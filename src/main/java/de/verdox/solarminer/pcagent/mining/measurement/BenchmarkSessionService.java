package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.BenchmarkMode;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.CoinMiner;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.measurement.MeasurementRunGuard;
import de.verdox.solarminer.pcagent.mining.measurement.SampleCollector;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.MinerStopContext;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;

/** Sample-driven local benchmark; full runs restore the miners that were active beforehand. */
@Service
public class BenchmarkSessionService {
    /** Samples are two seconds apart; a median of twelve points ignores startup noise. */
    public static final int REQUIRED_SAMPLES_PER_WORKER = 12;
    private static final long SAMPLE_INTERVAL_MILLIS = 2_000;
    /** Pearl's health monitor permits 90 seconds for its first pool job; leave a small scheduling margin. */
    static final Duration WORKER_STARTUP_GRACE = Duration.ofSeconds(95);
    /** LIVE phase label: measures whatever is currently mining, without a coin identity. */
    static final String PHASE_LIVE = "live";
    private final MiningService mining;
    private final MinerFactory miners;
    private final BenchmarkSharingService sharing;
    private final MinerConsoleService consoles;
    private final MiningPerformanceProfileStore performanceProfiles;
    private final WorkerAssignmentService assignments;
    private final MinerCatalogService catalog;
    private final MeasurementRunGuard guard;
    private volatile Session session = Session.idle();

    @org.springframework.beans.factory.annotation.Autowired
    public BenchmarkSessionService(MiningService mining, MinerFactory miners,
                                   BenchmarkSharingService sharing, MinerConsoleService consoles, LocalRunLock lock,
                                   MiningPerformanceProfileStore performanceProfiles, WorkerAssignmentService assignments,
                                   MinerCatalogService catalog) {
        this.mining = mining; this.miners = miners; this.sharing = sharing; this.consoles = consoles;
        this.performanceProfiles = performanceProfiles;
        this.assignments = assignments;
        this.catalog = catalog;
        this.guard = new MeasurementRunGuard(lock, "pc-agent-benchmark");
    }

    /** Focused test contexts predating the software catalog. */
    public BenchmarkSessionService(MiningService mining, MinerFactory miners,
                                   BenchmarkSharingService sharing, MinerConsoleService consoles, LocalRunLock lock,
                                   MiningPerformanceProfileStore performanceProfiles, WorkerAssignmentService assignments) {
        this(mining, miners, sharing, consoles, lock, performanceProfiles, assignments, null);
    }

    public synchronized Session start(String mode) { return start(mode, null); }

    /**
     * @param selectedCoinIds coins the operator opted into for this run; null or empty means
     *                        every configured mining coin. Unknown ids are rejected so a stale
     *                        browser selection can never silently widen the run.
     */
    public synchronized Session start(String mode, List<String> selectedCoinIds) {
        return start(mode, selectedCoinIds, null);
    }

    public synchronized Session start(String mode, List<String> selectedCoinIds, List<String> selectedWorkerIds) {
        return start(mode, selectedCoinIds, selectedWorkerIds, false);
    }

    public synchronized Session start(String mode, List<String> selectedCoinIds, List<String> selectedWorkerIds,
                                      boolean startMiningAfterBenchmark) {
        return start(mode, selectedCoinIds, selectedWorkerIds, startMiningAfterBenchmark, null);
    }

    public synchronized Session start(String mode, List<String> selectedCoinIds, List<String> selectedWorkerIds,
                                      boolean startMiningAfterBenchmark, Integer maxConcurrentGpus) {
        BenchmarkMode parsed = BenchmarkMode.from(mode).orElseThrow(() -> new IllegalArgumentException("Unknown benchmark mode"));
        int gpuLimit = validateGpuLimit(maxConcurrentGpus);
        if (session.running()) throw new IllegalStateException("A benchmark is already running");
        Set<String> selection = validateCoinSelection(selectedCoinIds);
        Set<String> workers = selectedWorkerIds == null ? Set.of() : validateWorkerSelection(selectedWorkerIds,
                assignments.workers().stream().map(WorkerAssignmentService.WorkerView::deviceId).collect(java.util.stream.Collectors.toSet()));
        List<String> setupSkipped = new ArrayList<>();
        if (parsed == BenchmarkMode.INSTALLED) {
            assignments.prepareBenchmarkDefaults(selection).forEach((coin, reason) -> {
                if (reason != null && !reason.isBlank()) setupSkipped.add(coin + " (" + reason + ")");
            });
        }
        List<String> phases = parsed == BenchmarkMode.INSTALLED ? installedPhases(selection, workers) : List.of(PHASE_LIVE);
        List<GpuBenchmarkBatch> plannedBatches = parsed == BenchmarkMode.INSTALLED ? gpuBenchmarkBatches(phases, workers, gpuLimit) : List.of();
        if (phases.isEmpty() || parsed == BenchmarkMode.INSTALLED
                && !phases.contains(Coin.MONERO.id()) && plannedBatches.isEmpty()) {
            String detail = setupSkipped.isEmpty()
                    ? "Install and configure at least one miner before running this benchmark"
                    : "Benchmark cannot start: " + String.join("; ", setupSkipped);
            throw new IllegalStateException(detail);
        }
        if (!guard.begin("benchmark")) throw new IllegalStateException("Ein anderer Messlauf (Effizienz-Sweep) läuft gerade; der Benchmark wartet, bis er beendet ist");
        guard.resetCancel();
        Instant start = Instant.now();
        int phaseCount = parsed == BenchmarkMode.INSTALLED
                ? (phases.contains(Coin.MONERO.id()) ? 1 : 0) + plannedBatches.size()
                : phases.size();
        session = new Session(true, parsed.wireName(), "Preparing", start, null, null, 0, phaseCount,
                List.of(), null, List.copyOf(workers), List.of(), startMiningAfterBenchmark, List.of());
        guard.submit(() -> run(parsed, phases, plannedBatches, setupSkipped, selection, workers,
                startMiningAfterBenchmark));
        return session;
    }

    public Session status() {
        Session value = session;
        return value;
    }
    public synchronized Session cancel() { guard.requestCancel(); return status(); }

    /** Serializes Node controls with measurement runs so a checked command cannot race a new run. */
    public synchronized <T> T withExternalControl(Supplier<T> command) {
        if (session.running() || guard.lockBusy()) throw new BenchmarkRunningException();
        return command.get();
    }

    private List<String> installedPhases(Set<String> selection, Set<String> workers) {
        List<String> phases = new ArrayList<>();
        if ((workers.isEmpty() || workers.contains(WorkerIds.CPU)) && (selection.isEmpty() || selection.contains(Coin.MONERO.id())))
            if (miners.miner(Coin.MONERO) instanceof de.verdox.solarminer.pcagent.miner.CpuMiner cpu && cpu.readyForStart())
                phases.add(Coin.MONERO.id());
        assignments.benchmarkGpuPhases().stream()
                .filter(coin -> selection.isEmpty() || selection.contains(coin))
                .forEach(phases::add);
        return List.copyOf(phases);
    }

    /** Unknown coin ids in a browser selection are a defect, not an invitation to widen the run. */
    static Set<String> validateCoinSelection(List<String> selectedCoinIds) {
        if (selectedCoinIds == null || selectedCoinIds.isEmpty()) return Set.of();
        Set<String> selection = new java.util.LinkedHashSet<>();
        for (String raw : selectedCoinIds) {
            Coin coin = Coin.byIdOrNull(raw);
            if (coin == null || coin == Coin.NONE)
                throw new IllegalArgumentException("Unknown benchmark coin: " + raw);
            selection.add(coin.id());
        }
        return Set.copyOf(selection);
    }

    static Set<String> validateWorkerSelection(List<String> selectedWorkerIds, Set<String> available) {
        if (selectedWorkerIds == null || selectedWorkerIds.isEmpty())
            throw new IllegalArgumentException("benchmark_select_worker");
        Set<String> selected = new LinkedHashSet<>(selectedWorkerIds);
        if (selected.stream().anyMatch(id -> id == null || id.isBlank() || !available.contains(id)))
            throw new IllegalArgumentException("benchmark_worker_unavailable");
        return Set.copyOf(selected);
    }

    private void run(BenchmarkMode mode, List<String> phases, List<GpuBenchmarkBatch> gpuBatches,
                     List<String> setupSkipped, Set<String> selection, Set<String> workers,
                     boolean startMiningAfterBenchmark) {
        boolean installed = mode == BenchmarkMode.INSTALLED;
        boolean externalMiner = Coin.miningCoins().stream().anyMatch(coin -> coin.isGpu()
                ? miners.gpuMinerAdapters(coin).stream().anyMatch(adapter -> adapter.miner().hasExternalMinerProcess())
                : miners.miner(coin).hasExternalMinerProcess());
        if (installed && externalMiner) {
            Session old = session;
            session = new Session(false, mode.wireName(), "Sequential benchmark skipped: externally started miners cannot be safely paused and restored",
                    old.startedAt(), Instant.now(), null, 0, old.phaseCount(), List.of(), null,
                    old.selectedWorkers(), List.of(), old.autoStartRequested(),
                    skippedAutoStarts(workers, "benchmark_auto_start_failed_run"));
            guard.end();
            return;
        }
        List<MinerStats.Worker> before = mining.getWorkerStats();
        boolean cpuPausedBefore = mining.cpuManuallyPaused();
        boolean xmrWasMining = before.stream().anyMatch(w -> isMining(w) && "CPU".equalsIgnoreCase(w.hardwareType()));
        // Do not derive these snapshots from visible worker status: SRBMiner deliberately
        // reports PAUSED while it is connecting to the pool, although its managed process
        // is already running. Phase 3: one generic capture/restore seam for every GPU
        // adapter — no Pearl special case.
        Map<String, GpuCoinMiner.WorkerPauseSnapshot> adapterSnapshots = new LinkedHashMap<>();
        Map<String, List<MinerStats.Worker>> observations = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>(setupSkipped);
        int totalPhases = installed ? (phases.contains(Coin.MONERO.id()) ? 1 : 0) + gpuBatches.size() : phases.size();
        int index = 0;
        String error = null;
        try {
            for (Coin coin : Coin.gpuCoins()) for (MinerFactory.GpuMinerAdapter adapter : miners.gpuMinerAdapters(coin))
                adapterSnapshots.put(coin.id() + "|" + adapter.softwareId(), adapter.miner().captureState());
            for (String skippedSetup : setupSkipped)
                benchmarkLog(Coin.PEARL.id(), "Benchmark setup skipped: " + skippedSetup);
            if (installed) {
                update(mode, "Pausing current miners", 0, totalPhases, observations);
                if (xmrWasMining && !pauseForBenchmark(Coin.MONERO.id(), "capturing the pre-benchmark state"))
                    throw new IllegalStateException("benchmark_stop_failed");
                for (Map.Entry<String, GpuCoinMiner.WorkerPauseSnapshot> snapshot : adapterSnapshots.entrySet())
                    if (!snapshot.getValue().runningDeviceIds().isEmpty())
                        if (!MinerStopContext.with("Benchmark: capturing the pre-benchmark state", () -> {
                            String[] parts = snapshot.getKey().split("\\|", 2);
                            return miners.gpuMiner(Coin.byIdOrNull(parts[0]), parts[1]).stopAll();
                        })) throw new IllegalStateException("benchmark_stop_failed");
            }
            for (String phase : phases) {
                // GPU work is scheduled below in per-device batches. Leaving the CPU phase
                // here preserves the existing CPU lifecycle and its independent XMRig rules.
                if (installed && Coin.byIdOrNull(phase) != null && Coin.byIdOrNull(phase).isGpu()) continue;
                if (guard.cancelRequested()) break;
                index++;
                if (installed) {
                    if (!mining.resumeMining(phase)) {
                        String detail = mining.lastStartError(phase);
                        String reason = detail == null || detail.isBlank() ? "Miner did not start; check its console" : detail;
                        skipped.add(phase + " (" + reason + ")");
                        benchmarkLog(phase, "Benchmark start failed: " + reason);
                        continue;
                    }
                }
                // In INSTALLED mode a just-started miner must be a candidate before its first API/pool
                // health sample. LIVE intentionally keeps its existing "only currently mining" semantics.
                List<MinerStats.Worker> candidates = phaseWorkers(phase, mode == BenchmarkMode.LIVE, selection, workers);
                Set<String> expected = candidates.stream().map(this::key).collect(java.util.stream.Collectors.toSet());
                if (expected.isEmpty()) {
                    String reason = mode == BenchmarkMode.LIVE
                            ? " (no active workers)" : " (no configured workers detected)";
                    skipped.add(phase + reason);
                    benchmarkLog(phase, "Phase skipped:" + reason);
                    if (installed) {
                        benchmarkLog(phase, "Benchmark pauses miner after skipped phase.");
                        if (!pauseForBenchmark(phase, "skipped phase"))
                            throw new IllegalStateException("benchmark_stop_failed");
                    }
                    continue;
                }
                benchmarkLog(phase, "Measurement phase started; expected workers: " + String.join(", ", expected)
                        + (installed ? "; waiting up to 95 s for the first active status." : "."));
                Set<String> unavailable = new HashSet<>();
                Set<String> observedMining = new HashSet<>();
                Instant startupDeadline = Instant.now().plus(WORKER_STARTUP_GRACE);
                update(mode, phaseLabel(phase) + " · 0/" + (expected.size() * REQUIRED_SAMPLES_PER_WORKER) + " Messpunkte",
                        index, totalPhases, observations);
                while (!guard.cancelRequested()) {
                    List<MinerStats.Worker> current = phaseWorkers(phase, false, selection, workers);
                    Map<String, MinerStats.Worker> currentByKey = current.stream()
                            .collect(java.util.stream.Collectors.toMap(this::key, worker -> worker, (first, ignored) -> first));
                    for (MinerStats.Worker worker : current.stream().filter(BenchmarkSessionService::isMining).toList()) {
                        String workerKey = key(worker);
                        if (!expected.contains(workerKey)) continue;
                        observedMining.add(workerKey);
                        if (!hasValidHashrate(worker)) continue;
                        List<MinerStats.Worker> samples = observations.computeIfAbsent(workerKey, ignored -> new ArrayList<>());
                        if (samples.size() < REQUIRED_SAMPLES_PER_WORKER) {
                            samples.add(worker);
                        }
                    }
                    List<String> lost = expected.stream()
                            .filter(k -> observations.getOrDefault(k, List.of()).size() < REQUIRED_SAMPLES_PER_WORKER)
                            .filter(k -> !unavailable.contains(k))
                            .filter(k -> workerUnavailable(k, currentByKey.get(k), observedMining, startupDeadline))
                            .toList();
                    unavailable.addAll(lost);
                    if (!lost.isEmpty()) {
                        String reason = "Worker did not start or stopped: " + String.join(", ", lost);
                        skipped.add(phase + " (" + reason + ")");
                        benchmarkLog(phase, reason);
                    }
                    boolean complete = expected.stream().allMatch(k -> unavailable.contains(k)
                            || observations.getOrDefault(k, List.of()).size() >= REQUIRED_SAMPLES_PER_WORKER);
                    if (complete) break;
                    int collected = expected.stream().mapToInt(k -> observations.getOrDefault(k, List.of()).size()).sum();
                    update(mode, phaseLabel(phase) + " · " + collected + "/" + (expected.size() * REQUIRED_SAMPLES_PER_WORKER) + " Messpunkte",
                            index, totalPhases, observations);
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS);
                }
                int collected = expected.stream().mapToInt(k -> observations.getOrDefault(k, List.of()).size()).sum();
                benchmarkLog(phase, "Measurement phase complete: " + collected + "/" + (expected.size() * REQUIRED_SAMPLES_PER_WORKER)
                        + " valid samples." + (unavailable.isEmpty() ? "" : " Unavailable workers: " + String.join(", ", unavailable)));
                if (installed) {
                    benchmarkLog(phase, "Benchmark pauses miner after measurement to restore the previous state.");
                    if (!pauseForBenchmark(phase, "measurement phase complete"))
                        throw new IllegalStateException("benchmark_stop_failed");
                }
                update(mode, "Summarizing", index, totalPhases, observations);
            }
            for (GpuBenchmarkBatch batch : gpuBatches) {
                if (guard.cancelRequested()) break;
                index++;
                measureGpuBatch(mode, batch, index, totalPhases, observations, skipped);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); error = "Benchmark interrupted";
        } catch (RuntimeException e) {
            error = Objects.toString(e.getMessage(), "Benchmark failed");
        } finally {
            boolean measurementSucceeded = error == null;
            boolean restoreSucceeded = true;
            try {
                if (installed) {
                    boolean restored = true;
                    if (xmrWasMining) restored = mining.resumeMining(Coin.MONERO.id());
                    else mining.restoreCpuPauseState(cpuPausedBefore);
                    for (Map.Entry<String, GpuCoinMiner.WorkerPauseSnapshot> snapshot : adapterSnapshots.entrySet()) {
                        String[] parts = snapshot.getKey().split("\\|", 2);
                        restored = miners.gpuMiner(Coin.byIdOrNull(parts[0]), parts[1]).restoreState(snapshot.getValue()) && restored;
                    }
                    if (!restored) { error = "Could not fully restore miner state"; restoreSucceeded = false; }
                    benchmarkLog(Coin.MONERO.id(), restored ? "Previous miner state restored."
                            : "Previous miner state could not be fully restored.");
                    benchmarkLog(Coin.PEARL.id(), restored ? "Previous miner state restored."
                            : "Previous miner state could not be fully restored.");
                }
            } catch (RuntimeException restoreFailure) {
                error = "Could not fully restore miner state: " + Objects.toString(restoreFailure.getMessage(), "unknown error");
                restoreSucceeded = false;
            }
            List<MinerStats.Worker> captured = aggregateWorkers(observations);
            try {
                persistPerformanceProfiles(observations);
            } catch (RuntimeException persistenceFailure) {
                error = "Could not save local benchmark profiles";
            }
            List<AutoStartResult> autoStartResults = List.of();
            boolean cancelledBeforeAutoStart;
            try {
                try {
                    sharing.reportManualResults(captured);
                } catch (RuntimeException sharingFailure) {
                    if (error == null) error = "Could not prepare benchmark sharing";
                }
                cancelledBeforeAutoStart = guard.cancelRequested();
                if (startMiningAfterBenchmark) {
                    if (cancelledBeforeAutoStart)
                        autoStartResults = skippedAutoStarts(workers, "benchmark_auto_start_cancelled");
                    else if (!measurementSucceeded || !restoreSucceeded)
                        autoStartResults = skippedAutoStarts(workers, "benchmark_auto_start_failed_run");
                    else {
                        update(mode, "benchmark_auto_start_verifying", totalPhases, totalPhases, observations);
                        autoStartResults = confirmAutoStarts(startConfiguredWorkers(workers), Duration.ofSeconds(240));
                    }
                }
            } finally {
                guard.end();
            }
            Session old = session;
            String result = error != null ? "Error: " + error : cancelledBeforeAutoStart ? "Cancelled; previous miner state restored"
                    : "Complete; previous miner state restored" + (skipped.isEmpty() ? "" : "; skipped: " + String.join(", ", skipped));
            session = new Session(false, mode.wireName(), result, old.startedAt(), Instant.now(), null,
                    old.phaseCount(), old.phaseCount(), summarize(observations), null,
                    old.selectedWorkers(), liveSeries(observations), old.autoStartRequested(), autoStartResults);
        }
    }

    List<AutoStartResult> startConfiguredWorkers(Set<String> workerIds) {
        List<AutoStartResult> outcomes = new ArrayList<>();
        for (String deviceId : workerIds) {
            if (guard.cancelRequested()) {
                outcomes.add(new AutoStartResult(deviceId, "SKIPPED", "benchmark_auto_start_cancelled"));
                continue;
            }
            try {
                if (!assignments.stopForBenchmarkRestart(deviceId)) throw new IllegalStateException("benchmark_auto_start_stop_failed");
                if (!assignments.startWithHeldRunLock(deviceId))
                    throw new IllegalStateException("benchmark_auto_start_start_failed");
                outcomes.add(new AutoStartResult(deviceId, "STARTED", null));
            } catch (RuntimeException failure) {
                outcomes.add(new AutoStartResult(deviceId, "FAILED",
                        Objects.toString(failure.getMessage(), "benchmark_auto_start_start_failed")));
            }
        }
        return List.copyOf(outcomes);
    }

    /** A successful start call is provisional until live telemetry reports positive hashrate. */
    List<AutoStartResult> confirmAutoStarts(List<AutoStartResult> starts, Duration timeout) {
        Map<String, AutoStartResult> results = new LinkedHashMap<>();
        starts.forEach(result -> results.put(result.deviceId(), result));
        Set<String> pending = new LinkedHashSet<>();
        starts.stream().filter(result -> "STARTED".equals(result.status()))
                .map(AutoStartResult::deviceId).forEach(pending::add);
        Instant deadline = Instant.now().plus(timeout);
        while (!pending.isEmpty()) {
            Map<String, List<MinerStats.Worker>> live = new LinkedHashMap<>();
            try {
                for (MinerStats.Worker worker : mining.getWorkerStats())
                    live.computeIfAbsent(worker.deviceId(), ignored -> new ArrayList<>()).add(worker);
            } catch (RuntimeException telemetryFailure) {
                // A transient miner API read must not erase a successful start. Retry until
                // the shared verification deadline, then report the unconfirmed workers.
            }
            for (Iterator<String> iterator = pending.iterator(); iterator.hasNext(); ) {
                String deviceId = iterator.next();
                List<MinerStats.Worker> workers = live.getOrDefault(deviceId, List.of());
                if (workers.stream().anyMatch(worker -> worker.miningStatus() == MinerStats.MinerStatus.MINING
                        && hasValidHashrate(worker))) {
                    iterator.remove();
                } else if (!workers.isEmpty() && workers.stream()
                        .allMatch(worker -> worker.miningStatus() == MinerStats.MinerStatus.ERROR)) {
                    results.put(deviceId, new AutoStartResult(deviceId, "FAILED", "benchmark_auto_start_miner_error"));
                    iterator.remove();
                }
            }
            if (pending.isEmpty()) break;
            if (guard.cancelRequested() || !Instant.now().isBefore(deadline)) break;
            try { Thread.sleep(SAMPLE_INTERVAL_MILLIS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        for (String deviceId : pending)
            results.put(deviceId, new AutoStartResult(deviceId, "FAILED", "benchmark_auto_start_no_hashrate"));
        return List.copyOf(results.values());
    }

    private static List<AutoStartResult> skippedAutoStarts(Set<String> workerIds, String reason) {
        return workerIds.stream().map(deviceId -> new AutoStartResult(deviceId, "SKIPPED", reason)).toList();
    }

    private List<MinerStats.Worker> phaseWorkers(String phase, boolean miningOnly) {
        return phaseWorkers(mining.getWorkerStats(), phase, miningOnly);
    }

    /** Every selected GPU measures every compatible coin, even when another GPU has the same model. */
    static int validateGpuLimit(Integer limit) {
        if (limit == null) return Integer.MAX_VALUE; // Older clients retain their existing behavior.
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("benchmark_invalid_gpu_concurrency");
        return limit;
    }

    private List<GpuBenchmarkBatch> gpuBenchmarkBatches(List<String> phases, Set<String> workers, int gpuLimit) {
        Map<String, Map<Coin, List<LocalGpuPowerService.Gpu>>> bySoftware = new LinkedHashMap<>();
        for (String phase : phases) {
            Coin coin = Coin.byIdOrNull(phase);
            if (coin == null || !coin.isGpu()) continue;
            List<MinerCatalogService.MinerOption> options = catalog == null ? List.of() : catalog.options(coin.id());
            if (options.isEmpty()) {
                bySoftware.computeIfAbsent("srbminer-multi", ignored -> new LinkedHashMap<>())
                        .put(coin, miners.gpuMiner(coin).selectedGpus().stream()
                                .filter(gpu -> workers.isEmpty() || workers.contains(gpu.deviceId())).toList());
            }
            for (MinerCatalogService.MinerOption option : options) {
                if (!option.installed() || !option.selectable()) continue;
                GpuCoinMiner miner = miners.gpuMiner(coin, option.id());
                if (!miner.binaryAvailable() || !miner.configured()) continue;
                List<LocalGpuPowerService.Gpu> cards = miner.selectedGpus().stream()
                        .filter(gpu -> (workers.isEmpty() || workers.contains(gpu.deviceId()))
                                && MinerCatalogService.supportsHardware(option, "GPU", gpu.vendor())).toList();
                if (!cards.isEmpty()) bySoftware.computeIfAbsent(option.id(), ignored -> new LinkedHashMap<>())
                        .put(coin, cards);
            }
        }
        List<GpuBenchmarkBatch> batches = new ArrayList<>();
        bySoftware.forEach((software, cards) -> planGpuBatches(cards, gpuLimit)
                .forEach(wave -> batches.add(new GpuBenchmarkBatch(software, wave))));
        return List.copyOf(batches);
    }

    /** Pure planner: each physical device has its own coin queue and runs at most one coin per wave. */
    static List<Map<Coin, List<LocalGpuPowerService.Gpu>>> planGpuBatches(Map<Coin, List<LocalGpuPowerService.Gpu>> cardsByCoin) {
        return planGpuBatches(cardsByCoin, Integer.MAX_VALUE);
    }

    static List<Map<Coin, List<LocalGpuPowerService.Gpu>>> planGpuBatches(
            Map<Coin, List<LocalGpuPowerService.Gpu>> cardsByCoin, int gpuLimit) {
        Map<String, LocalGpuPowerService.Gpu> devices = new LinkedHashMap<>();
        Map<String, List<Coin>> coinsByDevice = new LinkedHashMap<>();
        cardsByCoin.forEach((coin, cards) -> {
            for (LocalGpuPowerService.Gpu card : cards) {
                devices.putIfAbsent(card.deviceId(), card);
                List<Coin> eligible = coinsByDevice.computeIfAbsent(card.deviceId(), ignored -> new ArrayList<>());
                if (!eligible.contains(coin)) eligible.add(coin);
            }
        });
        List<Map<Coin, List<LocalGpuPowerService.Gpu>>> waves = new ArrayList<>();
        int deviceIndex = 0;
        for (Map.Entry<String, List<Coin>> entry : coinsByDevice.entrySet()) {
            List<Coin> eligible = entry.getValue();
            for (int wave = 0; wave < eligible.size(); wave++) {
                while (waves.size() <= wave) waves.add(new LinkedHashMap<>());
                Coin coin = eligible.get((wave + deviceIndex) % eligible.size());
                waves.get(wave).computeIfAbsent(coin, ignored -> new ArrayList<>()).add(devices.get(entry.getKey()));
            }
            deviceIndex++;
        }
        List<Map<Coin, List<LocalGpuPowerService.Gpu>>> limited = new ArrayList<>();
        for (Map<Coin, List<LocalGpuPowerService.Gpu>> wave : waves) {
            Map<Coin, List<LocalGpuPowerService.Gpu>> chunk = new LinkedHashMap<>();
            int size = 0;
            for (Map.Entry<Coin, List<LocalGpuPowerService.Gpu>> entry : wave.entrySet()) {
                for (LocalGpuPowerService.Gpu gpu : entry.getValue()) {
                    if (size == gpuLimit) {
                        limited.add(immutableBatch(chunk));
                        chunk = new LinkedHashMap<>();
                        size = 0;
                    }
                    chunk.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(gpu);
                    size++;
                }
            }
            if (size > 0) limited.add(immutableBatch(chunk));
        }
        return List.copyOf(limited);
    }

    private static Map<Coin, List<LocalGpuPowerService.Gpu>> immutableBatch(Map<Coin, List<LocalGpuPowerService.Gpu>> batch) {
        Map<Coin, List<LocalGpuPowerService.Gpu>> copy = new LinkedHashMap<>();
        batch.forEach((coin, cards) -> copy.put(coin, List.copyOf(cards)));
        return Collections.unmodifiableMap(copy);
    }

    private void measureGpuBatch(BenchmarkMode mode, GpuBenchmarkBatch batch, int index, int phaseCount,
                                 Map<String, List<MinerStats.Worker>> observations, List<String> skipped)
            throws InterruptedException {
        Set<String> expected = new LinkedHashSet<>();
        try {
            for (Map.Entry<Coin, List<LocalGpuPowerService.Gpu>> entry : batch.assignments().entrySet()) {
                GpuCoinMiner miner = miners.gpuMiner(entry.getKey(), batch.softwareId());
                MinerConfig base = miner.configuration();
                if (base == null) {
                    continue;
                }
                String devices = entry.getValue().stream().map(gpu -> gpu.vendor() + ":" + gpu.index())
                        .collect(java.util.stream.Collectors.joining(","));
                miner.setBenchmarkOverride(new MinerConfig(base.poolUrl(), base.proxyUrl(), base.wallet(), base.worker(), devices));
                for (LocalGpuPowerService.Gpu gpu : entry.getValue()) miners.setBenchmarkSoftware(gpu.deviceId(), batch.softwareId());
                for (LocalGpuPowerService.Gpu gpu : entry.getValue()) if (!miner.resumeGpu(gpu.vendor(), gpu.index())) {
                    String detail = miner.lastError();
                    skipped.add(entry.getKey().id() + "/" + batch.softwareId() + " (" +
                            (detail == null || detail.isBlank() ? "Miner did not start" : detail) + ")");
                }
            }
            for (Map.Entry<Coin, List<LocalGpuPowerService.Gpu>> entry : batch.assignments().entrySet()) {
                Set<String> ids = entry.getValue().stream().map(LocalGpuPowerService.Gpu::deviceId).collect(java.util.stream.Collectors.toSet());
                phaseWorkers(entry.getKey().id(), false).stream().filter(worker -> ids.contains(worker.deviceId()))
                        .map(this::key).forEach(expected::add);
            }
            if (expected.isEmpty()) {
                return;
            }
            Set<String> unavailable = new HashSet<>();
            Set<String> observedMining = new HashSet<>();
            Instant startupDeadline = Instant.now().plus(WORKER_STARTUP_GRACE);
            while (!guard.cancelRequested()) {
                Map<String, MinerStats.Worker> current = new LinkedHashMap<>();
                for (Coin coin : batch.assignments().keySet())
                    for (MinerStats.Worker worker : phaseWorkers(coin.id(), false)) current.put(key(worker), worker);
                for (MinerStats.Worker worker : current.values()) {
                    String workerKey = key(worker);
                    if (!expected.contains(workerKey) || !isMining(worker)) continue;
                    observedMining.add(workerKey);
                    if (hasValidHashrate(worker)) {
                        List<MinerStats.Worker> samples = observations.computeIfAbsent(workerKey, ignored -> new ArrayList<>());
                        if (samples.size() < REQUIRED_SAMPLES_PER_WORKER) samples.add(worker);
                    }
                }
                for (String workerKey : expected) {
                    if (observations.getOrDefault(workerKey, List.of()).size() < REQUIRED_SAMPLES_PER_WORKER
                            && workerUnavailable(workerKey, current.get(workerKey), observedMining, startupDeadline)) unavailable.add(workerKey);
                }
                if (expected.stream().allMatch(workerKey -> unavailable.contains(workerKey)
                        || observations.getOrDefault(workerKey, List.of()).size() >= REQUIRED_SAMPLES_PER_WORKER)) break;
                int collected = expected.stream().mapToInt(workerKey -> observations.getOrDefault(workerKey, List.of()).size()).sum();
                update(mode, "Measuring active miners", index, phaseCount, observations);
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            }
        } finally {
            // This is an orchestration stop, not an operator pause.  In particular, do not
            // mark a temporarily assigned card as manually paused before the saved state is
            // restored.
            boolean stopped = true;
            for (Coin coin : batch.assignments().keySet())
                stopped = MinerStopContext.with("Benchmark: measurement phase complete",
                        () -> miners.gpuMiner(coin, batch.softwareId()).stopAll()) && stopped;
            for (Coin coin : batch.assignments().keySet()) miners.gpuMiner(coin, batch.softwareId()).clearBenchmarkOverride();
            for (List<LocalGpuPowerService.Gpu> cards : batch.assignments().values())
                for (LocalGpuPowerService.Gpu gpu : cards) miners.clearBenchmarkSoftware(gpu.deviceId());
            if (!stopped) throw new IllegalStateException("benchmark_stop_failed");
        }
    }
    private List<MinerStats.Worker> phaseWorkers(String phase, boolean miningOnly, Set<String> selection, Set<String> selectedWorkers) {
        List<MinerStats.Worker> candidates = phaseWorkers(phase, miningOnly).stream()
                .filter(worker -> selectedWorkers.isEmpty() || selectedWorkers.contains(worker.deviceId())).toList();
        if (selection.isEmpty()) return candidates;
        // LIVE has no phase identity of its own, so the coin selection filters its workers
        // directly; coin phases are already limited by their phase name.
        if (!PHASE_LIVE.equals(phase)) return candidates;
        return candidates.stream()
                .filter(w -> selection.contains(coinForAlgorithm(w.currentAlgorithm())))
                .toList();
    }
    private void benchmarkLog(String phase, String message) {
        String entry = "[Benchmark] " + message;
        Coin coin = Coin.byIdOrNull(phase);
        if (coin != null) miners.miner(coin).appendBenchmarkEvent(entry);
        else consoles.append(phase, entry);
    }
    private boolean pauseForBenchmark(String phase, String reason) {
        return MinerStopContext.with("Benchmark: " + reason, () -> mining.pauseMining(phase));
    }

    static List<MinerStats.Worker> phaseWorkers(List<MinerStats.Worker> workers, String phase, boolean miningOnly) {
        return workers.stream()
                .filter(w -> PHASE_LIVE.equals(phase) || (Coin.MONERO.id().equals(phase)
                        ? "CPU".equalsIgnoreCase(w.hardwareType())
                        : phase.equals(coinForAlgorithm(w.currentAlgorithm()))))
                .filter(w -> !miningOnly || isMining(w))
                .toList();
    }
    static boolean workerUnavailable(String workerKey, MinerStats.Worker current, Set<String> observedMining, Instant startupDeadline) {
        if (current != null && current.miningStatus() == MinerStats.MinerStatus.ERROR) return true;
        if (current != null && isMining(current)) return false;
        if (observedMining.contains(workerKey)) return current == null || !isMining(current);
        return !Instant.now().isBefore(startupDeadline);
    }
    private static boolean hasValidHashrate(MinerStats.Worker w) { return SampleCollector.hasValidHashrate(w); }
    private static String phaseLabel(String phase) { return PHASE_LIVE.equals(phase) ? "Measuring active miners" : "Benchmarking " + phase; }

    private void update(BenchmarkMode mode, String phase, int index, int count, Map<String, List<MinerStats.Worker>> observations) {
        Session old = session;
        session = new Session(true, mode.wireName(), phase, old.startedAt(), null, null, index, count,
                summarize(observations), null, old.selectedWorkers(), liveSeries(observations),
                old.autoStartRequested(), List.of());
    }
    /** Bounded trace for the local operator UI; every worker/algorithm/miner has at most twelve points. */
    static List<LiveSeries> liveSeries(Map<String, List<MinerStats.Worker>> observations) {
        return observations.entrySet().stream().map(entry -> {
            List<MinerStats.Worker> values = entry.getValue();
            MinerStats.Worker first = values.getFirst();
            int count = Math.min(values.size(), REQUIRED_SAMPLES_PER_WORKER);
            List<SamplePoint> points = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                MinerStats.Worker sample = values.get(index);
                points.add(new SamplePoint(index + 1, sample.terahashPerSecond() * 1_000_000_000_000d,
                        sample.approximatedPowerUsageWatts()));
            }
            return new LiveSeries(first.deviceId(), first.hardwareType(), first.hardwareModel(),
                    first.currentAlgorithm(), entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1),
                    List.copyOf(points));
        }).toList();
    }
    private static boolean isMining(MinerStats.Worker w) { return w.miningStatus() == MinerStats.MinerStatus.MINING; }
    private String key(MinerStats.Worker w) {
        Coin coin = Coin.byAlgorithm(w.currentAlgorithm()).orElse(Coin.NONE);
        return String.join("|", Objects.toString(w.deviceId(), ""), Objects.toString(w.currentAlgorithm(), ""),
                coin == Coin.NONE ? "unknown" : miners.minerSoftwareForWorker(coin, w.deviceId()));
    }
    private static List<Result> summarize(Map<String, List<MinerStats.Worker>> observations) {
        return observations.entrySet().stream().map(entry -> {
            List<MinerStats.Worker> values = entry.getValue();
            MinerStats.Worker w = values.getFirst();
            double[] rates = values.stream().mapToDouble(v -> v.terahashPerSecond() * 1_000_000_000_000d).toArray();
            double median = SampleCollector.median(rates);
            double watts = values.stream().mapToLong(MinerStats.Worker::approximatedPowerUsageWatts).filter(v -> v > 0).average().orElse(0);
            return new Result(w.hardwareType(), w.hardwareModel(), w.deviceId(), w.currentAlgorithm(), median, watts,
                    watts > 0 ? median / watts : 0, values.size(),
                    entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1));
        }).toList();
    }
    private static List<MinerStats.Worker> aggregateWorkers(Map<String, List<MinerStats.Worker>> observations) {
        return observations.values().stream().map(values -> {
            MinerStats.Worker w = values.getFirst();
            double[] rates = values.stream().mapToDouble(MinerStats.Worker::terahashPerSecond).toArray();
            double medianRate = SampleCollector.median(rates);
            long watts = Math.round(values.stream().mapToLong(MinerStats.Worker::approximatedPowerUsageWatts).filter(v -> v > 0).average().orElse(0));
            return new MinerStats.Worker(w.miningStatus(), w.workerDisplayName(), w.currentAlgorithm(), medianRate,
                    w.temperatureCelsius(), w.powerTargetWatts(), w.minPowerTarget(), w.defaultPowerTarget(),
                    w.maxPowerTarget(), watts, w.pools(), w.hardwareType(), w.hardwareModel(), w.deviceId(),
                    w.acceptedShares(), w.rejectedShares(), w.pool());
        }).toList();
    }

    private void persistPerformanceProfiles(Map<String, List<MinerStats.Worker>> observations) {
        Instant measuredAt = Instant.now();
        for (Map.Entry<String, List<MinerStats.Worker>> entry : observations.entrySet()) {
            List<MinerStats.Worker> values = entry.getValue();
            if (values.size() < REQUIRED_SAMPLES_PER_WORKER) continue;
            MinerStats.Worker worker = values.getFirst();
            // A changed cap during LIVE measurement must not blend two operating points.
            if (values.stream().anyMatch(value -> value.powerTargetWatts() != worker.powerTargetWatts())) continue;
            String coin = coinForAlgorithm(worker.currentAlgorithm());
            if (coin == null || worker.deviceId() == null || worker.deviceId().isBlank()) continue;
            double[] rates = values.stream().mapToDouble(value -> value.terahashPerSecond() * 1_000_000_000_000d).toArray();
            double hashrate = SampleCollector.median(rates);
            long watts = Math.round(values.stream().mapToLong(MinerStats.Worker::approximatedPowerUsageWatts)
                    .filter(value -> value > 0).average().orElse(0));
            if (!(hashrate > 0) || watts <= 0) continue;
            performanceProfiles.save(new MiningPerformanceProfileStore.Profile(worker.deviceId(), worker.hardwareType(),
                    worker.hardwareModel(), coin, worker.currentAlgorithm(), hashrate, watts, values.size(), measuredAt, "BENCHMARK",
                    worker.powerTargetWatts() > 0 ? Math.toIntExact(worker.powerTargetWatts()) : null,
                    entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1)));
        }
    }

    private static String coinForAlgorithm(String algorithm) {
        Coin coin = Coin.byAlgorithm(algorithm).orElse(null);
        return coin == null || coin == Coin.NONE ? null : coin.id();
    }
    @PreDestroy public void close() { guard.close(); }

    private record GpuBenchmarkBatch(String softwareId, Map<Coin, List<LocalGpuPowerService.Gpu>> assignments) {
        private GpuBenchmarkBatch {
            Map<Coin, List<LocalGpuPowerService.Gpu>> copy = new LinkedHashMap<>();
            assignments.forEach((coin, cards) -> copy.put(coin, List.copyOf(cards)));
            assignments = Map.copyOf(copy);
        }
    }

    public record Result(String hardwareType, String hardwareModel, String deviceId, String algorithm, double hashrateHs,
                         double powerWatts, double hashesPerWatt, int observations, String minerSoftwareId) { }
    public record SamplePoint(int index, double hashrateHs, long powerWatts) { }
    public record LiveSeries(String deviceId, String hardwareType, String hardwareModel, String algorithm,
                             String minerSoftwareId, List<SamplePoint> points) { }
    public record AutoStartResult(String deviceId, String status, String message) { }
    public record Session(boolean running, String mode, String phase, Instant startedAt, Instant phaseEndsAt, Instant totalEndsAt,
                          int phaseIndex, int phaseCount, List<Result> results, Long secondsRemaining,
                          List<String> selectedWorkers, List<LiveSeries> liveSeries,
                          boolean autoStartRequested, List<AutoStartResult> autoStartResults) {
        public Session(boolean running, String mode, String phase, Instant startedAt, Instant phaseEndsAt,
                       Instant totalEndsAt, int phaseIndex, int phaseCount, List<Result> results, Long secondsRemaining) {
            this(running, mode, phase, startedAt, phaseEndsAt, totalEndsAt, phaseIndex, phaseCount, results,
                    secondsRemaining, List.of(), List.of(), false, List.of());
        }
        static Session idle() { return new Session(false, "", "Idle", null, null, null, 0, 0, List.of(), 0L); }
    }

    public static final class BenchmarkRunningException extends RuntimeException {
        public BenchmarkRunningException() { super("Node controls are temporarily locked while a benchmark is running"); }
    }
}
