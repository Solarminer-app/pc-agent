package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.coin.SweepMode;
import de.verdox.solarminer.pcagent.coin.SweepRunState;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.measurement.MeasurementRunGuard;
import de.verdox.solarminer.pcagent.mining.measurement.SampleCollector;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import de.verdox.solarminer.pcagent.miner.GpuState;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.MinerStopContext;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;

/**
 * Power-cap efficiency sweep: for every configured GPU miner (coin × GPU) the agent walks the
 * power limit downward and keeps only steps that prove stable under real mining load.
 *
 * <p>Hardware protection is the hard constraint of every step:</p>
 * <ul>
 *   <li>Only driver-verified power limits are written (read-back check inside
 *       {@link LocalGpuPowerService}); driver and persisted user limits are never undercut.</li>
 *   <li>Every sample checks the GPU temperature; exceeding the configured ceiling aborts that
 *       step immediately. The GPU is stopped and must cool below a safety margin before the
 *       sweep may try the next lower limit.</li>
 *   <li>A step counts as stable only while the miner process stays alive, keeps hashing with a
 *       valid pool connection and does not exceed the rejected-share ratio.</li>
 *   <li>After the run — including cancellation or failure — the previous power limits and the
 *       previous miner state are always restored.</li>
 * </ul>
 */
@Service
public class EfficiencySweepService {
    private de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService workerPolicies;
    @org.springframework.beans.factory.annotation.Autowired
    public void setWorkerPolicies(de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService policies) {
        this.workerPolicies = policies;
    }
    private static final Logger LOGGER = Logger.getLogger(EfficiencySweepService.class.getName());
    /** Five-second samples; twelve points cover at least one minute of steady-state mining per step. */
    static final int SAMPLES_PER_STEP = 12;
    static final long SAMPLE_INTERVAL_MILLIS = 5_000;
    private static final long UNSTABLE_RETRY_PAUSE_MILLIS = 5_000;
    private static final long TEMPERATURE_COOLDOWN_TIMEOUT_MILLIS = 120_000;
    private static final long TEMPERATURE_COOLDOWN_POLL_MILLIS = 2_000;
    private static final double TEMPERATURE_COOLDOWN_MARGIN_C = 3.0;
    private static final long ESTIMATED_STARTUP_SECONDS = 45;
    private final MinerFactory miners;
    private final LocalGpuPowerService power;
    private final MinerConsoleService consoles;
    private final PayoutDefaultsService payoutDefaults;
    private final ProxyConfigurationService proxyConfiguration;
    private final GpuEfficiencyStore store;
    private final BenchmarkSharingService sharing;
    private final int stepWatts;
    private final double maxTemperatureC;
    private final double maxRejectedRatio;
    private final Set<String> thermalBlocked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService controls;
    @org.springframework.beans.factory.annotation.Autowired
    public void setControls(de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService controls) { this.controls = controls; }
    /**
     * Time a step waits for the first valid hashrate sample. The sweep restarts the miner at
     * every power limit, and a cold restart costs real time before SRBMiner reports anything:
     * DAG load for kawpow/etchash-class algorithms plus SRBMiner's one-minute average window
     * can keep the reported hashrate at zero for close to three minutes while the miner is
     * perfectly healthy. This grace must outlive the miner monitor's own hashrate watchdog,
     * otherwise the sweep declares a working card unstable.
     */
    private final Duration startupGrace;
    private final MeasurementRunGuard guard;
    private volatile Session session = Session.idle();

    public EfficiencySweepService(MinerFactory miners, LocalGpuPowerService power,
                                  MinerConsoleService consoles, PayoutDefaultsService payoutDefaults,
                                  ProxyConfigurationService proxyConfiguration,
                                  GpuEfficiencyStore store, BenchmarkSharingService sharing, LocalRunLock lock,
                                  @Value("${solarminer.agent.sweep.step-watts:15}") int stepWatts,
                                  @Value("${solarminer.agent.sweep.max-temperature-c:85}") double maxTemperatureC,
                                  @Value("${solarminer.agent.sweep.max-rejected-ratio:0.10}") double maxRejectedRatio,
                                  @Value("${solarminer.agent.sweep.startup-grace-seconds:240}") long startupGraceSeconds) {
        this.miners = miners;
        this.power = power;
        this.consoles = consoles;
        this.payoutDefaults = payoutDefaults;
        this.proxyConfiguration = proxyConfiguration;
        this.store = store;
        this.sharing = sharing;
        this.guard = new MeasurementRunGuard(lock, "pc-agent-efficiency-sweep");
        this.stepWatts = Math.max(5, stepWatts);
        this.maxTemperatureC = Double.isFinite(maxTemperatureC) ? Math.max(40, Math.min(90, maxTemperatureC)) : 85;
        this.maxRejectedRatio = maxRejectedRatio;
        this.startupGrace = Duration.ofSeconds(Math.max(60, startupGraceSeconds));
    }

    public synchronized Session start() {
        return start(false, null, null, null);
    }

    /** Selection-aware start: only the chosen coins are measured; null/empty means all. */
    public synchronized Session start(List<String> selectedCoinIds) {
        return start(false, selectedCoinIds, null, null);
    }

    public synchronized Session start(List<String> selectedCoinIds, List<String> selectedWorkerIds) {
        return start(false, selectedCoinIds, selectedWorkerIds, null);
    }

    public synchronized Session start(List<String> selectedCoinIds, List<String> selectedWorkerIds, Integer maxConcurrentGpus) {
        return start(false, selectedCoinIds, selectedWorkerIds, maxConcurrentGpus);
    }

    public synchronized Session resume() {
        return resume(null);
    }

    public synchronized Session resume(List<String> selectedCoinIds) {
        return resume(selectedCoinIds, null);
    }

    public synchronized Session resume(List<String> selectedCoinIds, List<String> selectedWorkerIds) {
        return resume(selectedCoinIds, selectedWorkerIds, null);
    }

    public synchronized Session resume(List<String> selectedCoinIds, List<String> selectedWorkerIds, Integer maxConcurrentGpus) {
        if (session.running()) throw new IllegalStateException("Ein Effizienz-Sweep läuft bereits");
        if (session.runs().isEmpty() || session.runs().stream().allMatch(RunStatus::terminalComplete))
            throw new IllegalStateException("Es gibt keinen unterbrochenen Effizienz-Sweep zum Fortsetzen");
        return start(true, selectedCoinIds, selectedWorkerIds, maxConcurrentGpus);
    }

    private Session start(boolean resume, List<String> selectedCoinIds, List<String> selectedWorkerIds, Integer maxConcurrentGpus) {
        int gpuLimit = BenchmarkSessionService.validateGpuLimit(maxConcurrentGpus);
        if (session.running()) throw new IllegalStateException("Ein Effizienz-Sweep läuft bereits");
        Set<String> selection = BenchmarkSessionService.validateCoinSelection(selectedCoinIds);
        Session previous = session;
        if (miners.srbExternalMinerRunning())
            throw new IllegalStateException("Effizienz-Sweep abgebrochen: SRBMiner läuft außerhalb des PC-Agent; Leistungsgrenzen können nicht sicher überwacht werden");
        List<LocalGpuPowerService.Gpu> discovered = power.discover();
        Set<String> availableWorkers = new HashSet<>();
        availableWorkers.add(WorkerIds.CPU);
        discovered.stream().map(LocalGpuPowerService.Gpu::deviceId).forEach(availableWorkers::add);
        Set<String> workers = selectedWorkerIds == null ? Set.of() : BenchmarkSessionService.validateWorkerSelection(
                selectedWorkerIds, availableWorkers);
        if (selectedWorkerIds != null && workers.stream().noneMatch(id -> !WorkerIds.CPU.equals(id)))
            throw new IllegalArgumentException("benchmark_select_gpu_for_sweep");
        if (workerPolicies != null && !workerPolicies.hasMeasurements(workers.stream()
                .filter(id -> !WorkerIds.CPU.equals(id)).collect(java.util.stream.Collectors.toSet()), selection))
            throw new IllegalArgumentException("benchmark_no_allowed_combinations");
        List<Target> targets = targets(discovered, selection, workers);
        if (targets.isEmpty())
            throw new IllegalStateException(selectedWorkerIds == null
                    ? unavailableReason(discovered, miners.srbBinaryAvailable())
                    : selectedTargetUnavailableReason(discovered, workers,
                    selectedMinerAvailable(discovered, selection, workers)));
        if (!guard.begin("efficiency-sweep"))
            throw new IllegalStateException("Ein anderer Messlauf (Benchmark) läuft gerade; der Sweep wartet, bis er beendet ist");
        guard.resetCancel();
        thermalBlocked.clear();
        Instant startedAt = Instant.now();
        List<List<List<Target>>> referenceBatches = referenceBatches(targetGroups(targets));
        List<List<Target>> cohorts = referenceBatches.stream().flatMap(List::stream).toList();
        List<RunStatus> runs = initialRuns(referenceBatches);
        Map<String, GpuEfficiencyStore.Profile> checkpoint = resume ? reusableProfiles(previous) : Map.of();
        if (resume) {
            if (workerPolicies != null) checkpoint = checkpoint.entrySet().stream()
                    .filter(entry -> entry.getValue().steps() != null && entry.getValue().steps().stream()
                            .filter(GpuEfficiencyStore.StepResult::stable).allMatch(step -> workerPolicies.temperatureSafe(
                                    entry.getValue().deviceId(), step.maxTemperatureC(), maxTemperatureC)))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            runs = applyCheckpoint(runs, previous.runs(), checkpoint);
            Set<String> compatible = runs.stream().filter(RunStatus::terminalComplete)
                    .map(RunStatus::id).collect(java.util.stream.Collectors.toSet());
            Map<String, GpuEfficiencyStore.Profile> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, GpuEfficiencyStore.Profile> entry : checkpoint.entrySet())
                if (compatible.contains(entry.getKey())) filtered.put(entry.getKey(), entry.getValue());
            checkpoint = Map.copyOf(filtered);
        }
        List<GpuEfficiencyStore.Profile> reused = List.copyOf(checkpoint.values());
        int completed = (int) runs.stream().filter(RunStatus::terminalComplete).count();
        session = new Session(true, resume ? "Fortsetzen wird vorbereitet" : "Vorbereitung", startedAt,
                completed, targets.size(), reused, runs,
                estimateRemainingSeconds(runs, startedAt));
        try {
            Map<String, GpuEfficiencyStore.Profile> resumeCheckpoint = checkpoint;
            guard.submit(() -> run(targets, referenceBatches, cohorts, resumeCheckpoint, gpuLimit));
        } catch (RuntimeException e) {
            session = Session.idle();
            guard.end();
            throw new IllegalStateException("Effizienz-Sweep konnte nicht gestartet werden", e);
        }
        return session;
    }

    public Session status() {
        return session;
    }

    public synchronized Session cancel() {
        guard.requestCancel();
        return status();
    }

    public List<GpuEfficiencyStore.Profile> profiles() {
        return store.all();
    }

    public boolean deleteProfile(GpuEfficiencyStore.Profile profile) {
        return store.delete(profile);
    }

    public GpuEfficiencyStore.StorageStatus profileStorageStatus() { return store.storageStatus(); }

    /**
     * Coins with a saved configuration and a usable SRBMiner, restricted to driver-verified GPUs.
     * A coin the operator never configured is still measured against the fee-backend house
     * (dev-fee) payout through an ephemeral in-memory override, exactly like an empty wallet in
     * the operator UI. The override never touches disk and is cleared when the sweep ends.
     */
    private List<Target> targets(List<LocalGpuPowerService.Gpu> discovered, Set<String> selectedCoinIds, Set<String> selectedWorkers) {
        List<Target> targets = new ArrayList<>();
        Map<String, LocalGpuPowerService.Gpu> verified = new LinkedHashMap<>();
        discovered.stream().filter(gpu -> gpu.supportsDynamicPowerScaling()
                        && (selectedWorkers.isEmpty() || selectedWorkers.contains(gpu.deviceId())))
                .forEach(gpu -> verified.put(gpu.deviceId(), gpu));
        if (verified.isEmpty()) return List.of();
        for (Coin coin : Coin.gpuCoins()) {
            if (!selectedCoinIds.isEmpty() && !selectedCoinIds.contains(coin.id())) continue;
            // A sweep compares every installed adapter on each compatible physical GPU.
            // The worker's saved assignment is restored after the whole session.
            List<MinerFactory.GpuMinerAdapter> adapters = measurementAdapters(coin, verified.values());
            for (MinerFactory.GpuMinerAdapter adapter : adapters) {
                String softwareId = adapter.softwareId();
                GpuCoinMiner miner = adapter.miner();
                if (!miner.binaryAvailable()) continue;
                List<LocalGpuPowerService.Gpu> eligible = verified.values().stream()
                        .filter(gpu -> MinerCatalogService.supportedGpuVendors(softwareId, coin.id())
                                .contains(gpu.vendor()))
                        .filter(gpu -> MinerCatalogService.supportsGpuModel(softwareId, gpu.model()))
                        .filter(gpu -> workerPolicies == null || workerPolicies.measures(gpu.deviceId(), coin.id(), softwareId)).toList();
                if (eligible.isEmpty()) continue;
                MinerConfig configured = miner.configuration();
                if (configured != null) {
                    List<LocalGpuPowerService.Gpu> configuredGpus = miner.selectedGpus();
                    List<LocalGpuPowerService.Gpu> measured = eligible;
                    if (measured.isEmpty()) continue;
                    MinerConfig override = sweepDeviceOverride(miner, configured, configuredGpus, measured);
                    for (LocalGpuPowerService.Gpu gpu : measured)
                        targets.add(new Target(coin.id(), miner.reportedAlgorithm(), gpu, override, softwareId));
                    continue;
                }
                String devices = String.join(",", eligible.stream()
                        .map(gpu -> gpu.vendor() + ":" + gpu.index()).toList());
                MinerConfig fallback = feeFallback(coin, miner, devices);
                if (fallback == null) continue;
                for (LocalGpuPowerService.Gpu gpu : eligible)
                    targets.add(new Target(coin.id(), miner.reportedAlgorithm(), gpu, fallback, softwareId));
            }
        }
        return List.copyOf(targets);
    }

    /** A selected measurement GPU must not rewrite or depend on the operator's saved device list. */
    private static MinerConfig sweepDeviceOverride(GpuCoinMiner miner, MinerConfig configured,
                                                   List<LocalGpuPowerService.Gpu> configuredGpus,
                                                   List<LocalGpuPowerService.Gpu> measured) {
        if (configuredGpus.stream().map(LocalGpuPowerService.Gpu::deviceId).collect(java.util.stream.Collectors.toSet())
                .containsAll(measured.stream().map(LocalGpuPowerService.Gpu::deviceId).toList())) return null;
        java.util.LinkedHashSet<String> devices = new java.util.LinkedHashSet<>(List.of(configured.devices().split(",")));
        measured.forEach(gpu -> devices.add(gpu.vendor() + ":" + gpu.index()));
        MinerConfig override = new MinerConfig(configured.poolUrl(), configured.proxyUrl(), configured.wallet(),
                configured.worker(), String.join(",", devices));
        miner.validateConfig(override);
        return override;
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

    static String selectedTargetUnavailableReason(List<LocalGpuPowerService.Gpu> discovered,
                                                  Set<String> selectedWorkers, boolean minerAvailable) {
        if (discovered.stream().filter(gpu -> selectedWorkers.contains(gpu.deviceId()))
                .noneMatch(LocalGpuPowerService.Gpu::supportsDynamicPowerScaling))
            return "benchmark_no_writable_selected_gpu";
        if (!minerAvailable) return "benchmark_selected_miner_missing_for_sweep";
        return "benchmark_no_selected_targets";
    }

    private boolean selectedMinerAvailable(List<LocalGpuPowerService.Gpu> discovered, Set<String> coins,
                                           Set<String> workers) {
        for (Coin coin : Coin.gpuCoins()) {
            if (!coins.isEmpty() && !coins.contains(coin.id())) continue;
            for (LocalGpuPowerService.Gpu gpu : discovered) {
                if (!workers.contains(gpu.deviceId()) || !gpu.supportsDynamicPowerScaling()) continue;
                for (MinerFactory.GpuMinerAdapter adapter : measurementAdapters(coin, List.of(gpu)))
                    if (MinerCatalogService.supportedGpuVendors(adapter.softwareId(), coin.id()).contains(gpu.vendor())
                            && MinerCatalogService.supportsGpuModel(adapter.softwareId(), gpu.model())
                            && adapter.miner().binaryAvailable()) return true;
            }
        }
        return false;
    }

    private List<MinerFactory.GpuMinerAdapter> measurementAdapters(Coin coin,
                                                                   java.util.Collection<LocalGpuPowerService.Gpu> cards) {
        List<MinerFactory.GpuMinerAdapter> registered = miners.gpuMinerAdapters(coin);
        if (registered != null && !registered.isEmpty()) return registered;
        // Compatibility with older factory implementations and focused tests.
        return cards.stream().map(gpu -> miners.minerSoftwareForMeasurement(coin, gpu.deviceId()))
                .map(id -> id == null ? "srbminer-multi" : id).distinct()
                .map(id -> new MinerFactory.GpuMinerAdapter(id, miners.gpuMiner(coin, id))).toList();
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) return "Power-Cap nicht verfügbar";
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    /**
     * Ephemeral config for one GPU coin against the fee-backend house target, or null when
     * the payout is unreachable or the coin's own adapter rejects the candidate config.
     */
    private MinerConfig feeFallback(Coin coin, GpuCoinMiner miner, String devices) {
        PayoutDefaultsService.DefaultPayout payout = payoutDefaults.resolve(coin.id()).orElse(null);
        if (payout == null || proxyConfiguration.host() == null) return null;
        String worker = payout.workerPart() != null && !payout.workerPart().isBlank() ? payout.workerPart() : "solarminer";
        String proxyUrl = proxyConfiguration.coinUrl(coin);
        if (proxyUrl == null) return null;
        MinerConfig fallback = new MinerConfig(payout.poolUrl(), proxyUrl, payout.walletPart(), worker, devices);
        try {
            miner.validateConfig(fallback);
            return fallback;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void run(List<Target> targets, List<List<List<Target>>> referenceBatches,
                     List<List<Target>> cohorts, Map<String, GpuEfficiencyStore.Profile> checkpoint, int gpuLimit) {
        try {
            execute(targets, referenceBatches, cohorts, checkpoint, gpuLimit);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Efficiency sweep aborted", e);
            Session old = session;
            String detail = Objects.toString(e.getMessage(), "Sweep fehlgeschlagen");
            session = new Session(false, "benchmark_sweep_stop_selected_failed".equals(detail) ? detail : "Fehler: " + detail,
                    old.startedAt(), old.phaseIndex(), old.phaseCount(), old.results(), old.runs(), 0L);
        } finally {
            guard.end();
        }
    }

    private void execute(List<Target> targets, List<List<List<Target>>> referenceBatches,
                         List<List<Target>> cohorts, Map<String, GpuEfficiencyStore.Profile> checkpoint, int gpuLimit) {
        // Safety snapshot: everything the sweep may touch is captured before the first write.
        // Phase 3: the Pearl adapter is captured/restored through the same generic
        // captureState/restoreState seam as every other GPU adapter.
        GpuCoinMiner.WorkerPauseSnapshot pearlSnapshot = miners.gpuMiner(Coin.PEARL).captureState();
        Map<GpuCoinMiner, Set<String>> coinWasMining = new LinkedHashMap<>();
        Map<GpuCoinMiner, Set<String>> coinWasPaused = new LinkedHashMap<>();
        Map<String, Integer> limitsBefore = new LinkedHashMap<>();
        for (Coin coin : Coin.gpuCoins()) {
            for (GpuCoinMiner miner : adapterMiners(coin)) {
                if (miner.configuration() == null) continue;
                for (GpuState state : miner.gpuStates(power.discover())) {
                    String key = state.vendor() + ":" + state.index();
                    if (state.running()) coinWasMining.computeIfAbsent(miner, ignored -> new java.util.HashSet<>()).add(key);
                    if (state.manuallyPaused()) coinWasPaused.computeIfAbsent(miner, ignored -> new java.util.HashSet<>()).add(key);
                }
            }
        }
        Set<String> targetDeviceIds = targets.stream().map(target -> target.gpu().deviceId()).collect(java.util.stream.Collectors.toSet());
        for (LocalGpuPowerService.Gpu gpu : power.refresh())
            if (targetDeviceIds.contains(gpu.deviceId()) && gpu.currentPowerLimitWatts() != null)
                limitsBefore.put(gpu.deviceId(), gpu.currentPowerLimitWatts());
        if (limitsBefore.size() != targetDeviceIds.size())
            throw new IllegalStateException("Nicht alle GPU-Power-Caps konnten vor dem Test sicher gelesen werden");

        List<GpuEfficiencyStore.Profile> produced = new ArrayList<>(checkpoint.values());
        String error = null;
        try {
            // A queued selected card may already be mining at its old cap. Stop all selected
            // cards before admitting the first task, otherwise the concurrency limit would
            // count only sweep tasks while those miners continue heating the rig.
            Set<String> stoppedDevices = new HashSet<>();
            for (Target target : targets)
                if (stoppedDevices.add(target.gpu().deviceId()) && !stopAllOn(target.gpu()))
                    throw new IllegalStateException("benchmark_sweep_stop_selected_failed");
            // Overrides are session-scoped. Keeping them until restoration avoids one parallel
            // run clearing a shared coin override while a sibling still needs to restart.
            for (Target target : targets) applyOverride(target);
            executeTasks(referenceBatches, cohorts, produced, checkpoint, gpuLimit);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            error = Objects.toString(e.getMessage(), "Sweep failed");
        } finally {
            // Safety net: every ephemeral fee-backend override is removed even when a
            // target aborted early, so no dev-fee route can outlive the sweep.
            for (Target target : targets) clearOverride(target);
            boolean minersStopped = true;
            for (Target target : targets) minersStopped = stopMiner(target) && minersStopped;
            if (!minersStopped) error = appendError(error, "Konnte nicht alle Sweep-Miner anhalten");
            power.clearSweepPowerTargets(targetDeviceIds);
            if (!power.restorePowerLimits(limitsBefore))
                error = appendError(error, "Konnte Leistungsgrenzen nicht vollständig wiederherstellen");
            try {
                // Safety stops survive restoration and automatic Node/worker restarts.
                if (controls != null && !thermalBlocked.isEmpty()) controls.setWorkersManuallyStopped(thermalBlocked, true);
                boolean restored = miners.gpuMiner(Coin.PEARL).restoreState(pearlSnapshot);
                for (Map.Entry<GpuCoinMiner, Set<String>> entry : coinWasMining.entrySet())
                    for (String key : entry.getValue()) {
                        String[] parts = key.split(":");
                        restored = entry.getKey().resumeGpu(parts[0], Integer.parseInt(parts[1])) && restored;
                    }
                for (Map.Entry<GpuCoinMiner, Set<String>> entry : coinWasPaused.entrySet())
                    for (String key : entry.getValue()) {
                        String[] parts = key.split(":");
                        restored = entry.getKey().pauseGpu(parts[0], Integer.parseInt(parts[1])) && restored;
                    }
                if (!restored) error = appendError(error, "Konnte Miner-Zustand nicht vollständig wiederherstellen");
            } catch (RuntimeException e) {
                error = appendError(error, "Konnte Miner-Zustand nicht vollständig wiederherstellen: " + e.getMessage());
            }
            sharing.reportEfficiencyResults(List.copyOf(produced));
            Session old = session;
            String result = error != null ? ("benchmark_sweep_stop_selected_failed".equals(error) ? error : "Fehler: " + error)
                    : guard.cancelRequested() ? "Abgebrochen; Leistungsgrenzen und Miner-Zustand wiederhergestellt"
                    : "Fertig; Leistungsgrenzen und Miner-Zustand wiederhergestellt";
            List<RunStatus> finalRuns = finalizePendingRuns(old.runs(),
                    error != null ? SweepRunState.FAILED : SweepRunState.CANCELLED,
                    error != null ? error : "Abgebrochen");
            int finalCompleted = (int) finalRuns.stream().filter(RunStatus::terminal).count();
            session = new Session(false, result, old.startedAt(), finalCompleted, targets.size(), List.copyOf(produced),
                    finalRuns, 0L);
        }
    }

    /**
     * Runs a dependency-aware queue. References of other coins fill GPUs whose same-coin
     * validation is still blocked; as soon as a reference completes, its sibling validations
     * become the preferred work for every matching device that becomes free.
     */
    private void executeTasks(List<List<List<Target>>> referenceBatches, List<List<Target>> cohorts,
                              List<GpuEfficiencyStore.Profile> produced,
                              Map<String, GpuEfficiencyStore.Profile> checkpoint, int gpuLimit) throws Exception {
        Map<String, List<Target>> siblingsByCohort = new LinkedHashMap<>();
        for (List<Target> cohort : cohorts)
            siblingsByCohort.put(cohortKey(cohort.getFirst()), List.copyOf(cohort.subList(1, cohort.size())));
        List<SweepTask> pendingReferences = new ArrayList<>();
        List<SweepTask> readyValidations = new ArrayList<>();
        for (List<Target> group : referenceBatches.stream().flatMap(List::stream).toList()) {
            Target reference = group.getFirst();
            String cohort = cohortKey(reference);
            GpuEfficiencyStore.Profile referenceProfile = checkpoint.get(runId(reference));
            if (referenceProfile == null) {
                pendingReferences.add(new SweepTask(reference, cohort, SweepMode.FULL, null));
                continue;
            }
            unlockValidations(siblingsByCohort.getOrDefault(cohort, List.of()), cohort,
                    referenceProfile, checkpoint, readyValidations);
        }
        Set<String> busyDevices = new HashSet<>();
        int active = 0;
        try (ExecutorService parallel = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionService<SweepTaskResult> completed = new ExecutorCompletionService<>(parallel);
            while (active > 0 || (!guard.cancelRequested() && (!pendingReferences.isEmpty() || !readyValidations.isEmpty()))) {
                if (!guard.cancelRequested()) {
                    SweepTask task;
                    while (active < gpuLimit && (task = takeRunnable(readyValidations, pendingReferences, busyDevices)) != null) {
                        SweepTask selected = task;
                        busyDevices.add(selected.target().gpu().deviceId());
                        LOGGER.info(() -> "Efficiency sweep task starting: " + selected.target().coin()
                                + " on GPU " + selected.target().gpu().deviceId());
                        completed.submit(() -> executeSweepTask(selected));
                        active++;
                    }
                }
                if (active == 0) break;
                SweepTaskResult result;
                try {
                    result = completed.take().get();
                } catch (Exception e) {
                    guard.requestCancel();
                    throw e;
                }
                active--;
                busyDevices.remove(result.task().target().gpu().deviceId());
                LOGGER.info(() -> "Efficiency sweep task finished: " + result.task().target().coin()
                        + " on GPU " + result.task().target().gpu().deviceId() + " ("
                        + session.runs().stream().filter(run -> run.id().equals(runId(result.task().target())))
                        .map(run -> run.status() + ": " + run.detail()).findFirst().orElse("status unavailable") + ")");
                if (result.profile() != null) produced.add(result.profile());
                if (result.task().mode() != SweepMode.FULL) continue;
                List<Target> siblings = siblingsByCohort.getOrDefault(result.task().cohort(), List.of());
                if (guard.cancelRequested()) {
                    for (Target sibling : siblings) finishRun(sibling, SweepRunState.CANCELLED, "Abgebrochen");
                } else if (result.profile() == null || result.profile().bestStableWatts() == null) {
                    for (Target sibling : siblings)
                        finishRun(sibling, SweepRunState.SKIPPED, "Referenzkarte lieferte keinen stabilen Ausgangswert");
                } else {
                    unlockValidations(siblings, result.task().cohort(), result.profile(), Map.of(), readyValidations);
                }
            }
        }
    }

    private SweepTaskResult executeSweepTask(SweepTask task) {
        try {
            return new SweepTaskResult(task, sweepTarget(task.target(), task.limits(), task.mode()));
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Efficiency sweep task failed for " + task.target().coin()
                    + " on GPU " + task.target().gpu().deviceId(), failure);
            // A failed card must not discard unrelated queued coin/device runs. Continue only
            // after every managed miner on this physical GPU has been stopped successfully.
            if (!stopAllOn(task.target().gpu())) throw failure;
            String detail = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
            finishRun(task.target(), SweepRunState.FAILED, detail);
            sweepLog(task.target(), task.target().gpu().model() + " · " + task.target().algorithm()
                    + ": " + detail);
            return new SweepTaskResult(task, null);
        }
    }

    private void unlockValidations(List<Target> siblings, String cohort,
                                   GpuEfficiencyStore.Profile referenceProfile,
                                   Map<String, GpuEfficiencyStore.Profile> checkpoint,
                                   List<SweepTask> readyValidations) {
        for (Target sibling : siblings) {
            if (checkpoint.containsKey(runId(sibling))) continue;
            List<Integer> candidates = validationLimits(referenceProfile.bestStableWatts(),
                    sibling.gpu().minWatts(), activeLimit(sibling.gpu()), stepWatts);
            setPlannedLimits(sibling, candidates, SweepMode.VALIDATION, "Referenz fertig; wartet auf freie GPU");
            readyValidations.add(new SweepTask(sibling, cohort, SweepMode.VALIDATION, candidates));
        }
    }

    /** Prefer unlocked same-cohort validation work, then use an idle card for another coin. */
    static SweepTask takeRunnable(List<SweepTask> readyValidations, List<SweepTask> pendingReferences,
                                  Set<String> busyDevices) {
        SweepTask task = removeFirstRunnable(readyValidations, busyDevices);
        return task != null ? task : removeFirstRunnable(pendingReferences, busyDevices);
    }

    private static SweepTask removeFirstRunnable(List<SweepTask> tasks, Set<String> busyDevices) {
        for (Iterator<SweepTask> iterator = tasks.iterator(); iterator.hasNext(); ) {
            SweepTask candidate = iterator.next();
            if (busyDevices.contains(candidate.target().gpu().deviceId())) continue;
            iterator.remove();
            return candidate;
        }
        return null;
    }

    private GpuEfficiencyStore.Profile sweepTarget(Target target, List<Integer> requestedLimits,
                                                   SweepMode mode) {
        if (thermalBlocked.contains(target.gpu().deviceId())) {
            finishRun(target, SweepRunState.SKIPPED, "benchmark_temperature_limit"); return null;
        }
        String label = target.gpu().model() + " · " + target.algorithm();
        // Only one miner per physical GPU: stop every other coin on this card first.
        if (!stopAllOn(target.gpu())) throw new IllegalStateException("Andere Miner auf " + target.gpu().model() + " konnten nicht angehalten werden");
        LocalGpuPowerService.Gpu fresh = power.refresh().stream()
                .filter(gpu -> gpu.deviceId().equals(target.gpu().deviceId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("GPU wurde während des Tests getrennt"));
        int current;
        if (requestedLimits != null) {
            current = activeLimit(fresh);
        } else {
            current = referenceStartLimit(fresh);
        }
        List<Integer> limits = requestedLimits == null
                ? candidateLimits(fresh.minWatts(), current, stepWatts) : requestedLimits;
        setPlannedLimits(target, limits, mode, mode == SweepMode.FULL ? "Vollständige GPU-Kurve" : "Parallele Gerätevalidierung");
        List<GpuEfficiencyStore.StepResult> steps = new ArrayList<>();
        GpuEfficiencyStore.StepResult best = null;
        boolean completedCurve = true;
        for (int limit : limits) {
            if (guard.cancelRequested()) {
                completedCurve = false;
                break;
            }
            updateRun(target, SweepRunState.RUNNING, limit, steps, 0, label + " · " + limit + " W");
            if (!power.setSweepPowerTarget(limit, target.gpu())) {
                completedCurve = false;
                steps.add(new GpuEfficiencyStore.StepResult(limit, null, null, null, false,
                        "Leistungsgrenze wurde nicht vom Treiber bestätigt; Schritt übersprungen"));
                updateRun(target, SweepRunState.RUNNING, limit, steps, SAMPLES_PER_STEP,
                        "Treiber hat die Leistungsgrenze nicht bestätigt");
                break;
            }
            GpuEfficiencyStore.StepResult step = measureStep(target, limit, steps);
            steps.add(step);
            updateRun(target, SweepRunState.RUNNING, limit, steps, SAMPLES_PER_STEP,
                    step.stable() ? limit + " W stabil" : limit + " W instabil: " + step.note());
            if (!stopMiner(target)) throw new IllegalStateException("Sweep-Miner konnte nicht angehalten werden");
            sweepLog(target, label + " @ " + limit + " W: " + (step.stable()
                    ? "stabil, " + formatRate(step.medianHashrateHs()) + " · " + Math.round(step.avgPowerWatts()) + " W"
                    : "instabil (" + step.note() + ")"));
            if (!step.stable()) {
                // Validation walks upward from the reference value; an unstable result
                // there must not unlock a still higher power target on the same GPU.
                if (mode != SweepMode.FULL || limit == limits.getLast()
                        || !mayContinueAfterUnstableStep(target, step)) {
                    completedCurve = limit == limits.getLast();
                    break;
                }
            }
            if (step.stable() && (best == null || step.medianHashrateHs() / step.avgPowerWatts() > best.medianHashrateHs() / best.avgPowerWatts()))
                best = step;
            if (step.stable() && mode == SweepMode.VALIDATION) break;
        }
        GpuEfficiencyStore.Profile profile = null;
        if (best != null) {
            profile = new GpuEfficiencyStore.Profile(
                    target.gpu().deviceId(), target.gpu().model(), target.coin(), target.algorithm(),
                    best.limitWatts(), best.medianHashrateHs(), best.avgPowerWatts(),
                    best.medianHashrateHs() / best.avgPowerWatts(), Instant.now(), List.copyOf(steps),
                    target.softwareId() == null
                            ? miners.minerSoftwareForWorker(Coin.byIdOrNull(target.coin()), target.gpu().deviceId())
                            : target.softwareId());
            sweepLog(target, label + (guard.cancelRequested() ? ": bisher bester Messwert " : ": bester stabiler Wert ")
                    + best.limitWatts() + " W ("
                    + formatRate(best.medianHashrateHs()) + ", " + Math.round(best.medianHashrateHs() / best.avgPowerWatts()) + " H/J)");
            finishRun(target, guard.cancelRequested() ? SweepRunState.CANCELLED : SweepRunState.COMPLETE, guard.cancelRequested()
                    ? "Abgebrochen; bisher bester Messwert: " + best.limitWatts() + " W"
                    : "Bester stabiler Wert: " + best.limitWatts() + " W");
        } else {
            sweepLog(target, label + ": kein stabiler Undervolt-Wert gefunden");
            if (!steps.isEmpty())
                profile = new GpuEfficiencyStore.Profile(target.gpu().deviceId(), target.gpu().model(),
                        target.coin(), target.algorithm(), null, null, null, null, Instant.now(), List.copyOf(steps),
                        target.softwareId() == null
                                ? miners.minerSoftwareForWorker(Coin.byIdOrNull(target.coin()), target.gpu().deviceId())
                                : target.softwareId());
            finishRun(target, guard.cancelRequested() ? SweepRunState.CANCELLED : SweepRunState.FAILED, guard.cancelRequested() ? "Abgebrochen" : "Kein stabiler Wert gefunden");
        }
        // Only complete per-device curves become durable profiles. Partial and cancelled
        // runs remain session history and never replace a complete measured curve.
        if (profile != null && best != null && mode == SweepMode.FULL && completedCurve && !guard.cancelRequested())
            store.save(profile);
        return profile;
    }

    /** Continue a descending reference curve after recoverable instability, with thermal guards. */
    private boolean mayContinueAfterUnstableStep(Target target, GpuEfficiencyStore.StepResult step) {
        if (guard.cancelRequested()) return false;
        if (step.note() != null && step.note().startsWith("GPU-Temperatur nicht lesbar")) return false;
        if (step.maxTemperatureC() != null && step.maxTemperatureC() >= temperatureCeiling(target))
            return waitForTemperatureCooldown(target);
        return waitBeforeLowerPowerRetry();
    }

    /** Do not run again until the stopped GPU is at least 3 °C below the trip threshold. */
    private boolean waitForTemperatureCooldown(Target target) {
        Instant deadline = Instant.now().plusMillis(TEMPERATURE_COOLDOWN_TIMEOUT_MILLIS);
        double restartBelow = temperatureCeiling(target) - TEMPERATURE_COOLDOWN_MARGIN_C;
        while (!guard.cancelRequested() && Instant.now().isBefore(deadline)) {
            Double temperature = power.readTemperatureC(target.gpu());
            if (temperature == null || !Double.isFinite(temperature) || temperature <= 0) return false;
            if (temperature <= restartBelow) { thermalBlocked.remove(target.gpu().deviceId()); return true; }
            if (!pauseForCooldown(TEMPERATURE_COOLDOWN_POLL_MILLIS)) return false;
        }
        return false;
    }

    private boolean waitBeforeLowerPowerRetry() {
        return !guard.cancelRequested() && pauseForCooldown(UNSTABLE_RETRY_PAUSE_MILLIS)
                && !guard.cancelRequested();
    }

    private boolean pauseForCooldown(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void applyOverride(Target target) {
        if (target.override() == null) return;
        targetMiner(target).setSweepOverride(target.override());
    }

    private void clearOverride(Target target) {
        if (target.override() == null) return;
        targetMiner(target).clearSweepOverride();
    }

    private GpuCoinMiner targetMiner(Target target) {
        Coin coin = Coin.byIdOrNull(target.coin());
        return target.softwareId() == null ? miners.gpuMiner(coin) : miners.gpuMiner(coin, target.softwareId());
    }

    /** Descending candidates never exceed the limit that was active when the sweep began. */
    static List<Integer> candidateLimits(int minWatts, int currentWatts, int stepWatts) {
        List<Integer> limits = new ArrayList<>();
        for (int limit = Math.max(minWatts, currentWatts); limit > minWatts; limit -= stepWatts) limits.add(limit);
        limits.add(Math.max(minWatts, 0));
        return List.copyOf(limits);
    }

    static List<Integer> validationLimits(int referenceWatts, int minWatts, int currentWatts, int stepWatts) {
        int start = Math.max(minWatts, Math.min(referenceWatts, currentWatts));
        List<Integer> limits = new ArrayList<>();
        for (int limit = start; limit < currentWatts; limit += stepWatts) limits.add(limit);
        if (limits.isEmpty() || limits.getLast() != currentWatts) limits.add(currentWatts);
        return List.copyOf(limits);
    }

    private static int activeLimit(LocalGpuPowerService.Gpu gpu) {
        return gpu.currentPowerLimitWatts() == null ? gpu.minWatts()
                : Math.min(gpu.maxWatts(), gpu.currentPowerLimitWatts());
    }

    /** Use the driver default when available, always respecting the operator's cap. */
    static int referenceStartLimit(LocalGpuPowerService.Gpu gpu) {
        Integer driverDefault = gpu.driverDefaultWatts();
        return driverDefault == null ? activeLimit(gpu)
                : Math.max(gpu.minWatts(), Math.min(gpu.maxWatts(), driverDefault));
    }

    /**
     * The SRBMiner adapters keep a rule-mapped diagnosis per GPU run (kernel/driver/pool
     * failures read from the miner's own log). The sweep surfaces it as the step's failure
     * note instead of a bare "no hashrate"; future miners attach to the same
     * {@link de.verdox.solarminer.pcagent.miner.diagnostics.MinerLogDiagnostics} seam through
     * {@link de.verdox.solarminer.pcagent.miner.GpuCoinMiner#diagnosis(String, int)}.
     */
    private String diagnosisFor(Target target) {
        Coin coin = Coin.byIdOrNull(target.coin());
        if (coin == null) return null;
        var diagnosis = targetMiner(target).diagnosis(target.gpu().vendor(), target.gpu().index());
        return diagnosis == null ? null : diagnosis.detail();
    }

    private double temperatureCeiling(Target target) {
        return workerPolicies == null ? maxTemperatureC : workerPolicies.ceiling(target.gpu().deviceId(), maxTemperatureC);
    }

    private GpuEfficiencyStore.StepResult measureStep(Target target, int limit,
                                                       List<GpuEfficiencyStore.StepResult> completedSteps) {
        if (!startMiner(target)) {
            String reason = targetMiner(target).lastError();
            if (reason == null || reason.isBlank()) reason = diagnosisFor(target);
            if (reason == null || reason.isBlank()) reason = "Miner konnte nicht gestartet werden";
            return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, reason);
        }
        List<Double> rates = new ArrayList<>();
        List<Double> watts = new ArrayList<>();
        double maxTemp = Double.NEGATIVE_INFINITY;
        Instant startupDeadline = Instant.now().plus(startupGrace);
        MinerStats.Worker worker = null;
        while (rates.size() < SAMPLES_PER_STEP) {
            if (guard.cancelRequested()) return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Abgebrochen");
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Unterbrochen");
            }
            worker = findWorker(target);
            if (worker != null && worker.miningStatus() == MinerStats.MinerStatus.ERROR) {
                String diagnosis = diagnosisFor(target);
                return new GpuEfficiencyStore.StepResult(limit, null, null, null, false,
                        diagnosis != null ? diagnosis : "Miner-Prozess ausgefallen");
            }
            Double temperature = power.readTemperatureC(target.gpu());
            if (temperature == null || !Double.isFinite(temperature) || temperature <= 0) {
                thermalBlocked.add(target.gpu().deviceId());
                return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "GPU-Temperatur nicht lesbar; Sicherheitshalber abgebrochen");
            }
            maxTemp = Math.max(maxTemp, temperature);
            if (maxTemp >= temperatureCeiling(target)) {
                thermalBlocked.add(target.gpu().deviceId());
                return new GpuEfficiencyStore.StepResult(limit, null, null, maxTemp, false,
                        "GPU wurde " + Math.round(maxTemp) + " °C heiß; Grenze über dem Schwellwert von " + Math.round(temperatureCeiling(target)) + " °C");
            }
            if (worker != null && worker.miningStatus() == MinerStats.MinerStatus.MINING
                    && Double.isFinite(worker.terahashPerSecond()) && worker.terahashPerSecond() > 0
                    && Double.isFinite(worker.approximatedPowerUsageWatts()) && worker.approximatedPowerUsageWatts() > 0) {
                rates.add(worker.terahashPerSecond() * 1_000_000_000_000d);
                watts.add((double) worker.approximatedPowerUsageWatts());
            } else if (!Instant.now().isBefore(startupDeadline)) {
                return new GpuEfficiencyStore.StepResult(limit, null, null, maxTemp == Double.NEGATIVE_INFINITY ? null : maxTemp,
                        false, worker == null
                                ? "Miner-Worker wird noch nicht gemeldet; keine Hashrate innerhalb der Startwartezeit"
                                : "Miner liefert trotz Pool-Jobs keine Hashrate");
            }
            updateRun(target, SweepRunState.RUNNING, limit, completedSteps, rates.size(),
                    target.gpu().model() + " · " + target.algorithm() + " · " + limit + " W");
        }
        double median = SampleCollector.median(rates);
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
        Coin coin = Coin.byIdOrNull(target.coin());
        List<MinerStats.Worker> workers = targetMiner(target).workerStats(List.of(fresh));
        return workers.stream()
                // target.algorithm is the adapter's display label (e.g. "QPoW (Poseidon2)"),
                // while worker telemetry uses Coin.algorithm() ("quantus"). Match the
                // worker's canonical coin identity instead of comparing those two formats.
                .filter(w -> matchesTargetWorker(target, coin, w.deviceId(), w.currentAlgorithm()))
                .findFirst().orElse(null);
    }

    static boolean matchesTargetWorker(Target target, Coin coin, String deviceId, String algorithm) {
        return coin != null && Objects.equals(deviceId, target.gpu().deviceId())
                && coin.matchesAlgorithm(algorithm);
    }

    boolean startMiner(Target target) {
        GpuCoinMiner miner = targetMiner(target);
        if (miner.resumeGpu(target.gpu().vendor(), target.gpu().index())) return true;
        // A previous managed miner can finish its exit callback just after the handoff.
        // Reap managed workers on this card once more; never terminate external processes.
        String reason = miner.lastError();
        if (reason != null && reason.startsWith("Another miner already controls ")
                && stopAllOn(target.gpu()))
            return miner.resumeGpu(target.gpu().vendor(), target.gpu().index());
        return false;
    }

    private boolean stopMiner(Target target) {
        return MinerStopContext.with("Efficiency sweep",
                () -> targetMiner(target).stopGpu(target.gpu().vendor(), target.gpu().index()));
    }

    private boolean stopAllOn(LocalGpuPowerService.Gpu gpu) {
        return MinerStopContext.with("Efficiency sweep", () -> {
            boolean success = true;
            for (Coin coin : Coin.gpuCoins())
                for (GpuCoinMiner miner : adapterMiners(coin))
                    success = miner.stopGpu(gpu.vendor(), gpu.index()) && success;
            return success;
        });
    }

    private List<GpuCoinMiner> adapterMiners(Coin coin) {
        List<MinerFactory.GpuMinerAdapter> adapters = miners.gpuMinerAdapters(coin);
        return adapters.isEmpty() ? List.of(miners.gpuMiner(coin))
                : adapters.stream().map(MinerFactory.GpuMinerAdapter::miner).toList();
    }

    private void sweepLog(Target target, String message) {
        String entry = "[Efficiency-Sweep] " + message;
        consoles.append(Coin.PEARL.id(), entry);
        consoles.append(target.coin(), entry);
    }

    static String cohortKey(Target target) {
        LocalGpuPowerService.Gpu gpu = target.gpu();
        return String.join("|", target.coin(), target.algorithm(),
                target.softwareId() == null ? "" : target.softwareId(), gpu.vendor(), gpu.model(),
                Integer.toString(gpu.driverMinWatts()), Integer.toString(gpu.driverMaxWatts()),
                Integer.toString(gpu.minWatts()), Integer.toString(gpu.maxWatts()));
    }

    static List<List<Target>> targetGroups(List<Target> targets) {
        // Power efficiency differs between physical cards, including cards of the same model.
        // A singleton group gives every device/coin pair its own complete power curve.
        return targets.stream().map(List::of).toList();
    }

    /**
     * Spreads complete device/coin curves across physical GPUs. The task queue enforces
     * one active curve per device while allowing distinct GPUs to run together.
     */
    static List<List<List<Target>>> referenceBatches(List<List<Target>> cohorts) {
        List<List<Target>> pending = new ArrayList<>(cohorts);
        List<List<List<Target>>> batches = new ArrayList<>();
        while (!pending.isEmpty()) {
            Set<String> usedDevices = new HashSet<>();
            List<List<Target>> batch = new ArrayList<>();
            for (Iterator<List<Target>> iterator = pending.iterator(); iterator.hasNext(); ) {
                List<Target> cohort = iterator.next();
                Target reference = cohort.stream()
                        .filter(candidate -> !usedDevices.contains(candidate.gpu().deviceId()))
                        .findFirst().orElse(null);
                if (reference == null) continue;
                List<Target> reordered = new ArrayList<>(cohort.size());
                reordered.add(reference);
                cohort.stream().filter(candidate -> candidate != reference).forEach(reordered::add);
                batch.add(List.copyOf(reordered));
                usedDevices.add(reference.gpu().deviceId());
                iterator.remove();
            }
            if (batch.isEmpty()) throw new IllegalStateException("Keine konfliktfreie GPU-Referenzplanung möglich");
            batches.add(List.copyOf(batch));
        }
        return List.copyOf(batches);
    }

    private List<RunStatus> initialRuns(List<List<List<Target>>> referenceBatches) {
        List<RunStatus> runs = new ArrayList<>();
        for (int batchIndex = 0; batchIndex < referenceBatches.size(); batchIndex++) {
            for (List<Target> group : referenceBatches.get(batchIndex)) {
                for (int index = 0; index < group.size(); index++) {
                    Target target = group.get(index);
                    boolean reference = index == 0;
                    List<Integer> limits = reference
                            ? candidateLimits(target.gpu().minWatts(), referenceStartLimit(target.gpu()), stepWatts) : List.of();
                    runs.add(new RunStatus(runId(target), target.gpu().deviceId(), target.gpu().model(), target.coin(),
                            target.algorithm(), reference ? SweepMode.FULL : SweepMode.VALIDATION, SweepRunState.QUEUED, null, limits,
                            List.of(), 0, SAMPLES_PER_STEP, reference ? batchIndex : null,
                            reference ? "Vollständige GPU-Kurve" : "Wartet auf Ergebnis der Referenzkarte",
                            target.softwareId()));
                }
            }
        }
        return List.copyOf(runs);
    }

    static Map<String, GpuEfficiencyStore.Profile> reusableProfiles(Session previous) {
        Set<String> complete = previous.runs().stream().filter(RunStatus::terminalComplete)
                .map(RunStatus::id).collect(java.util.stream.Collectors.toSet());
        Map<String, GpuEfficiencyStore.Profile> reusable = new LinkedHashMap<>();
        for (GpuEfficiencyStore.Profile profile : previous.results()) {
            String id = runId(profile.deviceId(), profile.coin(), profile.algorithm(), profile.minerSoftwareId());
            if (complete.contains(id)) reusable.put(id, profile);
        }
        return Map.copyOf(reusable);
    }

    static List<RunStatus> applyCheckpoint(List<RunStatus> fresh, List<RunStatus> previous,
                                           Map<String, GpuEfficiencyStore.Profile> checkpoint) {
        Map<String, RunStatus> oldById = new LinkedHashMap<>();
        for (RunStatus run : previous) oldById.put(run.id(), run);
        return fresh.stream().map(run -> {
            RunStatus old = oldById.get(run.id());
            GpuEfficiencyStore.Profile profile = checkpoint.get(run.id());
            if (old == null || profile == null || !old.terminalComplete() || old.mode() != run.mode()) return run;
            List<Integer> planned = old.plannedLimits().isEmpty() ? run.plannedLimits() : old.plannedLimits();
            return new RunStatus(run.id(), run.deviceId(), run.model(), run.coin(), run.algorithm(), run.mode(),
                    SweepRunState.COMPLETE, profile.bestStableWatts(), planned, profile.steps(), old.samples(),
                    run.samplesRequired(), run.referenceBatch(), "Aus unterbrochenem Sweep übernommen", run.softwareId());
        }).toList();
    }

    private synchronized void setPlannedLimits(Target target, List<Integer> limits, SweepMode mode, String detail) {
        mutateRun(target, current -> new RunStatus(current.id(), current.deviceId(), current.model(), current.coin(),
                current.algorithm(), mode, SweepRunState.QUEUED, null, List.copyOf(limits), current.steps(), 0,
                SAMPLES_PER_STEP, current.referenceBatch(), detail, current.softwareId()), detail);
    }

    private synchronized void updateRun(Target target, SweepRunState status, Integer limit,
                                        List<GpuEfficiencyStore.StepResult> steps, int samples, String detail) {
        mutateRun(target, current -> new RunStatus(current.id(), current.deviceId(), current.model(), current.coin(),
                current.algorithm(), current.mode(), status, limit, current.plannedLimits(), List.copyOf(steps),
                samples, SAMPLES_PER_STEP, current.referenceBatch(), detail, current.softwareId()), detail);
    }

    private synchronized void finishRun(Target target, SweepRunState status, String detail) {
        mutateRun(target, current -> new RunStatus(current.id(), current.deviceId(), current.model(), current.coin(),
                current.algorithm(), current.mode(), status, current.limitWatts(), current.plannedLimits(),
                current.steps(), current.samples(), current.samplesRequired(), current.referenceBatch(), detail,
                current.softwareId()), detail);
    }

    private void mutateRun(Target target, java.util.function.Function<RunStatus, RunStatus> change, String phase) {
        Session old = session;
        List<RunStatus> next = new ArrayList<>(old.runs());
        for (int index = 0; index < next.size(); index++) {
            if (next.get(index).id().equals(runId(target))) {
                next.set(index, change.apply(next.get(index)));
                break;
            }
        }
        int completed = (int) next.stream().filter(RunStatus::terminal).count();
        session = new Session(true, phase, old.startedAt(), completed, next.size(), old.results(), List.copyOf(next),
                estimateRemainingSeconds(next, old.startedAt()));
    }

    private static String runId(Target target) {
        return runId(target.gpu().deviceId(), target.coin(), target.algorithm(), target.softwareId());
    }

    private static String runId(String deviceId, String coin, String algorithm, String softwareId) {
        return deviceId + "|" + coin + "|" + algorithm
                + (softwareId == null ? "" : "|" + softwareId);
    }

    static long estimateRemainingSeconds(List<RunStatus> runs, Instant startedAt) {
        long sequentialSeconds = 0;
        Map<String, Long> parallelGroups = new LinkedHashMap<>();
        for (RunStatus run : runs) {
            if (run.terminal()) continue;
            int plannedSteps = Math.max(1, run.plannedLimits().size());
            int finishedSteps = run.steps().size();
            boolean currentStepFinished = run.limitWatts() != null && run.steps().stream()
                    .anyMatch(step -> step.limitWatts() == run.limitWatts());
            int liveSamples = currentStepFinished ? 0 : run.samples();
            int remainingSamples = Math.max(0, (plannedSteps - finishedSteps) * SAMPLES_PER_STEP - liveSamples);
            long runSeconds = remainingSamples * SAMPLE_INTERVAL_MILLIS / 1_000
                    + Math.max(0, plannedSteps - finishedSteps) * ESTIMATED_STARTUP_SECONDS;
            if (run.mode() == SweepMode.VALIDATION) {
                String group = String.join("|", run.coin(), run.algorithm(), run.model());
                parallelGroups.merge("validation|" + group, runSeconds, Math::max);
            } else if (run.referenceBatch() != null) {
                parallelGroups.merge("reference|" + run.referenceBatch(), runSeconds, Math::max);
            } else sequentialSeconds += runSeconds;
        }
        return sequentialSeconds + parallelGroups.values().stream().mapToLong(Long::longValue).sum();
    }

    private static List<RunStatus> finalizePendingRuns(List<RunStatus> runs, SweepRunState status, String detail) {
        return runs.stream().map(run -> run.terminal() ? run : new RunStatus(run.id(), run.deviceId(), run.model(),
                run.coin(), run.algorithm(), run.mode(), status, run.limitWatts(), run.plannedLimits(), run.steps(),
                run.samples(), run.samplesRequired(), run.referenceBatch(), detail, run.softwareId())).toList();
    }

    private static String formatRate(double hashrateHs) {
        if (hashrateHs >= 1_000_000_000_000d) return Math.round(hashrateHs / 1_000_000_000_000d) + " TH/s";
        if (hashrateHs >= 1_000_000_000d) return Math.round(hashrateHs / 1_000_000_000d) + " GH/s";
        if (hashrateHs >= 1_000_000d) return Math.round(hashrateHs / 1_000_000d) + " MH/s";
        if (hashrateHs >= 1_000d) return Math.round(hashrateHs / 1_000d) + " kH/s";
        return Math.round(hashrateHs) + " H/s";
    }

    private static String appendError(String current, String addition) {
        return current == null ? addition : current + "; " + addition;
    }

    @PreDestroy
    public void close() {
        guard.close();
        if (!guard.awaitTermination(30))
            session = new Session(false, "Fehler: Wiederherstellung nach Abbruch nicht abgeschlossen",
                    session.startedAt(), session.phaseIndex(), session.phaseCount(), session.results(), session.runs(), 0L);
    }

    /**
     * One coin x GPU measurement slot. {@code override} is a temporary route for an unconfigured
     * coin or a temporary extension of a configured coin's selected devices.
     */
    record Target(String coin, String algorithm, LocalGpuPowerService.Gpu gpu, MinerConfig override,
                  String softwareId) {
        Target(String coin, String algorithm, LocalGpuPowerService.Gpu gpu, MinerConfig override) {
            this(coin, algorithm, gpu, override, null);
        }
    }

    record SweepTask(Target target, String cohort, SweepMode mode, List<Integer> limits) {
    }

    private record SweepTaskResult(SweepTask task, GpuEfficiencyStore.Profile profile) {
    }

    public record RunStatus(String id, String deviceId, String model, String coin, String algorithm,
                            SweepMode mode, SweepRunState status, Integer limitWatts, List<Integer> plannedLimits,
                            List<GpuEfficiencyStore.StepResult> steps, int samples, int samplesRequired,
                            Integer referenceBatch, String detail, String softwareId) {
        public RunStatus(String id, String deviceId, String model, String coin, String algorithm,
                         SweepMode mode, SweepRunState status, Integer limitWatts, List<Integer> plannedLimits,
                         List<GpuEfficiencyStore.StepResult> steps, int samples, int samplesRequired,
                         Integer referenceBatch, String detail) {
            this(id, deviceId, model, coin, algorithm, mode, status, limitWatts, plannedLimits, steps, samples,
                    samplesRequired, referenceBatch, detail, null);
        }
        boolean terminal() {
            return status.terminal();
        }

        boolean terminalComplete() {
            return status == SweepRunState.COMPLETE;
        }
    }

    public record Session(boolean running, String phase, Instant startedAt, int phaseIndex, int phaseCount,
                          List<GpuEfficiencyStore.Profile> results, List<RunStatus> runs, Long secondsRemaining) {
        static Session idle() {
            return new Session(false, "Idle", null, 0, 0, List.of(), List.of(), 0L);
        }
    }
}
