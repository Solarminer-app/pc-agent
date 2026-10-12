package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;

/**
 * Aggregated local read model (refactoring Phase 5b, owner decision O3 of 2026-10-11).
 * Every dashboard section exists exactly once here with its historical collection cadence;
 * the targeted live SSE channels and {@code GET /api/agent/local/status} aggregate read the
 * same cached sections where applicable, so one section is computed at most once per tick.
 * The aggregate wraps section payloads with {@code generatedAt} and
 * {@code stale}. This is local UI state only; it introduces no Node, pool, fee or
 * cross-repository wire contract.
 */
@Service
public class AgentStatusService {
    /** One collected section: payload plus freshness metadata for the aggregate wire shape. */
    public record Section(Object payload, long generatedAt, boolean stale) { }

    /** The full aggregate; sections keep a stable, ordered key set. */
    public record Aggregate(List<Sectioned> sections, long generatedAt) { }
    public record Sectioned(String name, Section section) { }

    /**
     * Operator-facing alert row. titleKey/descriptionKey/actionKey are closed-catalog i18n
     * keys the UI translates; when descriptionKey is null, descriptionLiteral is shown
     * verbatim (worker display names — the same raw text the JS built before).
     */
    public record Alert(String tone, String titleKey, Map<String, Object> titleParams,
                        String descriptionKey, Map<String, Object> descriptionParams,
                        String descriptionLiteral, String href, String actionKey) { }
    /** Derived dashboard summary the UI used to compute from raw fields itself. */
    public record Summary(boolean proxyReachable, String proxyMode, Boolean nodeConnected,
                          int workerErrorCount, int assignedWorkerCount, int workerCount) { }
    public record UiState(List<Alert> alerts, Summary summary) { }

    private static final class Cached {
        final Section section; final long expiresAt;
        Cached(Section section, long expiresAt) { this.section = section; this.expiresAt = expiresAt; }
    }

    private final Map<String, Supplier<Object>> producers = new LinkedHashMap<>();
    private final Map<String, Long> ttls = new LinkedHashMap<>();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /**
     * Dirty flags (REST/SSE loading plan P2): mutating endpoints report the sections their
     * write affected via {@link #invalidate(String...)}. A live SSE publisher re-evaluates
     * its section on the next sweep; a REST read re-evaluates its dirty section directly.
     * The TTLs stay as the safety net
     * for changes nobody reports (proxy heartbeat, Node pushes, download progress, sensors).
     * Keyed by section name AND by non-section channel name (workers, power-control, ...) —
     * the publisher wakes those channels without a section cache of their own.
     */
    private final java.util.Set<String> dirty = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** P6 evidence counter: producer invocations per section (never on the wire). */
    private final Map<String, java.util.concurrent.atomic.AtomicLong> evaluations = new ConcurrentHashMap<>();
    private final MiningController mining;
    private final AgentPowerController power;
    private final NodeAssessmentController assessment;

    public AgentStatusService(MiningController mining, AgentPowerController power,
                              TelemetryController telemetry, NodeAssessmentController assessment,
                              EconomicsController economics) {
        this.mining = mining;
        this.power = power;
        this.assessment = assessment;
        // Section names are wire keys of the aggregate. Since P2/P6 (2026-10-11) these TTLs
        // are the SAFETY NET for unreported external changes only: local mutations call
        // invalidate(...) and the 500 ms publisher sweep re-evaluates immediately, so the
        // numbers below no longer drive the perceived freshness after an operator action.
        // overview recomputes GPU discovery, forecasts and fee lookups: 2 s while dirty
        // pushes carry mutations, raised from the old 1 s tick; the plan's 5 s net applies
        // only once no consumer forces re-reads — keep 2 s while it is the primary board.
        add("overview", mining::overview, 2_000);
        // Pure configuration: changes only through reported POSTs — the TTL is the 30 s net.
        add("settings", power::settings, 30_000);
        add("telemetry", telemetry::telemetry, 1_000);
        // Phase 5d: the proxy gate and the energy journal live in the merged proxy
        // resource and the economics controller; section names and payloads are unchanged.
        // proxy-gate keeps 1 s: the managed-proxy download progress changes externally.
        add("proxy-gate", mining::proxyGate, 1_000);
        add("fee-tier", mining::feeTier, 5_000);
        // Energy journal: settings arrive via reported POSTs; sessions start/stop with the
        // miner lifecycle — 30 s net per the P6 proposal.
        add("energy", economics::overview, 30_000);
        add("node-assessment", assessment::get, 5_000);
        add("wallets", mining::walletSnapshot, 15_000);
        // Static closed-enum catalog: id/ticker/name/algorithm/device per mining coin. The UI
        // renders ticker chips, editor options and GPU filters from this instead of keeping
        // its own hardcoded coin maps (Phase 5c).
        add("coin-catalog", AgentStatusService::coinCatalog, 3_600_000);
    }

    /** Derived straight from the closed Coin enum — the only coin identity in the product. */
    public static List<CoinRow> coinCatalog() {
        return de.verdox.solarminer.pcagent.coin.Coin.miningCoins().stream()
                .map(coin -> new CoinRow(coin.id(), coin.ticker(), coin.displayName(),
                        coin.displayAlgorithm(), coin.deviceLabel(), coin.experimental()))
                .toList();
    }

    public record CoinRow(String id, String ticker, String name, String algorithm, String device, boolean experimental) { }

    private void add(String name, Supplier<Object> producer, long ttlMs) {
        producers.put(name, producer);
        ttls.put(name, ttlMs);
    }

    /**
     * Reports that a local mutation changed the named sections/channels. Section-backed
     * channels re-run their producer on the next sweep even inside the TTL; non-section
     * channels (workers, power-control, miner-options, ...) are woken for an immediate
     * re-evaluation. Unknown names are kept for the publisher (channel wake) and ignored
     * by the section cache.
     */
    public void invalidate(String... names) {
        for (String name : names) if (name != null) dirty.add(name);
    }

    /** Whether a section/channel was reported dirty and not yet re-evaluated. */
    public boolean isDirty(String name) { return dirty.contains(name); }

    /** The publisher calls this after evaluating a channel so a wake happens at most once. */
    public void clearDirty(String name) { dirty.remove(name); }

    /** Total producer invocations per section (P6 measurement counter, diagnostics only). */
    public long evaluationCount(String name) {
        java.util.concurrent.atomic.AtomicLong counter = evaluations.get(name);
        return counter == null ? 0 : counter.get();
    }

    /** Per-section TTL plus evaluation count for the local diagnostics endpoint. */
    public List<SectionStats> sectionStats() {
        return producers.keySet().stream()
                .map(name -> new SectionStats(name, ttls.get(name), evaluationCount(name)))
                .toList();
    }

    public record SectionStats(String section, long ttlMs, long evaluations) { }

    /** Runs a producer with the evaluation counter; failures count too (they are work). */
    private Object collect(String name) {
        evaluations.computeIfAbsent(name, n -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        return producers.get(name).get();
    }

    /**
     * Raw section payload for the individual SSE channels. Throws when collection failed so
     * the channel error snapshot stays identical to the pre-aggregate behaviour; failures are
     * cached briefly (2 s) to keep a broken producer from re-running every tick.
     */
    public Object payload(String name) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(name);
        if (cached != null && now < cached.expiresAt && !dirty.contains(name)) {
            if (cached.section.stale()) throw new IllegalStateException("section " + name + " failed recently");
            return cached.section.payload();
        }
        try {
            Object value = collect(name);
            cache.put(name, new Cached(new Section(value, now, false), now + ttls.get(name)));
            // A successful re-collection consumes the dirty flag: within one sweep a
            // section is re-collected at most once even when several consumers read it.
            dirty.remove(name);
            return value;
        } catch (RuntimeException failure) {
            // Keep the flag: the 2 s failure cache throttles the retry, and the section
            // must recover as soon as that throttle expires rather than waiting for the TTL.
            cache.put(name, new Cached(new Section(null, now, true), now + 2_000));
            throw failure;
        }
    }

    /** The complete aggregate read model retained for compatibility with existing callers. */
    public Aggregate aggregate() {
        return aggregate(null);
    }

    /**
     * Reads only the sections required by the current page. Independent cold sections are
     * collected concurrently, so an optional wallet, market or proxy lookup cannot serialize
     * the dashboard's first paint. A missing or empty selection intentionally retains the
     * complete legacy response. Unknown names are ignored rather than exposing an error to a
     * client that has been updated ahead of the agent.
     */
    public Aggregate aggregate(java.util.Collection<String> requestedNames) {
        long now = System.currentTimeMillis();
        boolean complete = requestedNames == null || requestedNames.isEmpty();
        List<String> names = complete ? List.copyOf(producers.keySet()) : requestedNames.stream()
                .filter(producers::containsKey)
                .distinct()
                .toList();
        boolean includeUi = complete || requestedNames.contains("ui");
        List<Sectioned> sections = new java.util.ArrayList<>(names.size() + (includeUi ? 1 : 0));

        // The producers are independent read models. Join in request order to keep the wire
        // response deterministic while allowing slow cold caches to overlap.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Sectioned>> reads = names.stream()
                    .map(name -> executor.submit(() -> aggregateSection(name, now)))
                    .toList();
            for (Future<Sectioned> read : reads) {
                try {
                    sections.add(read.get());
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("aggregate status collection interrupted", interruption);
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("aggregate status collection failed", failure.getCause());
                }
            }
        }
        // uiState deliberately comes after its source sections. That lets it re-use the fresh
        // overview/node cache instead of causing duplicate work while producers are in flight.
        if (includeUi) sections.add(new Sectioned("ui", new Section(uiState(), now, false)));
        return new Aggregate(List.copyOf(sections), now);
    }

    private Sectioned aggregateSection(String name, long now) {
        Cached cached = cache.get(name);
        if (cached != null && now < cached.expiresAt && !dirty.contains(name)) {
            return new Sectioned(name, cached.section);
        }
        try {
            Object value = collect(name);
            Section section = new Section(value, now, false);
            cache.put(name, new Cached(section, now + ttls.get(name)));
            dirty.remove(name);
            return new Sectioned(name, section);
        } catch (RuntimeException failure) {
            Section stale = new Section(null, now, true);
            cache.put(name, new Cached(stale, now + 2_000));
            return new Sectioned(name, stale);
        }
    }

    /**
     * Derived alerts/summary (Phase 5c groundwork): the status-strip and alert-list logic the
     * overview page used to compute in JS. All texts are existing closed-catalog keys — the UI
     * translates them; no new strings are introduced here.
     */
    public UiState uiState() {
        MiningController.AgentOverview overview;
        AgentControlSettingsHolder settings;
        boolean assessmentConnected;
        try {
            overview = (MiningController.AgentOverview) payload("overview");
            settings = new AgentControlSettingsHolder(power.settings());
            assessmentConnected = Boolean.TRUE.equals(
                    ((NodeAssessmentController.NodeAssessmentStatus) payload("node-assessment")).connected());
        } catch (RuntimeException failure) {
            return new UiState(List.of(), null);
        }
        List<Alert> alerts = new java.util.ArrayList<>();
        var proxy = overview.proxy();
        List<MinerStats.Worker> workers = overview.stats() == null || overview.stats().workers() == null
                ? List.of() : overview.stats().workers();
        List<MinerStats.Worker> errors = workers.stream().filter(w -> w.miningStatus() == MinerStats.MinerStatus.ERROR).toList();
        var installed = overview.coins().stream().filter(c -> c.binaryAvailable() && !c.experimental()).toList();
        var unconfigured = installed.stream().filter(c -> !c.configured()).findFirst().orElse(null);
        if (installed.isEmpty())
            alerts.add(new Alert("warn", "no_miner_installed_yet", Map.of(),
                    "choose_a_compatible_build_in_the_miner_libra", Map.of(), null, "/mining.html", "install_miner_0f7094"));
        else if (unconfigured != null)
            alerts.add(new Alert("warn", "setup_incomplete", Map.of(),
                    "coin_pool_payout_or_device_selection_is_miss", Map.of("coin", unconfigured.name()), null, "/workers.html", "configure_workers"));
        if (proxy == null || !proxy.reachable())
            alerts.add(new Alert("bad", "mining_connection_disconnected", Map.of(),
                    "the_proxy_is_unavailable_miners_cannot_start", Map.of(), null, "/proxy.html", "check_connection_6c056f"));
        if (!errors.isEmpty())
            alerts.add(new Alert("bad", "count_workers_with_errors", Map.of("count", errors.size()),
                    null, Map.of(), String.join(", ", errors.stream().map(w -> w.workerDisplayName()).toList()), "/workers.html", "check_workers"));
        if (Boolean.TRUE.equals(settings.externalControlEnabled())
                && settings.enabledAssignedDeviceIds(overview.gpus() == null ? List.of() : overview.gpus()).isEmpty())
            alerts.add(new Alert("warn", "node_control_enabled_but_no_device_is_assign", Map.of(),
                    "choose_at_least_one_worker_under_node_automa", Map.of(), null, "/automation.html", "open_node_automation"));
        if (alerts.isEmpty())
            alerts.add(new Alert("ok", "everything_is_within_expected_limits", Map.of(),
                    "no_action_needed_the_readings_below_show_cur", Map.of(), null, null, null));
        Summary summary = new Summary(proxy != null && proxy.reachable(),
                proxy == null ? null : proxy.mode(),
                assessmentConnected, errors.size(),
                settings.assignedDeviceIds(overview.gpus() == null ? List.of() : overview.gpus()).size(), workers.size());
        return new UiState(List.copyOf(alerts), summary);
    }

    /**
     * Thin adapter over the settings record replicating the JS assignment rules exactly:
     * a device counts as assigned when workerCoins resolves to a coin other than "none"
     * (per-device entry, with the "*" default for non-CPU devices), and as enabled for
     * Node automation when workerExternalControl is not explicitly false.
     */
    private record AgentControlSettingsHolder(de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService.Settings raw) {
        Boolean externalControlEnabled() { return raw.externalControlEnabled(); }

        /**
         * Device ids (cpu + GPU device ids, exactly the JS scope list) whose persisted coin
         * assignment is not "none". A null workerCoins map means "everything assigned",
         * matching the pre-refactor UI rule.
         */
        List<String> assignedDeviceIds(List<de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu> gpus) {
            List<String> devices = new java.util.ArrayList<>();
            devices.add("cpu");
            gpus.forEach(gpu -> devices.add(gpu.deviceId()));
            Map<String, String> coins = raw.workerCoins();
            if (coins == null) return devices;
            return devices.stream().filter(id -> {
                String coin = coins.get(id) != null ? coins.get(id)
                        : "cpu".equals(id) ? "none" : coins.getOrDefault("*", "none");
                return coin != null && !coin.isBlank() && !"none".equals(coin);
            }).toList();
        }

        List<String> enabledAssignedDeviceIds(List<de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu> gpus) {
            return assignedDeviceIds(gpus).stream()
                    .filter(id -> !Boolean.FALSE.equals(raw.workerExternalControl().get(id)))
                    .toList();
        }
    }
}
