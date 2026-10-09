package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Power-cap efficiency sweep: for every configured GPU miner (coin × GPU) the agent walks the
 * power limit downward and keeps only steps that prove stable under real mining load.
 *
 * <p>Hardware protection is the hard constraint of every step:</p>
 * <ul>
 *   <li>Only driver-verified power limits are written (read-back check inside
 *       {@link LocalGpuPowerService}); driver and persisted user limits are never undercut.</li>
 *   <li>Every sample checks the GPU temperature; exceeding the configured ceiling aborts the
 *       step immediately and keeps the last stable limit.</li>
 *   <li>A step counts as stable only while the miner process stays alive, keeps hashing with a
 *       valid pool connection and does not exceed the rejected-share ratio.</li>
 *   <li>After the run — including cancellation or failure — the previous power limits and the
 *       previous miner state are always restored.</li>
 * </ul>
 */
@Service
public class EfficiencySweepService {
    /** Five-second samples; twelve points cover at least one minute of steady-state mining per step. */
    static final int SAMPLES_PER_STEP = 12;
    static final long SAMPLE_INTERVAL_MILLIS = 5_000;
    /** Matches the Pearl health monitor's 90 s first-job window plus a scheduling margin. */
    static final Duration STARTUP_GRACE = Duration.ofSeconds(95);
    private static final List<String> GPU_COINS = List.of("ravencoin", "ethereumclassic", "decred", "quantus");

    private final PearlMinerService pearl;
    private final GpuCoinMinerService gpuCoins;
    private final LocalGpuPowerService power;
    private final MinerConsoleService consoles;
    private final PayoutDefaultsService payoutDefaults;
    private final ProxyConfigurationService proxyConfiguration;
    private final GpuEfficiencyStore store;
    private final LocalRunLock lock;
    private final int stepWatts;
    private final double maxTemperatureC;
    private final double maxRejectedRatio;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("pc-agent-efficiency-sweep").daemon(true).unstarted(r));
    private volatile Session session = Session.idle();
    private volatile boolean cancel;

    public EfficiencySweepService(PearlMinerService pearl, GpuCoinMinerService gpuCoins, LocalGpuPowerService power,
                                  MinerConsoleService consoles, PayoutDefaultsService payoutDefaults,
                                  ProxyConfigurationService proxyConfiguration,
                                  GpuEfficiencyStore store, LocalRunLock lock,
                                  @Value("${solarminer.agent.sweep.step-watts:15}") int stepWatts,
                                  @Value("${solarminer.agent.sweep.max-temperature-c:85}") double maxTemperatureC,
                                  @Value("${solarminer.agent.sweep.max-rejected-ratio:0.10}") double maxRejectedRatio) {
        this.pearl = pearl;
        this.gpuCoins = gpuCoins;
        this.power = power;
        this.consoles = consoles;
        this.payoutDefaults = payoutDefaults;
        this.proxyConfiguration = proxyConfiguration;
        this.store = store;
        this.lock = lock;
        this.stepWatts = Math.max(5, stepWatts);
        this.maxTemperatureC = maxTemperatureC;
        this.maxRejectedRatio = maxRejectedRatio;
    }

    public synchronized Session start() {
        if (session.running()) throw new IllegalStateException("Ein Effizienz-Sweep läuft bereits");
        if (pearl.hasExternalMinerProcess())
            throw new IllegalStateException("Effizienz-Sweep abgebrochen: SRBMiner läuft außerhalb des PC-Agent; Leistungsgrenzen können nicht sicher überwacht werden");
        List<LocalGpuPowerService.Gpu> discovered = power.discover();
        List<Target> targets = targets(discovered);
        if (targets.isEmpty())
            throw new IllegalStateException(unavailableReason(discovered, pearl.binaryAvailable()));
        if (!lock.tryBegin("efficiency-sweep"))
            throw new IllegalStateException("Ein anderer Messlauf (Benchmark) läuft gerade; der Sweep wartet, bis er beendet ist");
        cancel = false;
        session = new Session(true, "Preparing", Instant.now(), 0, targets.size(), List.of(), null);
        try {
            executor.submit(() -> run(targets));
        } catch (RuntimeException e) {
            session = Session.idle();
            lock.end();
            throw new IllegalStateException("Effizienz-Sweep konnte nicht gestartet werden", e);
        }
        return session;
    }

    public Session status() {
        return session;
    }

    public synchronized Session cancel() {
        cancel = true;
        return status();
    }

    public List<GpuEfficiencyStore.Profile> profiles() {
        return store.all();
    }

    /**
     * Coins with a saved configuration and a usable SRBMiner, restricted to driver-verified GPUs.
     * A coin the operator never configured is still measured against the fee-backend house
     * (dev-fee) payout through an ephemeral in-memory override, exactly like an empty wallet in
     * the operator UI. The override never touches disk and is cleared when the sweep ends.
     */
    private List<Target> targets(List<LocalGpuPowerService.Gpu> discovered) {
        List<Target> targets = new ArrayList<>();
        Map<String, LocalGpuPowerService.Gpu> verified = new LinkedHashMap<>();
        discovered.stream().filter(LocalGpuPowerService.Gpu::supportsDynamicPowerScaling)
                .forEach(gpu -> verified.put(gpu.deviceId(), gpu));
        if (verified.isEmpty() || !pearl.binaryAvailable()) return List.of();
        String devices = String.join(",", verified.values().stream()
                .map(gpu -> gpu.vendor() + ":" + gpu.index()).toList());
        if (pearl.configuration() != null)
            for (LocalGpuPowerService.Gpu gpu : pearl.selectedGpus())
                if (verified.containsKey(gpu.deviceId())) targets.add(new Target("pearl", "PearlHash", gpu, null));
        else {
            PearlMinerService.Config fallback = pearlFeeFallback(devices);
            if (fallback != null)
                for (LocalGpuPowerService.Gpu card : verified.values())
                    targets.add(new Target("pearl", "PearlHash", card, new ConfigOverride(fallback, null)));
        }
        for (String coin : GPU_COINS) {
            if (gpuCoins.configuration(coin) != null) {
                for (LocalGpuPowerService.Gpu gpu : gpuCoins.selected(coin))
                    if (verified.containsKey(gpu.deviceId()))
                        targets.add(new Target(coin, GpuCoinMinerService.algorithm(coin), gpu, null));
                continue;
            }
            GpuCoinMinerService.Config fallback = gpuCoinFeeFallback(coin, devices);
            if (fallback == null) continue;
            for (LocalGpuPowerService.Gpu gpu : verified.values())
                targets.add(new Target(coin, GpuCoinMinerService.algorithm(coin), gpu, new ConfigOverride(null, fallback)));
        }
        return List.copyOf(targets);
    }

    static String unavailableReason(List<LocalGpuPowerService.Gpu> discovered, boolean binaryAvailable) {
        if (discovered.isEmpty())
            return "Power-Limit-Test kann nicht gestartet werden: Keine GPU wurde erkannt";
        List<LocalGpuPowerService.Gpu> writable = discovered.stream()
                .filter(LocalGpuPowerService.Gpu::supportsDynamicPowerScaling).toList();
        if (writable.isEmpty()) {
            String driverStatus = discovered.stream()
                    .map(gpu -> gpu.model() + ": " + firstLine(gpu.regulationError()))
                    .distinct().limit(3).reduce((left, right) -> left + "; " + right).orElse("Treiberzugriff nicht verfügbar");
            return "Power-Limit-Test kann nicht gestartet werden: Keine GPU-Leistungsgrenze ist schreibbar. "
                    + "Starte den PC-Agent mit den für den GPU-Treiber nötigen Administrator-/root-Rechten. "
                    + "Treiberstatus: " + driverStatus;
        }
        if (!binaryAvailable)
            return "Power-Limit-Test kann nicht gestartet werden: SRBMiner-MULTI ist nicht installiert";
        return "Power-Limit-Test kann nicht gestartet werden: Für keine regelbare GPU ist ein startbereiter "
                + "GPU-Miner mit gültiger Pool-/Fee-Route verfügbar";
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) return "Power-Cap nicht verfügbar";
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    /** Ephemeral Pearl config against the fee-backend house target, or null when unreachable/invalid. */
    private PearlMinerService.Config pearlFeeFallback(String devices) {
        PayoutDefaultsService.DefaultPayout payout = payoutDefaults.resolve("pearl").orElse(null);
        if (payout == null || proxyConfiguration.host() == null) return null;
        String worker = payout.workerPart() != null ? payout.workerPart() : "solarminer";
        PearlMinerService.Config fallback = new PearlMinerService.Config(payout.poolUrl(),
                proxyConfiguration.pearlUrl(), payout.walletPart(), worker, devices);
        try {
            PearlMinerService.validate(fallback);
            return fallback;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ephemeral GPU-coin config against the fee-backend house target, or null when unreachable/invalid. */
    private GpuCoinMinerService.Config gpuCoinFeeFallback(String coin, String devices) {
        PayoutDefaultsService.DefaultPayout payout = payoutDefaults.resolve(coin).orElse(null);
        if (payout == null || proxyConfiguration.host() == null) return null;
        String worker = payout.workerPart() != null && !payout.workerPart().isBlank() ? payout.workerPart() : "solarminer";
        String proxyUrl = switch (coin) {
            case "ravencoin" -> proxyConfiguration.ravencoinUrl();
            case "ethereumclassic" -> proxyConfiguration.ethereumclassicUrl();
            case "decred" -> proxyConfiguration.decredUrl();
            default -> proxyConfiguration.quantusUrl();
        };
        GpuCoinMinerService.Config fallback = new GpuCoinMinerService.Config(payout.poolUrl(),
                proxyUrl, payout.walletPart(), worker, devices);
        try {
            GpuCoinMinerService.validate(coin, fallback);
            return fallback;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void run(List<Target> targets) {
        try {
            execute(targets);
        } catch (RuntimeException e) {
            Session old = session;
            session = new Session(false, "Fehler: " + Objects.toString(e.getMessage(), "Sweep fehlgeschlagen"),
                    old.startedAt(), old.phaseIndex(), old.phaseCount(), old.results(), null);
        } finally {
            lock.end();
        }
    }

    private void execute(List<Target> targets) {
        // Safety snapshot: everything the sweep may touch is captured before the first write.
        Set<String> pearlWasMining = pearl.runningGpuDeviceIds();
        Set<String> pearlPausedBefore = pearl.manuallyPausedGpuKeys();
        Map<String, Set<String>> coinWasMining = new LinkedHashMap<>();
        Map<String, Set<String>> coinWasPaused = new LinkedHashMap<>();
        Map<String, Integer> limitsBefore = new LinkedHashMap<>();
        for (String coin : GPU_COINS) {
            if (gpuCoins.configuration(coin) == null) continue;
            for (GpuCoinMinerService.GpuState state : gpuCoins.gpuStates(coin, power.discover())) {
                String key = state.vendor() + ":" + state.index();
                if (state.running()) coinWasMining.computeIfAbsent(coin, ignored -> new java.util.HashSet<>()).add(key);
                if (state.manuallyPaused()) coinWasPaused.computeIfAbsent(coin, ignored -> new java.util.HashSet<>()).add(key);
            }
        }
        Set<String> targetDeviceIds = targets.stream().map(target -> target.gpu().deviceId()).collect(java.util.stream.Collectors.toSet());
        for (LocalGpuPowerService.Gpu gpu : power.refresh())
            if (targetDeviceIds.contains(gpu.deviceId()) && gpu.currentPowerLimitWatts() != null)
                limitsBefore.put(gpu.deviceId(), gpu.currentPowerLimitWatts());
        if (limitsBefore.size() != targetDeviceIds.size())
            throw new IllegalStateException("Nicht alle GPU-Power-Caps konnten vor dem Test sicher gelesen werden");

        List<GpuEfficiencyStore.Profile> produced = new ArrayList<>();
        int index = 0;
        String error = null;
        try {
            for (Target target : targets) {
                if (cancel) break;
                index++;
                sweepTarget(target, index, targets.size(), produced);
            }
        } catch (RuntimeException e) {
            error = Objects.toString(e.getMessage(), "Sweep failed");
        } finally {
            // Safety net: every ephemeral fee-backend override is removed even when a
            // target aborted early, so no dev-fee route can outlive the sweep.
            for (Target target : targets) clearOverride(target);
            boolean minersStopped = true;
            for (Target target : targets) minersStopped = stopMiner(target) && minersStopped;
            if (!minersStopped) error = appendError(error, "Konnte nicht alle Sweep-Miner anhalten");
            if (!power.restorePowerLimits(limitsBefore))
                error = appendError(error, "Konnte Leistungsgrenzen nicht vollständig wiederherstellen");
            try {
                boolean restored = pearl.restoreWorkerState(pearlPausedBefore, pearlWasMining);
                for (Map.Entry<String, Set<String>> entry : coinWasMining.entrySet())
                    for (String key : entry.getValue()) {
                        String[] parts = key.split(":");
                        restored = gpuCoins.resumeGpu(entry.getKey(), parts[0], Integer.parseInt(parts[1])) && restored;
                    }
                for (Map.Entry<String, Set<String>> entry : coinWasPaused.entrySet())
                    for (String key : entry.getValue()) {
                        String[] parts = key.split(":");
                        restored = gpuCoins.pauseGpu(entry.getKey(), parts[0], Integer.parseInt(parts[1])) && restored;
                    }
                if (!restored) error = appendError(error, "Konnte Miner-Zustand nicht vollständig wiederherstellen");
            } catch (RuntimeException e) {
                error = appendError(error, "Konnte Miner-Zustand nicht vollständig wiederherstellen: " + e.getMessage());
            }
            Session old = session;
            String result = error != null ? "Fehler: " + error
                    : cancel ? "Abgebrochen; Leistungsgrenzen und Miner-Zustand wiederhergestellt"
                    : "Fertig; Leistungsgrenzen und Miner-Zustand wiederhergestellt";
            session = new Session(false, result, old.startedAt(), targets.size(), targets.size(), produced, null);
        }
    }

    private void sweepTarget(Target target, int index, int count, List<GpuEfficiencyStore.Profile> produced) {
        String label = target.gpu().model() + " · " + target.algorithm();
        // Only one miner per physical GPU: stop every other coin on this card first.
        if (!stopAllOn(target.gpu())) throw new IllegalStateException("Andere Miner auf " + target.gpu().model() + " konnten nicht angehalten werden");
        applyOverride(target);
        LocalGpuPowerService.Gpu fresh = power.refresh().stream()
                .filter(gpu -> gpu.deviceId().equals(target.gpu().deviceId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("GPU wurde während des Tests getrennt"));
        int current = fresh.currentPowerLimitWatts() == null ? fresh.minWatts()
                : Math.min(fresh.maxWatts(), fresh.currentPowerLimitWatts());
        List<Integer> limits = candidateLimits(fresh.minWatts(), current, stepWatts);
        List<GpuEfficiencyStore.StepResult> steps = new ArrayList<>();
        GpuEfficiencyStore.StepResult best = null;
        for (int limit : limits) {
            if (cancel) break;
            update(label + " · " + limit + " W", index, count);
            if (!power.setTotalPowerTarget(limit, List.of(target.gpu()))) {
                steps.add(new GpuEfficiencyStore.StepResult(limit, null, null, null, false,
                        "Leistungsgrenze wurde nicht vom Treiber bestätigt; Schritt übersprungen"));
                break;
            }
            GpuEfficiencyStore.StepResult step = measureStep(target, limit, index, count);
            steps.add(step);
            if (!stopMiner(target)) throw new IllegalStateException("Sweep-Miner konnte nicht angehalten werden");
            sweepLog(target, label + " @ " + limit + " W: " + (step.stable()
                    ? "stabil, " + formatRate(step.medianHashrateHs()) + " · " + Math.round(step.avgPowerWatts()) + " W"
                    : "instabil (" + step.note() + ")"));
            if (!step.stable()) break;
            if (best == null || step.medianHashrateHs() / step.avgPowerWatts() > best.medianHashrateHs() / best.avgPowerWatts())
                best = step;
        }
        if (best != null) {
            GpuEfficiencyStore.Profile profile = new GpuEfficiencyStore.Profile(
                    target.gpu().deviceId(), target.gpu().model(), target.coin(), target.algorithm(),
                    best.limitWatts(), best.medianHashrateHs(), best.avgPowerWatts(),
                    best.medianHashrateHs() / best.avgPowerWatts(), Instant.now(), List.copyOf(steps));
            store.save(profile);
            produced.add(profile);
            sweepLog(target, label + ": bester stabiler Wert " + best.limitWatts() + " W ("
                    + formatRate(best.medianHashrateHs()) + ", " + Math.round(best.medianHashrateHs() / best.avgPowerWatts()) + " H/J)");
        } else {
            sweepLog(target, label + ": kein stabiler Undervolt-Wert gefunden");
        }
        clearOverride(target);
    }

    private void applyOverride(Target target) {
        if (target.override() == null) return;
        if (target.override().pearl() != null) pearl.setSweepOverride(target.override().pearl());
        if (target.override().gpuCoin() != null) gpuCoins.setSweepOverride(target.coin(), target.override().gpuCoin());
    }

    private void clearOverride(Target target) {
        if (target.override() == null) return;
        if (target.override().pearl() != null) pearl.clearSweepOverride();
        if (target.override().gpuCoin() != null) gpuCoins.clearSweepOverride(target.coin());
    }

    /** Descending candidates never exceed the limit that was active when the sweep began. */
    static List<Integer> candidateLimits(int minWatts, int currentWatts, int stepWatts) {
        List<Integer> limits = new ArrayList<>();
        for (int limit = Math.max(minWatts, currentWatts); limit > minWatts; limit -= stepWatts) limits.add(limit);
        limits.add(Math.max(minWatts, 0));
        return List.copyOf(limits);
    }

    private GpuEfficiencyStore.StepResult measureStep(Target target, int limit, int index, int count) {
        if (!startMiner(target))
            return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Miner konnte nicht gestartet werden");
        List<Double> rates = new ArrayList<>();
        List<Double> watts = new ArrayList<>();
        double maxTemp = Double.NEGATIVE_INFINITY;
        Instant startupDeadline = Instant.now().plus(STARTUP_GRACE);
        MinerStats.Worker worker = null;
        while (rates.size() < SAMPLES_PER_STEP) {
            if (cancel) return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Abgebrochen");
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Unterbrochen");
            }
            worker = findWorker(target);
            if (worker == null || worker.miningStatus() == MinerStats.MinerStatus.ERROR)
                return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Miner-Prozess ausgefallen");
            Double temperature = power.readTemperatureC(target.gpu());
            if (temperature == null)
                return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "GPU-Temperatur nicht lesbar; Sicherheitshalber abgebrochen");
            maxTemp = Math.max(maxTemp, temperature);
            if (maxTemp > maxTemperatureC)
                return new GpuEfficiencyStore.StepResult(limit, null, null, maxTemp, false,
                        "GPU wurde " + Math.round(maxTemp) + " °C heiß; Grenze über dem Schwellwert von " + Math.round(maxTemperatureC) + " °C");
            if (worker.miningStatus() == MinerStats.MinerStatus.MINING
                    && Double.isFinite(worker.terahashPerSecond()) && worker.terahashPerSecond() > 0
                    && Double.isFinite(worker.approximatedPowerUsageWatts()) && worker.approximatedPowerUsageWatts() > 0) {
                rates.add(worker.terahashPerSecond() * 1_000_000_000_000d);
                watts.add((double) worker.approximatedPowerUsageWatts());
            } else if (!Instant.now().isBefore(startupDeadline)) {
                return new GpuEfficiencyStore.StepResult(limit, null, null, maxTemp == Double.NEGATIVE_INFINITY ? null : maxTemp,
                        false, "Miner liefert trotz Pool-Jobs keine Hashrate");
            }
            update(target.gpu().model() + " · " + target.algorithm() + " · " + limit + " W · "
                    + rates.size() + "/" + SAMPLES_PER_STEP + " Messpunkte", index, count);
        }
        double median = median(rates);
        double avgWatts = watts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (median <= 0 || avgWatts <= 0)
            return new GpuEfficiencyStore.StepResult(limit, null, null, maxTemp, false, "Keine gültigen Messpunkte");
        long accepted = worker.acceptedShares() == null ? 0 : worker.acceptedShares();
        long rejected = worker.rejectedShares() == null ? 0 : worker.rejectedShares();
        if (accepted + rejected > 0 && (double) rejected / (accepted + rejected) > maxRejectedRatio)
            return new GpuEfficiencyStore.StepResult(limit, median, avgWatts, maxTemp, false,
                    rejected + " von " + (accepted + rejected) + " Shares verworfen (Power-Cap nicht stabil)");
        return new GpuEfficiencyStore.StepResult(limit, median, avgWatts, maxTemp, true, null);
    }

    private MinerStats.Worker findWorker(Target target) {
        // Re-discover so the worker row carries the live power draw instead of the
        // snapshot captured when the sweep started.
        LocalGpuPowerService.Gpu fresh = power.discover().stream()
                .filter(gpu -> gpu.deviceId().equals(target.gpu().deviceId())).findFirst().orElse(target.gpu());
        List<MinerStats.Worker> workers = "pearl".equals(target.coin())
                ? pearl.workerStats(List.of(fresh))
                : gpuCoins.workerStats(target.coin(), List.of(fresh));
        return workers.stream()
                .filter(w -> Objects.equals(w.deviceId(), target.gpu().deviceId())
                        && Objects.equals(w.currentAlgorithm(), target.algorithm()))
                .findFirst().orElse(null);
    }

    private boolean startMiner(Target target) {
        return "pearl".equals(target.coin())
                ? pearl.startGpu(target.gpu().vendor(), target.gpu().index())
                : gpuCoins.startGpu(target.coin(), target.gpu().vendor(), target.gpu().index());
    }

    private boolean stopMiner(Target target) {
        return MinerStopContext.with("Efficiency sweep", () -> "pearl".equals(target.coin())
                ? pearl.stopGpu(target.gpu().vendor(), target.gpu().index())
                : gpuCoins.stopGpu(target.coin(), target.gpu().vendor(), target.gpu().index()));
    }

    private boolean stopAllOn(LocalGpuPowerService.Gpu gpu) {
        return MinerStopContext.with("Efficiency sweep", () -> {
            boolean success = pearl.stopGpu(gpu.vendor(), gpu.index());
            for (String coin : GPU_COINS) success = gpuCoins.stopGpu(coin, gpu.vendor(), gpu.index()) && success;
            return success;
        });
    }

    private void sweepLog(Target target, String message) {
        String entry = "[Efficiency-Sweep] " + message;
        consoles.append("pearl", entry);
        consoles.append(target.coin(), entry);
    }

    private void update(String phase, int index, int count) {
        Session old = session;
        session = new Session(true, phase, old.startedAt(), index, count, List.of(), null);
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 0) return 0;
        return sorted.length % 2 == 1 ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }

    private static String formatRate(double hashrateHs) {
        if (hashrateHs >= 1_000_000_000d) return Math.round(hashrateHs / 1_000_000_000d) + " MH/s";
        if (hashrateHs >= 1_000_000d) return Math.round(hashrateHs / 1_000_000d) + " kH/s";
        return Math.round(hashrateHs) + " H/s";
    }

    private static String appendError(String current, String addition) {
        return current == null ? addition : current + "; " + addition;
    }

    @PreDestroy
    public void close() {
        cancel = true;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
                session = new Session(false, "Fehler: Wiederherstellung nach Abbruch nicht abgeschlossen",
                        session.startedAt(), session.phaseIndex(), session.phaseCount(), session.results(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Target(String coin, String algorithm, LocalGpuPowerService.Gpu gpu, ConfigOverride override) {
    }

    /** Ephemeral fee-backend configuration for a coin the operator never configured. */
    private record ConfigOverride(PearlMinerService.Config pearl, GpuCoinMinerService.Config gpuCoin) {
    }

    public record Session(boolean running, String phase, Instant startedAt, int phaseIndex, int phaseCount,
                          List<GpuEfficiencyStore.Profile> results, Long secondsRemaining) {
        static Session idle() {
            return new Session(false, "Idle", null, 0, 0, List.of(), 0L);
        }
    }
}
