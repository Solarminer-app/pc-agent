package de.verdox.solarminer.pcagent.mining.economics;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.EfficiencySweepService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import de.verdox.solarminer.pcagent.support.AtomicFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Local, explicitly enabled GPU coin and power-target selection based on measured gross USD per kWh. */
@Service
public class CoinHoppingService {
    private static final Duration MAX_PROFILE_AGE = Duration.ofDays(30);
    private static final Duration MAX_MARKET_AGE = Duration.ofHours(2);
    private static final int DEFAULT_COOLDOWN_MINUTES = 30;
    private static final double DEFAULT_THRESHOLD_PERCENT = 5;
    private final ObjectMapper json;
    private final Path file;
    private final GpuEfficiencyStore profiles;
    private final MiningPerformanceProfileStore performanceProfiles;
    private final MarketDataService market;
    private final EconomicPlanningService planning;
    private final WorkerAssignmentService assignments;
    private final LocalGpuPowerService power;
    private final BenchmarkSessionService benchmark;
    private final EfficiencySweepService sweep;
    private final LocalRunLock runLock;
    private final CoinHoppingLedger ledger;
    private final EnergyJournalService energy;
    private de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService ownerSettings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOwnerSettings(de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService ownerSettings) {
        this.ownerSettings = ownerSettings;
    }
    private volatile Settings settings;
    private volatile Instant lastSwitchAt;
    private volatile String lastOutcome;

    public CoinHoppingService(ObjectMapper json,
                              @Value("${solarminer.agent.coin-hopping-file:./solarminer-agent/coin-hopping.json}") String path,
                              GpuEfficiencyStore profiles, MiningPerformanceProfileStore performanceProfiles,
                              MarketDataService market, EconomicPlanningService planning,
                              WorkerAssignmentService assignments, LocalGpuPowerService power,
                              BenchmarkSessionService benchmark, EfficiencySweepService sweep, LocalRunLock runLock,
                              CoinHoppingLedger ledger, EnergyJournalService energy) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.profiles = profiles;
        this.performanceProfiles = performanceProfiles;
        this.market = market;
        this.planning = planning;
        this.assignments = assignments;
        this.power = power;
        this.benchmark = benchmark;
        this.sweep = sweep;
        this.runLock = runLock;
        this.ledger = ledger;
        this.energy = energy;
        this.settings = load();
        if (this.settings.enabled()) {
            try {
                Instant now = Instant.now();
                ledger.begin(this.settings.deviceId(), this.settings.targetWatts(), now);
                ledger.mark(now);
            }
            catch (RuntimeException failure) {
                this.settings = Settings.disabled();
                this.lastOutcome = "coin_hopping_ledger_save_failed";
            }
        }
    }

    private Settings load() {
        try {
            Persisted persisted = json.readValue(Files.readString(file), Persisted.class);
            lastSwitchAt = persisted.lastSwitchAt();
            Settings saved = persisted.settings();
            if (saved != null && (saved.cooldownMinutes() == 0 || saved.thresholdPercent() == 0))
                saved = new Settings(saved.enabled(), saved.deviceId(), saved.targetWatts(),
                        DEFAULT_COOLDOWN_MINUTES, DEFAULT_THRESHOLD_PERCENT);
            // Earlier releases stored a fixed target. Hopping now optimizes the target too.
            return saved != null && (!saved.enabled() || (saved.deviceId() != null
                    && !saved.deviceId().isBlank()))
                    ? new Settings(saved.enabled(), saved.deviceId(), 0, saved.cooldownMinutes(), saved.thresholdPercent())
                    : Settings.disabled();
        } catch (Exception ignored) {
            // A damaged file never opts the agent into automatic switching.
            return Settings.disabled();
        }
    }

    public synchronized Settings configure(Settings requested) {
        if (requested == null) throw new IllegalArgumentException("coin_hopping_invalid_settings");
        if (requested.cooldownMinutes() < 1 || requested.cooldownMinutes() > 1440
                || !Double.isFinite(requested.thresholdPercent())
                || requested.thresholdPercent() < 0.1 || requested.thresholdPercent() > 100)
            throw new IllegalArgumentException("coin_hopping_invalid_settings");
        if (requested.enabled()) {
            LocalGpuPowerService.Gpu gpu = power.snapshot().stream()
                    .filter(value -> value.deviceId().equals(requested.deviceId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("coin_hopping_gpu_missing"));
            if (!gpu.supportsDynamicPowerScaling())
                throw new IllegalArgumentException("coin_hopping_target_unavailable");
            boolean swept = profiles.all().stream().anyMatch(profile -> requested.deviceId().equals(profile.deviceId())
                    && profile.bestStableWatts() != null && profile.steps() != null
                    && profile.steps().stream().anyMatch(step -> step.stable()
                    && step.limitWatts() >= gpu.minWatts() && step.limitWatts() <= gpu.maxWatts()
                    && positive(step.medianHashrateHs()) && positive(step.avgPowerWatts())));
            boolean observed = performanceProfiles.all().stream().anyMatch(profile ->
                    requested.deviceId().equals(profile.workerId()) && profile.powerTargetWatts() != null
                    && profile.powerTargetWatts() >= gpu.minWatts() && profile.powerTargetWatts() <= gpu.maxWatts()
                    && profile.observations() >= 12 && profile.hashrateHps() > 0 && profile.powerWatts() > 0);
            if (!swept && !observed)
                throw new IllegalArgumentException("coin_hopping_target_unmeasured");
        }
        Settings next = new Settings(requested.enabled(), requested.deviceId(), 0,
                requested.cooldownMinutes(), requested.thresholdPercent());
        Instant now = Instant.now();
        if (next.enabled()) ledger.begin(next.deviceId(), next.targetWatts(), now);
        ledger.mark(now);
        if (!AtomicFileStore.writeJson(file, new Persisted(next, lastSwitchAt), json, "coin-hopping-"))
            throw new IllegalStateException("coin_hopping_save_failed");
        settings = next;
        return next;
    }

    public View view() {
        Settings current = settings;
        return view(current.deviceId(), current.targetWatts());
    }

    public View view(String deviceId, int targetWatts) {
        Settings current = settings;
        if (deviceId == null || deviceId.isBlank())
            return new View(current, List.of(), null, lastSwitchAt, lastOutcome, ledger.summary());
        List<Candidate> candidates = candidates(deviceId, targetWatts);
        return new View(current, candidates, candidates.isEmpty() ? null : candidates.getFirst().coin(), lastSwitchAt,
                lastOutcome, ledger.summary());
    }

    List<Candidate> candidates(String deviceId, int targetWatts) {
        Instant now = Instant.now();
        LocalGpuPowerService.Gpu gpu = power.snapshot().stream()
                .filter(value -> deviceId.equals(value.deviceId())).findFirst().orElse(null);
        if (gpu == null || !gpu.supportsDynamicPowerScaling()) return List.of();
        List<EconomicPlanningService.CoinCapability> capabilities = planning.capabilities().workers().stream()
                .filter(worker -> deviceId.equals(worker.workerId())).findFirst()
                .map(EconomicPlanningService.WorkerCapability::coins).orElse(List.of());
        List<Candidate> result = new ArrayList<>();
        for (GpuEfficiencyStore.Profile profile : profiles.all()) {
            if (!deviceId.equals(profile.deviceId()) || profile.bestStableWatts() == null || profile.testedAt() == null
                    || profile.testedAt().isBefore(now.minus(MAX_PROFILE_AGE)) || profile.steps() == null) continue;
            Coin coin = Coin.byIdOrNull(profile.coin());
            if (coin == null || !coin.isGpu() || !coin.matchesAlgorithm(profile.algorithm())) continue;
            for (GpuEfficiencyStore.StepResult step : profile.steps()) {
                if (!step.stable() || step.limitWatts() < gpu.minWatts() || step.limitWatts() > gpu.maxWatts()
                        || (targetWatts > 0 && step.limitWatts() != targetWatts)
                        || !positive(step.medianHashrateHs()) || !positive(step.avgPowerWatts())) continue;
                addCandidate(result, capabilities, coin, step.limitWatts(), step.avgPowerWatts(), step.medianHashrateHs(),
                        profile.testedAt(), "SWEEP", now);
            }
        }
        for (MiningPerformanceProfileStore.Profile profile : performanceProfiles.all()) {
            if (!deviceId.equals(profile.workerId()) || profile.powerTargetWatts() == null
                    || profile.powerTargetWatts() < gpu.minWatts() || profile.powerTargetWatts() > gpu.maxWatts()
                    || (targetWatts > 0 && profile.powerTargetWatts() != targetWatts) || profile.observations() < 12
                    || profile.measuredAt() == null || profile.measuredAt().isBefore(now.minus(MAX_PROFILE_AGE))) continue;
            Coin coin = Coin.byIdOrNull(profile.coin());
            if (coin == null || !coin.isGpu() || !coin.matchesAlgorithm(profile.algorithm())) continue;
            addCandidate(result, capabilities, coin, profile.powerTargetWatts(), (double) profile.powerWatts(), profile.hashrateHps(),
                    profile.measuredAt(), profile.source(), now);
        }
        // Preserve every measured target; only replace an older observation of the same point.
        return result.stream().collect(java.util.stream.Collectors.toMap(
                        value -> value.coin() + "|" + value.limitWatts(), value -> value,
                        (left, right) -> left.measuredAt().isAfter(right.measuredAt()) ? left : right))
                .values().stream().sorted(Comparator.comparingDouble(Candidate::usdPerKwh).reversed()).toList();
    }

    private void addCandidate(List<Candidate> result, List<EconomicPlanningService.CoinCapability> capabilities,
                              Coin coin, int targetWatts, double measuredWatts, double hashrateHps,
                              Instant measuredAt, String source, Instant now) {
        if (!positive(measuredWatts) || !positive(hashrateHps)
                || capabilities.stream().noneMatch(value -> coin.id().equals(value.coin())
                && value.configured() && value.feeRouteReady())) return;
        MarketDataService.Forecast forecast = market.forecastForProfile(coin.id(), hashrateHps);
        if (forecast == null || !forecast.available() || forecast.stale() || forecast.updatedAt() == null
                || forecast.updatedAt().isBefore(now.minus(MAX_MARKET_AGE))
                || !positive(forecast.usdPerDay()) || !positive(forecast.coinsPerDay())) return;
        double usdPerKwh = forecast.usdPerDay() / (measuredWatts * 24.0 / 1000.0);
        if (positive(usdPerKwh)) result.add(new Candidate(coin.id(), targetWatts, measuredWatts,
                hashrateHps, usdPerKwh, measuredAt, forecast.updatedAt(), source,
                forecast.coinsPerDay(), forecast.usdPerDay()));
    }

    private static boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }

    @Scheduled(initialDelayString = "${solarminer.agent.coin-hopping-interval-ms:600000}",
            fixedDelayString = "${solarminer.agent.coin-hopping-interval-ms:600000}")
    public synchronized void evaluate() {
        try { evaluateCurrent(); }
        catch (RuntimeException failure) {
            lastOutcome = "coin_hopping_evaluation_failed";
            markLedger(Instant.now());
        }
    }

    private void evaluateCurrent() {
        Settings current = settings;
        if (!current.enabled()) return;
        if (ownerSettings != null && !"MANUAL".equals(ownerSettings.get().powerModeFor(current.deviceId()))) return;
        Instant now = Instant.now();
        if (benchmark.status().running() || sweep.status().running()
                || planning.capabilities().activePlanExpiresAt() != null) {
            lastOutcome = "coin_hopping_measurement_or_plan_active";
            markLedger(now);
            return;
        }
        List<Candidate> choices = candidates(current.deviceId(), 0);
        if (choices.size() < 2) { lastOutcome = "coin_hopping_insufficient_candidates"; markLedger(now); return; }
        EconomicPlanningService.WorkerCapability worker = planning.capabilities().workers().stream()
                .filter(value -> current.deviceId().equals(value.workerId())).findFirst().orElse(null);
        if (worker == null) { lastOutcome = "coin_hopping_gpu_missing"; markLedger(now); return; }
        MinerStats.Worker miningWorker = assignments.workers().stream()
                .filter(value -> current.deviceId().equals(value.deviceId())).map(WorkerAssignmentService.WorkerView::telemetry)
                .filter(Objects::nonNull).findFirst().orElse(null);
        if (miningWorker == null || miningWorker.miningStatus() != MinerStats.MinerStatus.MINING) {
            lastOutcome = "coin_hopping_worker_not_mining"; markLedger(now); return;
        }
        LocalGpuPowerService.Gpu gpu = power.snapshot().stream()
                .filter(value -> current.deviceId().equals(value.deviceId())).findFirst().orElse(null);
        if (gpu == null || gpu.currentPowerLimitWatts() == null) {
            lastOutcome = "coin_hopping_gpu_missing"; markLedger(now); return;
        }
        List<Candidate> safeChoices = choices.stream().filter(value -> value.limitWatts() >= gpu.minWatts()
                && value.limitWatts() <= gpu.maxWatts()).toList();
        Candidate existing = safeChoices.stream().filter(value -> value.coin().equals(worker.selectedCoin())
                && value.limitWatts() == gpu.currentPowerLimitWatts()).findFirst().orElse(null);
        Candidate best = safeChoices.isEmpty() ? null : safeChoices.getFirst();
        if (existing == null) { lastOutcome = "coin_hopping_current_unmeasured"; markLedger(now); return; }
        if (best == null) { lastOutcome = "coin_hopping_insufficient_candidates"; markLedger(now); return; }
        Instant activeStartedAt = energy.overview().activeSessions().stream()
                .filter(session -> current.deviceId().equals(session.deviceId())
                        && existing.coin().equals(session.coin()))
                .map(EnergyJournalService.Session::startedAt).findFirst().orElse(null);
        try { ledger.account(now, existing.coin(), existing.limitWatts(), activeStartedAt, safeChoices); }
        catch (RuntimeException failure) { lastOutcome = "coin_hopping_ledger_save_failed"; return; }
        if ((best.coin().equals(existing.coin()) && best.limitWatts() == existing.limitWatts())
                || !worthHop(existing.usdPerKwh(), best.usdPerKwh(), current.thresholdPercent())) {
            lastOutcome = "coin_hopping_best_already_selected"; return;
        }
        if (!cooldownExpired(lastSwitchAt, now, current.cooldownMinutes())) {
            lastOutcome = "coin_hopping_cooldown_active"; return;
        }
        int previousLimit = gpu.currentPowerLimitWatts();
        if (!runLock.tryBegin("coin-hopping")) {
            lastOutcome = "coin_hopping_measurement_or_plan_active"; return;
        }
        try {
            if (planning.capabilities().activePlanExpiresAt() != null) {
                lastOutcome = "coin_hopping_measurement_or_plan_active"; return;
            }
            if (!best.coin().equals(existing.coin())
                    && !assignments.applyEconomicAssignment(current.deviceId(), best.coin()))
                throw new IllegalStateException("coin_hopping_switch_failed");
            if (!power.setTotalPowerTarget(best.limitWatts(), List.of(gpu)))
                throw new IllegalStateException("coin_hopping_target_failed");
            if (!best.coin().equals(existing.coin()) && !assignments.start(current.deviceId()))
                throw new IllegalStateException("coin_hopping_switch_failed");
            lastSwitchAt = Instant.now();
            lastOutcome = AtomicFileStore.writeJson(file, new Persisted(current, lastSwitchAt), json, "coin-hopping-")
                    ? (best.coin().equals(existing.coin()) ? "coin_hopping_power_adjusted" : "coin_hopping_switched")
                    : "coin_hopping_switch_not_persisted";
            try { ledger.action(lastSwitchAt, lastOutcome, existing.coin(), best.coin(), best.limitWatts(),
                    (best.usdPerKwh() / existing.usdPerKwh() - 1) * 100,
                    existing.usdPerKwh(), best.usdPerKwh()); }
            catch (RuntimeException ignored) { lastOutcome = "coin_hopping_switch_not_recorded"; }
        } catch (RuntimeException failure) {
            boolean restored = false;
            try { restored = best.coin().equals(existing.coin())
                    || assignments.applyEconomicAssignment(current.deviceId(), existing.coin()); }
            catch (RuntimeException ignored) { }
            try { power.setTotalPowerTarget(previousLimit, List.of(gpu)); } catch (RuntimeException ignored) { }
            if (restored && !best.coin().equals(existing.coin())) {
                try { assignments.start(current.deviceId()); } catch (RuntimeException ignored) { }
            }
            lastOutcome = "coin_hopping_switch_failed";
            try { ledger.action(Instant.now(), lastOutcome, existing.coin(), best.coin(), best.limitWatts(),
                    (best.usdPerKwh() / existing.usdPerKwh() - 1) * 100,
                    existing.usdPerKwh(), best.usdPerKwh()); } catch (RuntimeException ignored) { }
        } finally {
            runLock.end();
        }
    }

    private void markLedger(Instant now) {
        try { ledger.mark(now); } catch (RuntimeException ignored) { lastOutcome = "coin_hopping_ledger_save_failed"; }
    }

    static boolean worthHop(double currentUsdPerKwh, double bestUsdPerKwh, double thresholdPercent) {
        return currentUsdPerKwh > 0 && bestUsdPerKwh >= currentUsdPerKwh * (1 + thresholdPercent / 100.0);
    }

    static boolean cooldownExpired(Instant lastSwitchAt, Instant now, int cooldownMinutes) {
        return lastSwitchAt == null || !now.isBefore(lastSwitchAt.plus(Duration.ofMinutes(cooldownMinutes)));
    }

    public record Settings(boolean enabled, String deviceId, int targetWatts,
                           int cooldownMinutes, double thresholdPercent) {
        static Settings disabled() { return new Settings(false, null, 0, DEFAULT_COOLDOWN_MINUTES, DEFAULT_THRESHOLD_PERCENT); }
    }
    private record Persisted(Settings settings, Instant lastSwitchAt) { }
    public record Candidate(String coin, int limitWatts, double measuredWatts, double hashrateHps,
                            double usdPerKwh, Instant measuredAt, Instant marketAt, String source,
                            double coinsPerDay, double usdPerDay) { }
    public record View(Settings settings, List<Candidate> candidates, String recommendedCoin,
                       Instant lastSwitchAt, String lastOutcome, CoinHoppingLedger.Summary summary) { }
}
