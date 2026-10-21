package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.miner.MinerNativeTuning;
import de.verdox.solarminer.pcagent.mining.MiningService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** On-demand, per-GPU Gaussian-process calibration against live miner telemetry. */
@Service
public class AutoTuningSessionService {
    private de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService workerPolicies;
    @org.springframework.beans.factory.annotation.Autowired
    public void setWorkerPolicies(de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService policies) {
        this.workerPolicies = policies;
    }
    /** Broad search, bounded refinement, then efficiency and throughput confirmations per safe power tier. */
    private static final int BASE_CANDIDATES = 18, MAX_SEARCH_CANDIDATES = 24, MAX_CANDIDATES = 33,
            SAMPLES_PER_CANDIDATE = 16, CONFIRMATION_SAMPLES = 32, MAX_RUN_MINUTES = 60;
    private static final double REFINEMENT_ADVANTAGE = 1.05;
    // Driver-reported offset limits are writable bounds, not stability evidence.
    private static final int MAX_UNVERIFIED_MEMORY_UNDERCLOCK_MHZ = 1500;
    private static final int MAX_UNVERIFIED_MEMORY_OVERCLOCK_MHZ = 100;

    private final MiningService mining;
    private final LocalGpuPowerService power;
    private final GpuTuningService tuning;
    private final AutoTuningProfileStore profiles;
    private final AutoTuningSelectionStore selections;
    private final LocalRunLock runLock;
    private final MinerFactory miners;
    // Planning estimate: full search/confirmation budget plus driver/miner startup per window.
    long estimateRemainingSeconds(int candidateIndex, int sampleIndex, int sampleCount) {
        return estimateRemainingSeconds(candidateIndex, sampleIndex, sampleCount, sampleIntervalMillis);
    }

    static long estimateRemainingSeconds(int candidateIndex, int sampleIndex, int sampleCount, long intervalMillis) {
        int completed = Math.max(0, candidateIndex - 1);
        boolean confirming = sampleCount == CONFIRMATION_SAMPLES;
        int search = confirming ? 0 : Math.max(0, MAX_SEARCH_CANDIDATES - completed);
        int confirmations = confirming ? Math.min(MAX_CANDIDATES - MAX_SEARCH_CANDIDATES, Math.max(1, MAX_CANDIDATES - completed))
                : MAX_CANDIDATES - MAX_SEARCH_CANDIDATES;
        long samples = (long) search * SAMPLES_PER_CANDIDATE + (long) confirmations * CONFIRMATION_SAMPLES;
        samples -= Math.min(Math.max(0, sampleIndex), Math.max(0, sampleCount));
        return Math.max(0, samples) * intervalMillis / 1_000 + (search + confirmations) * 45L;
    }

    private double temperatureCeiling(String deviceId) {
        return workerPolicies == null ? maxTemperatureC : workerPolicies.ceiling(deviceId, maxTemperatureC);
    }
    private final double maxTemperatureC;
    private final long sampleIntervalMillis;
    private de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService controls;
    private de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService modes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModeDependencies(de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService controls,
                                    de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService modes) {
        this.controls = controls;
        this.modes = modes;
    }
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AutoTuningConcurrencyStore concurrency;
    private final Deque<QueuedWorker> queuedWorkers = new ArrayDeque<>();
    private final Set<String> awaitingWorkers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> awaitingRecovery = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> unsafeDevices = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, RunState> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Session> latest = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AutoTuningSessionService(MiningService mining, LocalGpuPowerService power, GpuTuningService tuning,
                                   AutoTuningProfileStore profiles, AutoTuningSelectionStore selections,
                                   LocalRunLock runLock, MinerFactory miners,
                                   AutoTuningConcurrencyStore concurrency,
                                   @Value("${solarminer.agent.auto-tuning.max-temperature-c:85}") double maxTemperatureC) {
        this(mining, power, tuning, profiles, selections, runLock, miners, concurrency, maxTemperatureC, 5_000);
    }

    AutoTuningSessionService(MiningService mining, LocalGpuPowerService power, GpuTuningService tuning,
                             AutoTuningProfileStore profiles, AutoTuningSelectionStore selections,
                             LocalRunLock runLock, MinerFactory miners,
                             AutoTuningConcurrencyStore concurrency,
                             double maxTemperatureC, long sampleIntervalMillis) {
        this.mining = mining; this.power = power; this.tuning = tuning; this.profiles = profiles;
        this.selections = selections;
        this.runLock = runLock; this.miners = miners;
        this.concurrency = concurrency;
        this.maxTemperatureC = Double.isFinite(maxTemperatureC)
                ? Math.max(65, Math.min(90, maxTemperatureC)) : 85;
        this.sampleIntervalMillis = sampleIntervalMillis;
    }

    AutoTuningSessionService(MiningService mining, LocalGpuPowerService power, GpuTuningService tuning,
                             AutoTuningProfileStore profiles, LocalRunLock runLock, MinerFactory miners,
                             double maxTemperatureC, long sampleIntervalMillis) {
        this(mining, power, tuning, profiles, new AutoTuningSelectionStore(), runLock, miners, new AutoTuningConcurrencyStore(),
                maxTemperatureC, sampleIntervalMillis);
    }

    AutoTuningSessionService(MiningService mining, LocalGpuPowerService power, GpuTuningService tuning,
                             AutoTuningProfileStore profiles, AutoTuningSelectionStore selections,
                             LocalRunLock runLock, MinerFactory miners,
                             double maxTemperatureC, long sampleIntervalMillis) {
        this(mining, power, tuning, profiles, selections, runLock, miners, new AutoTuningConcurrencyStore(),
                maxTemperatureC, sampleIntervalMillis);
    }

    public synchronized Session start(String deviceId, String coinId) {
        RunState state = prepare(deviceId, coinId, null, false);
        try { executor.execute(state.task); }
        catch (RuntimeException rejected) {
            active.remove(deviceId, state); latest.remove(deviceId);
            runLock.endAutoTuning();
            throw new IllegalStateException("auto_tuning_busy", rejected);
        }
        return state.session;
    }

    /** Synchronous refinement inside an already exclusive sweep, with no unlock/start gap. */
    Session refineWithinSweep(String deviceId, String coinId, int seedWatts,
                              java.util.function.BooleanSupplier cancelled) {
        return refineWithinSweep(deviceId, coinId, seedWatts, null, cancelled);
    }

    Session refineWithinSweep(String deviceId, String coinId, GpuEfficiencyStore.Profile curve,
                              java.util.function.BooleanSupplier cancelled) {
        return refineWithinSweep(deviceId, coinId, null, curve, cancelled);
    }

    private Session refineWithinSweep(String deviceId, String coinId, Integer seedWatts, GpuEfficiencyStore.Profile curve,
                                      java.util.function.BooleanSupplier cancelled) {
        if (!"efficiency-sweep".equals(runLock.holder())) throw new IllegalStateException("power_mode_busy");
        RunState state = prepare(deviceId, coinId, seedWatts, true, curve);
        state.externalCancel = cancelled;
        try { state.task.run(); }
        catch (RuntimeException recoveryFailure) {
            // A throwing recovery adapter must not leave a finished borrowed task marked running.
            unsafeDevices.add(deviceId);
            update(state, state.session.failed("gpu_clock_restore_failed"));
        }
        return state.session;
    }

    Session sessionForDevice(String deviceId) { return latest.get(deviceId); }

    private RunState prepare(String deviceId, String coinId, Integer seedWatts, boolean ownedBySweep) {
        return prepare(deviceId, coinId, seedWatts, ownedBySweep, null);
    }

    private synchronized RunState prepare(String deviceId, String coinId, Integer seedWatts, boolean ownedBySweep,
                                           GpuEfficiencyStore.Profile curve) {
        if (active.containsKey(deviceId)) throw new IllegalStateException("auto_tuning_busy");
        if (active.size() >= concurrency.get()) throw new IllegalStateException("auto_tuning_capacity_reached");
        Coin coin = Coin.byIdOrNull(coinId);
        if (coin == null || !coin.isGpu()) throw new IllegalArgumentException("auto_tuning_requires_gpu_coin");
        LocalGpuPowerService.Gpu gpu = power.refresh().stream().filter(value -> value.deviceId().equals(deviceId))
                .map(value -> ownedBySweep ? sweepBoundedGpu(value) : value).findFirst().orElse(null);
        if (gpu == null) throw new IllegalArgumentException("gpu_not_found");
        if (unsafeDevices.contains(deviceId)) throw new IllegalStateException("auto_tuning_gpu_requires_attention");
        if (workerPolicies != null && workerPolicies.get(deviceId) != null
                && !workerPolicies.temperatureSafe(deviceId, power.readTemperatureC(gpu), maxTemperatureC))
            throw new IllegalStateException("auto_tuning_temperature_limit");
        if (tuning.hasPendingRecovery(deviceId)) throw new IllegalStateException("gpu_clock_restore_failed");
        if (!gpu.supportsDynamicPowerScaling() || gpu.currentPowerLimitWatts() == null)
            throw new IllegalStateException("gpu_power_control_unavailable");
        if (gpu.safeTuningMaxWatts() == null)
            throw new IllegalStateException("auto_tuning_driver_default_power_unavailable");
        SweepTuningPlan powerPlan = curve == null ? null : SweepTuningPlan.from(curve, gpu.minWatts(),
                gpu.safeTuningMaxWatts(), temperatureCeiling(deviceId));
        int startingWatts = powerPlan != null ? powerPlan.anchors().getFirst()
                : seedWatts == null ? power.configuredPowerLimitWatts(gpu) : seedWatts;
        if (startingWatts < gpu.minWatts() || startingWatts > gpu.safeTuningMaxWatts())
            throw new IllegalStateException("auto_tuning_start_target_unsafe");
        if (gpu.currentPowerLimitWatts() > gpu.safeTuningMaxWatts())
            throw new IllegalStateException("auto_tuning_current_target_unsafe");
        MinerStats.Worker worker = worker(deviceId);
        if (worker == null || worker.miningStatus() != MinerStats.MinerStatus.MINING)
            throw new IllegalStateException("auto_tuning_requires_active_worker");
        if (!coin.matchesAlgorithm(worker.currentAlgorithm()))
            throw new IllegalStateException("auto_tuning_requires_matching_algorithm");
        GpuTuningService.Capabilities caps = tuning.refreshCapabilities(deviceId);
        AutoTuningSelectionStore.Selection selection = selections.get(deviceId);
        if (ownedBySweep) selection = new AutoTuningSelectionStore.Selection(caps.coreClockControl(),
                caps.memoryClockControl() || tuning.memoryOffsetRange(deviceId) != null, true,
                AutoTuningSelectionStore.Goal.EFFICIENCY);
        GpuTuningService.ClockTelemetry observedClocks = "NVIDIA".equalsIgnoreCase(gpu.vendor())
                ? tuning.readEffectiveClocks(deviceId) : null;
        if ((selection.core() || selection.memory()) && "NVIDIA".equalsIgnoreCase(gpu.vendor())
                && observedClocks == null)
            throw new IllegalStateException("auto_tuning_clock_readback_unavailable");
        String software = miners.minerSoftwareForWorker(coin, deviceId);
        if (software == null || software.isBlank())
            throw new IllegalStateException("auto_tuning_miner_unavailable");
        if (workerPolicies != null && !workerPolicies.measures(deviceId, coin.id(), software))
            throw new IllegalArgumentException("benchmark_combination_invalid");
        if (curve != null && (!deviceId.equals(curve.deviceId()) || !coin.id().equals(curve.coin())
                || !coin.matchesAlgorithm(curve.algorithm())
                || curve.minerSoftwareId() != null && !curve.minerSoftwareId().equals(software)))
            throw new IllegalArgumentException("benchmark_combination_invalid");
        String softwareVersion = miners.minerVersionForWorker(coin, deviceId);
        MinerNativeTuning nativeTuning = miners.gpuMinerForWorker(coin.id(), deviceId)
                .filter(MinerNativeTuning.class::isInstance).map(MinerNativeTuning.class::cast).orElse(null);
        boolean probeFixedMemoryWithoutDriverLock = nativeTuning != null
                && nativeTuning.mayProbeFixedMemoryClockWithoutDriverLock()
                && "NVIDIA".equalsIgnoreCase(gpu.vendor());
        GpuTuningService.MemoryOffsetRange offsetRange = tuning.memoryOffsetRange(deviceId);
        if (offsetRange == null && "NVIDIA".equalsIgnoreCase(gpu.vendor())
                && gpu.model() != null && gpu.model().matches("(?i).*RTX\\s*20[0-9]{2}.*"))
            caps = new GpuTuningService.Capabilities(caps.deviceId(), caps.coreClockControl(), false,
                    caps.voltageControl(), caps.coreMinMhz(), caps.coreMaxMhz(), caps.memoryMinMhz(),
                    caps.memoryMaxMhz(), caps.reason(), caps.supportedPairs());
        // A missing memory control must not prevent a core/power calibration. This is common
        // on RTX 20 cards before a memory-offset API is verified; SRBMiner's own adapter can
        // later expose memory control without changing this generic safety rule.
        if (selection.core() && !caps.coreClockControl() || !selection.core() && selection.memory()
                && !caps.memoryClockControl() && offsetRange == null)
            throw new IllegalStateException("auto_tuning_selected_control_unavailable");
        List<BayesianGpuOptimizer.Candidate> candidates = candidates(gpu, caps, observedClocks,
                startingWatts, offsetRange, selection, probeFixedMemoryWithoutDriverLock,
                powerPlan == null ? null : powerPlan.anchors());
        if (candidates.isEmpty()) throw new IllegalStateException("auto_tuning_no_safe_clock_candidates");
        if (selection.power() && candidates.stream().map(BayesianGpuOptimizer.Candidate::powerWatts).distinct().count() < 3)
            throw new IllegalStateException("auto_tuning_power_range_too_narrow");
        int tierCount = (int) candidates.stream().map(BayesianGpuOptimizer.Candidate::powerWatts).distinct().count();
        int candidatesPerTier = Math.max(1, BASE_CANDIDATES / tierCount);
        int plannedCandidates = candidates.stream().collect(java.util.stream.Collectors.groupingBy(
                BayesianGpuOptimizer.Candidate::powerWatts)).values().stream()
                .mapToInt(rows -> Math.min(candidatesPerTier, rows.size()) + Math.min(3, rows.size())).sum();
        if (!ownedBySweep && !runLock.tryBeginAutoTuning()) throw new IllegalStateException("power_mode_busy");
        RunState state = new RunState(new Session(true, "auto_tuning_phase_prepare", Instant.now(), deviceId, gpu.model(), coin.id(),
                coin.assignmentAlgorithm(), software, 0, plannedCandidates, null, null, null, 0, SAMPLES_PER_CANDIDATE, List.of(), null));
        active.put(deviceId, state);
        latest.put(deviceId, state.session);
        state.ownedBySweep = ownedBySweep;
        state.seedWatts = startingWatts;
        state.powerPlan = powerPlan;
        GpuTuningService.Capabilities runCaps = caps;
        AutoTuningSelectionStore.Selection runSelection = selection;
        state.task = () -> run(state, gpu, coin, software, softwareVersion, runCaps, observedClocks,
                offsetRange, runSelection, probeFixedMemoryWithoutDriverLock, candidates);
        return state;
    }

    private static LocalGpuPowerService.Gpu sweepBoundedGpu(LocalGpuPowerService.Gpu gpu) {
        return new LocalGpuPowerService.Gpu(gpu.vendor(), gpu.index(), gpu.deviceId(), gpu.model(), gpu.driverMinWatts(),
                gpu.driverMaxWatts(), gpu.minWatts(), gpu.maxWatts(), gpu.currentPowerLimitWatts(), gpu.currentWatts(),
                gpu.usageMeasurement(), gpu.supportsDynamicPowerScaling(), gpu.regulationError(),
                gpu.driverDefaultWatts() == null ? null : Math.min(gpu.driverDefaultWatts(), gpu.maxWatts()));
    }

    /** Called by the operating mode after miner startup; the miner may need time to report MINING. */
    public synchronized void startWhenReady(String deviceId, String coinId) {
        if (deviceId == null || coinId == null || active.containsKey(deviceId)
                || awaitingWorkers.contains(deviceId)
                || queuedWorkers.stream().anyMatch(row -> row.deviceId().equals(deviceId))) return;
        queuedWorkers.addLast(new QueuedWorker(deviceId, coinId));
        startNextQueuedWorker();
    }

    private synchronized void startNextQueuedWorker() {
        while (active.size() + awaitingWorkers.size() < concurrency.get() && awaitingRecovery.isEmpty() && !queuedWorkers.isEmpty()) {
            QueuedWorker request = queuedWorkers.removeFirst();
            if (!awaitingWorkers.add(request.deviceId())) continue;
            try { executor.execute(() -> {
                boolean started = false;
                try {
                    for (int attempt = 0; attempt < 30; attempt++) {
                        if (controls == null || !"AUTO_TUNING".equals(controls.get().powerModeFor(request.deviceId()))) return;
                        MinerStats.Worker active = worker(request.deviceId());
                        if (active != null
                                && active.miningStatus() == MinerStats.MinerStatus.MINING) {
                            start(request.deviceId(), request.coinId());
                            started = true;
                            return;
                        }
                        Thread.sleep(1_000);
                    }
                    if (controls != null
                            && "AUTO_TUNING".equals(controls.get().powerModeFor(request.deviceId()))) {
                        latest.put(request.deviceId(), Session.idle().failed("auto_tuning_requires_active_worker"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException failure) {
                    String reason = Objects.toString(failure.getMessage(), "auto_tuning_failed");
                    if (("power_mode_busy".equals(reason) || "auto_tuning_busy".equals(reason))
                            && controls != null && "AUTO_TUNING".equals(controls.get().powerModeFor(request.deviceId())))
                        synchronized (AutoTuningSessionService.this) { queuedWorkers.addFirst(request); }
                    else latest.put(request.deviceId(), Session.idle().failed(reason));
                } finally {
                    awaitingWorkers.remove(request.deviceId());
                    if (!started) startNextQueuedWorker();
                }
            });
            } catch (RuntimeException rejected) {
                awaitingWorkers.remove(request.deviceId());
                queuedWorkers.addFirst(request);
                return;
            }
        }
    }

    public Session status() { return sessions().stream().max(Comparator.comparing(row -> Objects.requireNonNullElse(row.startedAt(), Instant.EPOCH))).orElse(Session.idle()); }
    public List<Session> sessions() { return latest.values().stream().sorted(Comparator.comparing(row -> Objects.requireNonNullElse(row.startedAt(), Instant.EPOCH))).toList(); }
    public int maxConcurrent() { return concurrency.get(); }
    public synchronized boolean setMaxConcurrent(int value) { boolean changed = concurrency.set(value); if (changed) startNextQueuedWorker(); return changed; }
    public synchronized Session cancel() { active.values().forEach(state -> state.cancel.set(true)); return status(); }
    public synchronized boolean clearCompletedSession(String deviceId) {
        if (deviceId == null || active.containsKey(deviceId)) return false;
        latest.remove(deviceId);
        return true;
    }
    public void cancelForDevice(String deviceId) {
        if (deviceId == null) return;
        synchronized (this) { queuedWorkers.removeIf(row -> row.deviceId().equals(deviceId)); }
        RunState state = active.get(deviceId);
        if (state == null && !awaitingWorkers.contains(deviceId)) return;
        awaitingRecovery.add(deviceId);
        if (state != null) state.cancel.set(true);
        // A worker stop must not race a final candidate write. Let the tuning thread run its
        // recovery path before the caller stops the miner or applies another power mode.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while ((active.containsKey(deviceId) || awaitingWorkers.contains(deviceId)) && System.nanoTime() < deadline) {
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        if (active.containsKey(deviceId) || awaitingWorkers.contains(deviceId)) {
            awaitingRecovery.remove(deviceId);
            throw new IllegalStateException("auto_tuning_stop_recovery_timeout");
        }
        try {
            if (!tuning.restoreDefaults(deviceId).applied())
                throw new IllegalStateException("gpu_clock_restore_failed");
        } finally {
            awaitingRecovery.remove(deviceId);
            startNextQueuedWorker();
        }
    }

    @PreDestroy
    public void shutdown() {
        active.values().forEach(state -> state.cancel.set(true));
        executor.shutdownNow();
        try { executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void run(RunState state, LocalGpuPowerService.Gpu gpu, Coin coin, String software, String softwareVersion,
                     GpuTuningService.Capabilities caps, GpuTuningService.ClockTelemetry observedClocks,
                     GpuTuningService.MemoryOffsetRange offsetBaseline,
                     AutoTuningSelectionStore.Selection selection,
                     boolean probeFixedMemoryWithoutDriverLock,
                     List<BayesianGpuOptimizer.Candidate> candidates) {
        RunContext context = new RunContext(UUID.randomUUID().toString(), gpu, coin, software, softwareVersion);
        int originalPower = gpu.currentPowerLimitWatts();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(MAX_RUN_MINUTES);
        List<BayesianGpuOptimizer.Observation> observations = new ArrayList<>();
        List<BayesianGpuOptimizer.Candidate> activeCandidates = candidates;
        int measuredCandidates = 0;
        try {
            update(state, state.session.withPhase("auto_tuning_phase_sampling"));
            int startingWatts = state.seedWatts;
            List<Integer> tiers = candidates.stream().map(BayesianGpuOptimizer.Candidate::powerWatts).distinct()
                    .sorted(Comparator.comparingInt((Integer watts) -> Math.abs(watts - startingWatts))
                            .thenComparingInt(Integer::intValue)).toList();
            if (tiers.isEmpty() || selection.power() && tiers.size() < 3)
                throw new IllegalStateException("auto_tuning_power_range_too_narrow");
            int candidatesPerTier = Math.max(1, BASE_CANDIDATES / tiers.size());
            List<BayesianGpuOptimizer.Observation> historical = historicalObservations(context, activeCandidates);
            Deque<BayesianGpuOptimizer.Candidate> finalists = null;
            Deque<BayesianGpuOptimizer.Candidate> powerNeighbors = null;
            for (int index = 0; index < MAX_CANDIDATES; index++) {
                if (state.cancelled()) break;
                if (System.nanoTime() >= deadline) throw new IllegalStateException("auto_tuning_time_limit");
                boolean localRefinement = index >= BASE_CANDIDATES;
                List<BayesianGpuOptimizer.Observation> efficientRegions = localRefinement
                        ? promisingEfficientRegions(observations) : List.of();
                // Reserve the final windows plus driver/restart overhead before extending search.
                long confirmationReserve = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                        tiers.size() * 3L * CONFIRMATION_SAMPLES * sampleIntervalMillis)
                        + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
                if (state.powerPlan != null && localRefinement && powerNeighbors == null) {
                    powerNeighbors = new ArrayDeque<>(state.powerPlan.neighbors(observations, activeCandidates, gpu.minWatts(), gpu.safeTuningMaxWatts()));
                    Set<BayesianGpuOptimizer.Candidate> expanded = new LinkedHashSet<>(activeCandidates);
                    expanded.addAll(powerNeighbors);
                    activeCandidates = List.copyOf(expanded);
                }
                boolean beginConfirmation = index >= MAX_SEARCH_CANDIDATES || localRefinement
                        && (deadline - System.nanoTime() <= confirmationReserve
                        || (state.powerPlan != null ? powerNeighbors.isEmpty() : efficientRegions.isEmpty()));
                if (finalists == null && beginConfirmation) {
                    finalists = new ArrayDeque<>(state.powerPlan == null ? confirmationCandidates(observations, activeCandidates)
                            : state.powerPlan.finalists(observations, activeCandidates));
                    update(state, state.session.withCandidateCount(measuredCandidates + finalists.size()));
                }
                boolean confirming = finalists != null;
                boolean checkingPower = !confirming && state.powerPlan != null && localRefinement;
                BayesianGpuOptimizer.Candidate candidate;
                if (confirming) {
                    candidate = finalists.pollFirst();
                    if (candidate == null) break;
                } else if (checkingPower) {
                    update(state, state.session.withCandidateCount(MAX_CANDIDATES));
                    candidate = powerNeighbors.removeFirst();
                } else {
                    if (localRefinement)
                        update(state, state.session.withCandidateCount(MAX_CANDIDATES));
                    int tierWatts = localRefinement
                            ? efficientRegions.get((index - BASE_CANDIDATES) % efficientRegions.size()).candidate().powerWatts()
                            : tiers.get(Math.min(index / candidatesPerTier, tiers.size() - 1));
                    List<BayesianGpuOptimizer.Candidate> tierCandidates = activeCandidates.stream()
                            .filter(row -> row.powerWatts() == tierWatts).toList();
                    List<BayesianGpuOptimizer.Observation> tierObservations = observations.stream()
                            .filter(row -> row.candidate().powerWatts() == tierWatts).toList();
                    List<BayesianGpuOptimizer.Observation> training = new ArrayList<>(historical.stream()
                            .filter(row -> row.candidate().powerWatts() == tierWatts
                                    && tierObservations.stream().noneMatch(current -> current.candidate().equals(row.candidate())))
                            .toList());
                    training.addAll(tierObservations);
                    candidate = BayesianGpuOptimizer.next(tierCandidates, training, tierObservations);
                    int positionInTier = index % candidatesPerTier;
                    boolean hasOffsetCandidates = tierCandidates.stream().anyMatch(row -> row.memoryOffsetMhz() != null);
                    if (!localRefinement && positionInTier <= 1 && hasOffsetCandidates)
                        candidate = memoryBoundaryCandidate(tierCandidates, candidate, true, positionInTier == 0);
                    else if (!localRefinement && positionInTier <= 1 && selection.memory() && caps.memoryClockControl())
                        candidate = memoryBoundaryCandidate(tierCandidates, candidate, false, positionInTier == 0);
                    // Boundary anchors may collapse onto the same setting on a narrow driver grid.
                    if (candidate == null || tierObservations.stream().map(BayesianGpuOptimizer.Observation::candidate)
                            .toList().contains(candidate))
                        candidate = BayesianGpuOptimizer.next(tierCandidates, training, tierObservations);
                    if (candidate == null) continue;
                }
                int sampleCount = confirming ? CONFIRMATION_SAMPLES : SAMPLES_PER_CANDIDATE;
                String resultType = confirming ? "RESULT" : "SEARCH_RESULT";
                update(state, state.session.withCandidate(candidate, ++measuredCandidates, sampleCount, confirming));
                if (checkingPower) update(state, state.session.withPhase("auto_tuning_phase_power_refining"));
                MinerNativeTuning nativeTuning = miners.gpuMinerForWorker(coin.id(), gpu.deviceId())
                        .filter(MinerNativeTuning.class::isInstance).map(MinerNativeTuning.class::cast).orElse(null);
                boolean useMinerCommands = nativeTuning != null && "NVIDIA".equalsIgnoreCase(gpu.vendor());
                AutoTuningProfileStore.Profile commandProfile = new AutoTuningProfileStore.Profile(gpu.deviceId(), gpu.model(),
                        gpu.vendor(), coin.id(), coin.assignmentAlgorithm(), software, softwareVersion,
                        candidate.coreMhz(), candidate.memoryMhz(), candidate.powerWatts(), 1, 1, 1, 1, Instant.now(),
                        candidate.memoryOffsetMhz(), candidate.memoryOffsetPstate());
                if (state.ownedBySweep && !power.setSweepPowerTarget(candidate.powerWatts(), gpu))
                    throw new IllegalStateException("power_mode_apply_failed");
                GpuTuningService.ApplyResult clockWrite = useMinerCommands
                        ? new GpuTuningService.ApplyResult(nativeTuning.applyNativeTuning(gpu, commandProfile),
                        "miner_native_tuning_failed")
                        : !selection.core() && !selection.memory()
                        ? new GpuTuningService.ApplyResult(true, null)
                        : candidate.memoryOffsetMhz() == null
                        ? tuning.apply(gpu.deviceId(), candidate.coreMhz(), candidate.coreMhz(),
                        candidate.memoryMhz(), candidate.memoryMhz())
                        : tuning.apply(gpu.deviceId(), candidate.coreMhz(), candidate.coreMhz(),
                        null, null, candidate.memoryOffsetMhz(), candidate.memoryOffsetPstate());
                boolean capApplied = clockWrite.applied() && (useMinerCommands
                        || power.setAutoTuningPowerTarget(candidate.powerWatts(), gpu));
                if (!capApplied) {
                    saveMeasurement(context, candidate, resultType, null, null, null, null, null,
                            0, null, null, false,
                            clockWrite.applied() ? "gpu_power_control_unavailable" : clockWrite.reason());
                    observations.add(new BayesianGpuOptimizer.Observation(candidate, 0, false,
                            clockWrite.applied() ? "gpu_power_control_unavailable" : clockWrite.reason()));
                    update(state, state.session.withObservations(observations));
                    if ("gpu_clock_restore_failed".equals(clockWrite.reason())
                            || !clockWrite.applied() && "NVIDIA".equalsIgnoreCase(gpu.vendor())
                            && tuning.readEffectiveClocks(gpu.deviceId()) == null)
                        throw new IllegalStateException("auto_tuning_gpu_unresponsive");
                    if (!clockWrite.applied() && candidate.memoryOffsetMhz() != null) {
                        if (!selection.core()) throw new IllegalStateException("auto_tuning_selected_control_unavailable");
                        if (!tuning.restoreDefaults(gpu.deviceId()).applied())
                            throw new IllegalStateException("gpu_clock_restore_failed");
                        GpuTuningService.Capabilities coreOnly = new GpuTuningService.Capabilities(caps.deviceId(),
                                caps.coreClockControl(), false, caps.voltageControl(), caps.coreMinMhz(), caps.coreMaxMhz(),
                                caps.memoryMinMhz(), caps.memoryMaxMhz(), caps.reason(), caps.supportedPairs());
                        activeCandidates = candidates(gpu, coreOnly, observedClocks, startingWatts,
                                null, selection, probeFixedMemoryWithoutDriverLock,
                                state.powerPlan == null ? null : state.powerPlan.anchors());
                        if (powerNeighbors != null) powerNeighbors.clear();
                        historical = historicalObservations(context, activeCandidates);
                        if (finalists != null) finalists.retainAll(activeCandidates);
                    }
                    continue;
                }
                if (useMinerCommands) {
                    String measurementPhase = state.session.phase();
                    update(state, state.session.withPhase("auto_tuning_phase_starting"));
                    awaitNativeCandidate(gpu, coin, state::cancelled, deadline);
                    update(state, state.session.withPhase(measurementPhase));
                }
                SampleSummary sample = collect(state, context, candidate, deadline, observedClocks, offsetBaseline, sampleCount);
                saveMeasurement(context, candidate, resultType, sample.samples() > 0 ? sample.hashrateHs() : null,
                        sample.samples() > 0 ? sample.watts() : null, sample.maxTemperatureC(),
                        sample.observedCoreMhz(), sample.observedMemoryMhz(), sample.samples(),
                        null, null, sample.stable(), sample.detail());
                observations.add(new BayesianGpuOptimizer.Observation(candidate,
                        sample.stable() ? sample.hashrateHs() / sample.watts() : 0,
                        sample.stable(), sample.detail(), sample));
                update(state, state.session.withObservations(observations));
                if ("auto_tuning_memory_lock_ineffective".equals(sample.detail())) {
                    if (!selection.core()) throw new IllegalStateException("auto_tuning_selected_control_unavailable");
                    if (!tuning.restoreDefaults(gpu.deviceId()).applied())
                        throw new IllegalStateException("gpu_clock_restore_failed");
                    GpuTuningService.Capabilities coreOnly = new GpuTuningService.Capabilities(caps.deviceId(),
                            caps.coreClockControl(), false, caps.voltageControl(), caps.coreMinMhz(), caps.coreMaxMhz(),
                            caps.memoryMinMhz(), caps.memoryMaxMhz(), caps.reason(), caps.supportedPairs());
                    activeCandidates = candidates(gpu, coreOnly, observedClocks, startingWatts,
                            null, selection, probeFixedMemoryWithoutDriverLock,
                            state.powerPlan == null ? null : state.powerPlan.anchors());
                    if (powerNeighbors != null) powerNeighbors.clear();
                    if (activeCandidates.isEmpty()) throw new IllegalStateException("auto_tuning_no_safe_clock_candidates");
                    historical = historicalObservations(context, activeCandidates);
                    if (finalists != null) finalists.retainAll(activeCandidates);
                }
                if (sample.driverFailure()) {
                    if (!"auto_tuning_worker_stopped".equals(sample.detail()) || !recoverWorker(state, gpu, coin, originalPower))
                        throw new IllegalStateException(sample.detail());
                }
            }
            if (state.cancelled()) throw new IllegalStateException("auto_tuning_cancelled");
            update(state, state.session.withCandidateCount(measuredCandidates));
            // Search windows are deliberately not operating profiles. Only independent,
            // longer confirmations from this run can satisfy tier completion and win.
            List<AutoTuningProfileStore.Profile> confirmed = profiles.measurements().stream()
                    .filter(row -> context.runId().equals(row.runId()))
                    .map(AutoTuningProfileStore.Measurement::stableProfile).filter(Objects::nonNull).toList();
            for (int tierWatts : tiers) {
                if (confirmed.stream().noneMatch(row -> row.powerLimitWatts() == tierWatts))
                    throw new IllegalStateException("auto_tuning_no_confirmed_tier");
            }
            AutoTuningProfileStore.Profile profile = confirmed.stream()
                    .max(Comparator.comparingDouble(AutoTuningProfileStore.Profile::efficiencyHashesPerWatt)).orElse(null);
            if (profile == null) throw new IllegalStateException("auto_tuning_profile_save_failed");
            boolean nativeProfile = miners.gpuMinerForWorker(coin.id(), gpu.deviceId())
                    .filter(MinerNativeTuning.class::isInstance).isPresent()
                    && "NVIDIA".equalsIgnoreCase(gpu.vendor());
            if ((!nativeProfile && !tuning.restoreDefaults(gpu.deviceId()).applied())
                    || (!nativeProfile && !power.setAutoTuningPowerTarget(originalPower, gpu)))
                throw new IllegalStateException("gpu_clock_restore_failed");
            if (!state.ownedBySweep && selection.core() && selection.memory() && selection.power()
                    && controls != null && modes != null
                    && "AUTO_TUNING".equals(controls.get().powerModeFor(gpu.deviceId()))) {
                var applied = modes.apply(gpu.deviceId(), coin.id());
                if (applied.error() != null) throw new IllegalStateException(applied.error());
            }
            update(state, state.session.completed(profile, "auto_tuning_complete"));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            String reason = Objects.toString(failure.getMessage(), "auto_tuning_failed");
            boolean gpuFailure = "auto_tuning_gpu_unresponsive".equals(reason)
                    || "gpu_clock_restore_failed".equals(reason);
            boolean thermalFailure = "auto_tuning_temperature_limit".equals(reason)
                    || "auto_tuning_temperature_unavailable".equals(reason);
            if (gpuFailure || thermalFailure) unsafeDevices.add(gpu.deviceId());
            if (gpuFailure || thermalFailure) {
                try {
                    miners.gpuMinerForWorker(coin.id(), gpu.deviceId()).ifPresent(miner ->
                            miner.pauseGpu(gpu.vendor(), gpu.index()));
                } catch (RuntimeException ignored) { /* Still attempt driver and power restoration. */ }
            }
            boolean gpuReset = !gpuFailure || !"NVIDIA".equalsIgnoreCase(gpu.vendor())
                    || tuning.resetUnresponsiveGpu(gpu.deviceId()).applied();
            var recoveringMiner = miners.gpuMinerForWorker(coin.id(), gpu.deviceId());
            boolean stoppedForSweep = !state.ownedBySweep || recoveringMiner.isEmpty()
                    || recoveringMiner.get().stopGpu(gpu.vendor(), gpu.index());
            if (stoppedForSweep) recoveringMiner
                    .filter(MinerNativeTuning.class::isInstance).map(MinerNativeTuning.class::cast)
                    .ifPresent(nativeTuning -> nativeTuning.clearNativeTuning(gpu));
            boolean clocksRestored = tuning.restoreDefaults(gpu.deviceId()).applied();
            boolean powerRestored = power.setAutoTuningPowerTarget(originalPower, gpu);
            update(state, state.session.failed(!stoppedForSweep ? "auto_tuning_gpu_requires_attention" : !gpuReset ? "auto_tuning_gpu_reset_failed"
                    : clocksRestored && powerRestored ? reason : "gpu_clock_restore_failed"));
        } finally {
            active.remove(gpu.deviceId(), state);
            latest.put(gpu.deviceId(), state.session);
            if (!state.ownedBySweep) { runLock.endAutoTuning(); startNextQueuedWorker(); }
        }
    }

    private boolean recoverWorker(RunState state, LocalGpuPowerService.Gpu gpu, Coin coin, int originalPower) throws InterruptedException {
        if (!tuning.restoreDefaults(gpu.deviceId()).applied()
                || !power.setAutoTuningPowerTarget(originalPower, gpu)) return false;
        for (int attempt = 0; attempt < 3 && !state.cancelled(); attempt++) {
            Thread.sleep(5_000);
            if (miners.gpuMinerForWorker(coin.id(), gpu.deviceId()).orElseThrow()
                    .resumeGpu(gpu.vendor(), gpu.index())) return true;
        }
        return false;
    }

    /** Native clock commands can restart the process; startup is not a failed sample. */
    void awaitNativeCandidate(LocalGpuPowerService.Gpu gpu, Coin coin,
            java.util.function.BooleanSupplier cancelled, long runDeadline) throws InterruptedException {
        long startupDeadline = Math.min(runDeadline, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(240));
        while (!cancelled.getAsBoolean()) {
            Double temperature = power.readTemperatureC(gpu);
            if (temperature == null || !Double.isFinite(temperature) || temperature <= 0)
                throw new IllegalStateException("auto_tuning_temperature_unavailable");
            if (temperature >= temperatureCeiling(gpu.deviceId()))
                throw new IllegalStateException("auto_tuning_temperature_limit");
            MinerStats.Worker worker = worker(gpu.deviceId());
            if (worker != null && worker.miningStatus() == MinerStats.MinerStatus.MINING
                    && coin.matchesAlgorithm(worker.currentAlgorithm()) && SampleCollector.hasValidHashrate(worker)) return;
            if (worker != null && worker.miningStatus() == MinerStats.MinerStatus.ERROR)
                throw new IllegalStateException("auto_tuning_worker_stopped");
            if (System.nanoTime() >= startupDeadline)
                throw new IllegalStateException(System.nanoTime() >= runDeadline ? "auto_tuning_time_limit" : "auto_tuning_requires_active_worker");
            Thread.sleep(Math.min(500, sampleIntervalMillis));
        }
        throw new IllegalStateException("auto_tuning_cancelled");
    }

    private SampleSummary collect(RunState state, RunContext context, BayesianGpuOptimizer.Candidate candidate,
                                  long deadline, GpuTuningService.ClockTelemetry baselineClocks,
                                  GpuTuningService.MemoryOffsetRange offsetBaseline, int sampleCount) throws InterruptedException {
        List<Double> rates = new ArrayList<>(), watts = new ArrayList<>(), temps = new ArrayList<>();
        List<Integer> observedCore = new ArrayList<>(), observedMemory = new ArrayList<>();
        int consecutiveMemoryMismatches = 0;
        int consecutiveMissingClocks = 0;
        for (int i = 0; i < sampleCount; i++) {
            if (state.cancelled()) return SampleSummary.unstable("auto_tuning_cancelled", false);
            if (System.nanoTime() >= deadline) throw new IllegalStateException("auto_tuning_time_limit");
            Thread.sleep(sampleIntervalMillis);
            update(state, state.session.withSampleIndex(i + 1));
            MinerStats.Worker worker = worker(state.session.deviceId());
            if (worker == null || worker.miningStatus() != MinerStats.MinerStatus.MINING) {
                boolean gpuUnresponsive = "NVIDIA".equalsIgnoreCase(context.gpu().vendor())
                        && tuning.readEffectiveClocks(context.gpu().deviceId()) == null;
                saveMeasurement(context, candidate, "SAMPLE", null, null, null, null, null,
                        i + 1, null, null, null,
                        gpuUnresponsive ? "auto_tuning_gpu_unresponsive" : "auto_tuning_worker_stopped");
                return SampleSummary.unstable(gpuUnresponsive ? "auto_tuning_gpu_unresponsive"
                        : "auto_tuning_worker_stopped", true);
            }
            double rate = worker.terahashPerSecond() * 1e12;
            double powerWatts = worker.approximatedPowerUsageWatts();
            LocalGpuPowerService.Gpu gpu = power.snapshot().stream()
                    .filter(value -> state.session.deviceId().equals(value.deviceId())).findFirst().orElse(null);
            Double temp = gpu == null ? null : power.readTemperatureC(gpu);
            boolean validRate = Double.isFinite(rate) && rate > 0;
            boolean validPower = Double.isFinite(powerWatts) && powerWatts > 0;
            boolean validTemp = temp != null && Double.isFinite(temp) && temp > 0;
            boolean nvidia = "NVIDIA".equalsIgnoreCase(context.gpu().vendor());
            GpuTuningService.ClockTelemetry clocks = nvidia
                    ? tuning.readEffectiveClocks(context.gpu().deviceId()) : null;
            consecutiveMissingClocks = nvidia && clocks == null ? consecutiveMissingClocks + 1 : 0;
            boolean offsetNoEffect = clocks != null && candidate.memoryOffsetMhz() != null
                    && baselineClocks != null && offsetBaseline != null
                    && candidate.memoryOffsetPstate() == offsetBaseline.pstate()
                    && !candidate.memoryOffsetMhz().equals(offsetBaseline.currentMhz())
                    && Math.abs(clocks.memoryMhz() - baselineClocks.memoryMhz()) < 20;
            boolean memoryMismatch = clocks != null && (candidate.memoryMhz() != null
                    && Math.abs(clocks.memoryMhz() - candidate.memoryMhz()) > memoryTolerance(candidate.memoryMhz())
                    || candidate.memoryOffsetMhz() != null && !Objects.equals(candidate.memoryOffsetMhz(),
                    tuning.readMemoryOffset(context.gpu().deviceId(), candidate.memoryOffsetPstate()))
                    || offsetNoEffect);
            String issue = nvidia && !validTemp && clocks == null ? "auto_tuning_gpu_unresponsive"
                    : !validTemp ? "auto_tuning_temperature_unavailable"
                    : temp >= temperatureCeiling(state.session.deviceId()) ? "auto_tuning_temperature_limit"
                    : !validRate || !validPower ? "auto_tuning_sample_unavailable"
                    : nvidia && clocks == null
                    ? "auto_tuning_clock_readback_unavailable"
                    : memoryMismatch ? "auto_tuning_memory_lock_ineffective" : null;
            if (consecutiveMissingClocks >= 2) issue = "auto_tuning_gpu_unresponsive";
            saveMeasurement(context, candidate, "SAMPLE", validRate ? rate : null,
                    validPower ? powerWatts : null, validTemp ? temp : null,
                    clocks == null ? null : clocks.coreMhz(), clocks == null ? null : clocks.memoryMhz(),
                    i + 1, worker.acceptedShares(), worker.rejectedShares(), null, issue);
            if ("auto_tuning_gpu_unresponsive".equals(issue))
                return SampleSummary.unstable("auto_tuning_gpu_unresponsive", true);
            if (!validTemp)
                return SampleSummary.unstable("auto_tuning_temperature_unavailable", true);
            if (temp >= temperatureCeiling(state.session.deviceId()))
                return SampleSummary.unstable("auto_tuning_temperature_limit", true);
            if (!validRate || !validPower) continue;
            if (nvidia) {
                if (clocks == null) continue;
                observedCore.add(clocks.coreMhz()); observedMemory.add(clocks.memoryMhz());
            }
            rates.add(rate); watts.add(powerWatts); temps.add(temp);
            consecutiveMemoryMismatches = memoryMismatch ? consecutiveMemoryMismatches + 1 : 0;
            if (consecutiveMemoryMismatches >= 3)
                return summarize(rates, watts, temps, observedCore, observedMemory)
                        .withFailure("auto_tuning_memory_lock_ineffective");
        }
        if (rates.size() < sampleCount - sampleCount / 8) return SampleSummary.unstable("auto_tuning_insufficient_samples", false);
        SampleSummary summary = summarize(rates, watts, temps, observedCore, observedMemory);
        double median = summary.hashrateHs();
        if (rates.stream().filter(value -> value < median * .8 || value > median * 1.2).count() > sampleCount / 8)
            return summary.withFailure("auto_tuning_unstable_hashrate");
        if (!observedCore.isEmpty() && candidate.coreMhz() != null && observedCore.stream()
                .filter(value -> value > candidate.coreMhz() + Math.max(75, candidate.coreMhz() * .12))
                .count() > 2)
            return summary.withFailure("auto_tuning_clock_not_reached");
        if (!observedCore.isEmpty()) {
            double actualCoreMedian = SampleCollector.median(observedCore.stream().mapToDouble(Integer::doubleValue).toArray());
            if (observedCore.stream().filter(value -> Math.abs(value - actualCoreMedian)
                    > Math.max(75, actualCoreMedian * .12)).count() > 2)
                return summary.withFailure("auto_tuning_unstable_core_clock");
        }
        if (candidate.memoryMhz() != null && observedMemory.stream()
                .filter(value -> Math.abs(value - candidate.memoryMhz()) > memoryTolerance(candidate.memoryMhz()))
                .count() > 2)
            return summary.withFailure("auto_tuning_clock_not_reached");
        return summary;
    }

    private static double memoryTolerance(int targetMhz) { return Math.max(50, targetMhz * .01); }

    private static SampleSummary summarize(List<Double> rates, List<Double> watts, List<Double> temps,
                                           List<Integer> observedCore, List<Integer> observedMemory) {
        return new SampleSummary(true, SampleCollector.median(rates.stream().mapToDouble(Double::doubleValue).toArray()),
                watts.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                rates.size(), temps.stream().mapToDouble(Double::doubleValue).max().stream().boxed().findFirst().orElse(null),
                observedCore.isEmpty() ? null : (int) Math.round(observedCore.stream().mapToInt(Integer::intValue).average().orElse(0)),
                observedMemory.isEmpty() ? null : (int) Math.round(observedMemory.stream().mapToInt(Integer::intValue).average().orElse(0)),
                null, false);
    }

    private void saveMeasurement(RunContext context, BayesianGpuOptimizer.Candidate candidate, String type,
                                 Double hashrate, Double watts, Double temperature, Integer observedCore,
                                 Integer observedMemory, Integer validSamples, Long accepted, Long rejected,
                                 Boolean stable, String reason) {
        LocalGpuPowerService.Gpu gpu = context.gpu();
        Coin coin = context.coin();
        if (!profiles.appendMeasurement(new AutoTuningProfileStore.Measurement(context.runId(), Instant.now(),
                gpu.deviceId(), gpu.model(), gpu.vendor(), coin.id(), coin.assignmentAlgorithm(),
                context.software(), context.softwareVersion(), candidate.coreMhz(), candidate.memoryMhz(),
                candidate.powerWatts(), type, hashrate, watts, temperature, observedCore, observedMemory,
                validSamples, accepted, rejected, stable, reason,
                candidate.memoryOffsetMhz(), candidate.memoryOffsetPstate())))
            throw new IllegalStateException("auto_tuning_observation_save_failed");
    }

    private List<BayesianGpuOptimizer.Observation> historicalObservations(RunContext context,
                                                                           List<BayesianGpuOptimizer.Candidate> candidates) {
        LocalGpuPowerService.Gpu gpu = context.gpu();
        List<AutoTuningProfileStore.Profile> prior = profiles.candidates(gpu.deviceId(), context.coin().id(),
                context.software(), context.softwareVersion(), gpu.model(), gpu.vendor(),
                context.coin().assignmentAlgorithm());
        List<BayesianGpuOptimizer.Observation> observations = new ArrayList<>();
        for (BayesianGpuOptimizer.Candidate candidate : candidates) {
            double[] scores = prior.stream()
                    .filter(row -> row.powerLimitWatts() == candidate.powerWatts()
                            && Objects.equals(row.coreMhz(), candidate.coreMhz())
                            && Objects.equals(row.memoryMhz(), candidate.memoryMhz())
                            && Objects.equals(row.memoryOffsetMhz(), candidate.memoryOffsetMhz())
                            && Objects.equals(row.memoryOffsetPstate(), candidate.memoryOffsetPstate()))
                    .mapToDouble(AutoTuningProfileStore.Profile::efficiencyHashesPerWatt).toArray();
            if (scores.length > 0) observations.add(new BayesianGpuOptimizer.Observation(candidate,
                    SampleCollector.median(scores), true, null));
        }
        return observations;
    }

    private record RunContext(String runId, LocalGpuPowerService.Gpu gpu, Coin coin,
                              String software, String softwareVersion) { }

    private MinerStats.Worker worker(String deviceId) {
        return mining.getWorkerStats().stream().filter(value -> deviceId.equals(value.deviceId()))
                .sorted(Comparator.comparing(value -> value.miningStatus() != MinerStats.MinerStatus.MINING))
                .findFirst().orElse(null);
    }

    private void update(RunState state, Session next) {
        state.session = next;
        latest.put(next.deviceId(), next);
    }

    private static final class RunState {
        private final AtomicBoolean cancel = new AtomicBoolean();
        private volatile Session session;
        private boolean ownedBySweep;
        private int seedWatts;
        private SweepTuningPlan powerPlan;
        private Runnable task;
        private java.util.function.BooleanSupplier externalCancel = () -> false;
        private boolean cancelled() { return cancel.get() || externalCancel.getAsBoolean(); }
        private RunState(Session session) { this.session = session; }
    }

    static List<BayesianGpuOptimizer.Candidate> candidates(LocalGpuPowerService.Gpu gpu,
                                                            GpuTuningService.Capabilities caps,
                                                            GpuTuningService.ClockTelemetry observedClocks) {
        return candidates(gpu, caps, observedClocks, gpu.currentPowerLimitWatts() == null
                ? gpu.maxWatts() : gpu.currentPowerLimitWatts());
    }

    static List<BayesianGpuOptimizer.Candidate> candidates(LocalGpuPowerService.Gpu gpu,
                                                            GpuTuningService.Capabilities caps,
                                                            GpuTuningService.ClockTelemetry observedClocks,
                                                            int startWatts) {
        return candidates(gpu, caps, observedClocks, startWatts, null);
    }

    static List<BayesianGpuOptimizer.Candidate> candidates(LocalGpuPowerService.Gpu gpu,
                                                            GpuTuningService.Capabilities caps,
                                                            GpuTuningService.ClockTelemetry observedClocks,
                                                            int startWatts,
                                                            GpuTuningService.MemoryOffsetRange offsetRange) {
        return candidates(gpu, caps, observedClocks, startWatts, offsetRange,
                AutoTuningSelectionStore.Selection.DEFAULT, false);
    }

    static List<BayesianGpuOptimizer.Candidate> candidates(LocalGpuPowerService.Gpu gpu,
                                                            GpuTuningService.Capabilities caps,
                                                            GpuTuningService.ClockTelemetry observedClocks,
                                                            int startWatts,
                                                            GpuTuningService.MemoryOffsetRange offsetRange,
                                                            AutoTuningSelectionStore.Selection selection) {
        return candidates(gpu, caps, observedClocks, startWatts, offsetRange, selection, false);
    }

    static List<BayesianGpuOptimizer.Candidate> candidates(LocalGpuPowerService.Gpu gpu,
                                                            GpuTuningService.Capabilities caps,
                                                            GpuTuningService.ClockTelemetry observedClocks,
                                                            int startWatts,
                                                            GpuTuningService.MemoryOffsetRange offsetRange,
                                                            AutoTuningSelectionStore.Selection selection,
                                                            boolean probeFixedMemoryWithoutDriverLock) {
        return candidates(gpu, caps, observedClocks, startWatts, offsetRange, selection, probeFixedMemoryWithoutDriverLock, null);
    }

    private static List<BayesianGpuOptimizer.Candidate> candidates(LocalGpuPowerService.Gpu gpu,
            GpuTuningService.Capabilities caps, GpuTuningService.ClockTelemetry observedClocks, int startWatts,
            GpuTuningService.MemoryOffsetRange offsetRange, AutoTuningSelectionStore.Selection selection,
            boolean probeFixedMemoryWithoutDriverLock, List<Integer> measuredPowerTiers) {
        Integer safeMaxWatts = gpu.safeTuningMaxWatts();
        if (safeMaxWatts == null || safeMaxWatts < gpu.minWatts()) return List.of();
        List<Integer> cores = values(selection.core() && caps.coreClockControl(), caps.coreMinMhz(), caps.coreMaxMhz());
        List<Integer> memories = values(selection.memory() && caps.memoryClockControl(), caps.memoryMinMhz(), caps.memoryMaxMhz());
        if (startWatts < gpu.minWatts() || startWatts > safeMaxWatts) return List.of();
        List<Integer> power = selection.power() ? measuredPowerTiers == null
                ? powerTiers(gpu.minWatts(), startWatts, safeMaxWatts) : measuredPowerTiers : List.of(startWatts);
        if (power.isEmpty()) return List.of();
        Set<BayesianGpuOptimizer.Candidate> unique = new LinkedHashSet<>();
        if (!selection.core() && !selection.memory()) {
            for (int target : power) unique.add(new BayesianGpuOptimizer.Candidate(null, null, target, 0, 0,
                    selection.power() ? position(target, gpu.minWatts(), safeMaxWatts) : 0));
            return List.copyOf(unique);
        }
        boolean offsetCandidatesAdded = false;
        if (selection.memory() && offsetRange != null && observedClocks != null) {
            int low = Math.max(offsetRange.minMhz(), offsetRange.currentMhz() - MAX_UNVERIFIED_MEMORY_UNDERCLOCK_MHZ);
            int high = Math.min(offsetRange.maxMhz(), offsetRange.currentMhz() + MAX_UNVERIFIED_MEMORY_OVERCLOCK_MHZ);
            if (low <= high) {
                Set<Integer> offsetGrid = new LinkedHashSet<>();
                for (int step = 0; step <= 16; step++) offsetGrid.add(low + (high - low) * step / 16);
                offsetGrid.add(Math.clamp(0, low, high));
                List<Integer> offsets = List.copyOf(offsetGrid);
                List<Integer> coreTargets = selection.core() ? (probeFixedMemoryWithoutDriverLock
                        ? caps.supportedPairs().stream().map(GpuTuningService.ClockPair::coreMhz).distinct().toList()
                        : caps.supportedPairs().stream()
                        .filter(pair -> pair.memoryMhz() == observedClocks.memoryMhz())
                        .map(GpuTuningService.ClockPair::coreMhz).distinct()
                        .sorted(Comparator.comparingInt((Integer core) -> Math.abs(core - observedClocks.coreMhz())))
                        .toList()) : List.of(observedClocks.coreMhz());
                if (coreTargets.isEmpty() && selection.core()) coreTargets = cores;
                for (int target : power) for (int core : coreTargets) for (int offset : offsets)
                    unique.add(new BayesianGpuOptimizer.Candidate(selection.core() ? core : null, null, target,
                            selection.core() ? position(core, caps.coreMinMhz(), caps.coreMaxMhz()) : 0,
                            position(offset, low, high), position(target, gpu.minWatts(), safeMaxWatts),
                            offset, offsetRange.pstate()));
                offsetCandidatesAdded = !unique.isEmpty();
            }
        }
        if (offsetCandidatesAdded && !caps.memoryClockControl() && !probeFixedMemoryWithoutDriverLock)
            return List.copyOf(unique);
        if (!caps.supportedPairs().isEmpty()) {
            if (probeFixedMemoryWithoutDriverLock && selection.core() && selection.memory()) {
                List<Integer> listedCores = caps.supportedPairs().stream()
                        .map(GpuTuningService.ClockPair::coreMhz).distinct().toList();
                List<Integer> listedMemories = caps.supportedPairs().stream()
                        .map(GpuTuningService.ClockPair::memoryMhz).distinct().toList();
                for (int core : listedCores) for (int memory : listedMemories) for (int target : power)
                    unique.add(new BayesianGpuOptimizer.Candidate(core, memory, target,
                            position(core, caps.coreMinMhz(), caps.coreMaxMhz()),
                            position(memory, caps.memoryMinMhz(), caps.memoryMaxMhz()),
                            position(target, gpu.minWatts(), safeMaxWatts)));
            } else {
            int highestSupportedMemory = caps.supportedPairs().stream()
                    .mapToInt(GpuTuningService.ClockPair::memoryMhz)
                    .filter(value -> observedClocks == null || value <= observedClocks.memoryMhz())
                    .max().orElse(0);
            for (var pair : caps.supportedPairs()) for (int target : power) {
                if (!caps.memoryClockControl() && !probeFixedMemoryWithoutDriverLock
                        && pair.memoryMhz() != highestSupportedMemory) continue;
                if (!selection.core() && observedClocks != null
                        && Math.abs(pair.coreMhz() - observedClocks.coreMhz()) > 20) continue;
                if (!selection.memory() && observedClocks != null
                        && Math.abs(pair.memoryMhz() - observedClocks.memoryMhz()) > memoryTolerance(observedClocks.memoryMhz())) continue;
                Integer memory = selection.memory() && (caps.memoryClockControl() || probeFixedMemoryWithoutDriverLock)
                        ? pair.memoryMhz() : null;
                Integer core = selection.core() ? pair.coreMhz() : null;
                unique.add(new BayesianGpuOptimizer.Candidate(core, memory, target,
                        core == null ? 0 : position(core, caps.coreMinMhz(), caps.coreMaxMhz()),
                        memory == null ? 0 : position(memory, caps.memoryMinMhz(), caps.memoryMaxMhz()),
                        position(target, gpu.minWatts(), safeMaxWatts)));
            }
            }
            if (selection.memory() && (caps.memoryClockControl() || probeFixedMemoryWithoutDriverLock)
                    && observedClocks != null) {
                int baselineMemory = observedClocks.memoryMhz();
                return unique.stream().sorted(Comparator
                        .comparingInt((BayesianGpuOptimizer.Candidate row) ->
                                row.memoryMhz() == null || row.memoryMhz() == baselineMemory ? 1 : 0)
                        .thenComparingInt(row -> row.memoryMhz() != null && row.memoryMhz() < baselineMemory ? 0 : 1)
                        .thenComparingInt(row -> row.memoryMhz() == null ? Integer.MAX_VALUE
                                : Math.abs(row.memoryMhz() - baselineMemory)))
                        .toList();
            }
            return List.copyOf(unique);
        }
        for (int i = 0; i < cores.size(); i++) for (int j = 0; j < memories.size(); j++) for (int k = 0; k < power.size(); k++) {
            Integer core = selection.core() && caps.coreClockControl() ? cores.get(i) : null;
            Integer memory = selection.memory() && caps.memoryClockControl() ? memories.get(j) : null;
            unique.add(new BayesianGpuOptimizer.Candidate(core, memory, power.get(k),
                    core == null ? 0 : position(core, caps.coreMinMhz(), caps.coreMaxMhz()),
                    memory == null ? 0 : position(memory, caps.memoryMinMhz(), caps.memoryMaxMhz()),
                    selection.power() ? position(power.get(k), gpu.minWatts(), safeMaxWatts) : 0));
        }
        return List.copyOf(unique);
    }

    private static double position(int value, int min, int max) {
        return max == min ? 0 : (value - min) / (double) (max - min);
    }

    private static BayesianGpuOptimizer.Candidate memoryBoundaryCandidate(
            List<BayesianGpuOptimizer.Candidate> candidates,
            BayesianGpuOptimizer.Candidate preferred, boolean offset, boolean minimum) {
        List<BayesianGpuOptimizer.Candidate> eligible = candidates.stream()
                .filter(row -> offset ? row.memoryOffsetMhz() != null : row.memoryMhz() != null).toList();
        if (eligible.isEmpty()) return preferred;
        int boundary = eligible.stream().mapToInt(row -> offset ? row.memoryOffsetMhz() : row.memoryMhz())
                .min().orElseThrow();
        if (!minimum) boundary = eligible.stream()
                .mapToInt(row -> offset ? row.memoryOffsetMhz() : row.memoryMhz()).max().orElseThrow();
        final int selectedBoundary = boundary;
        return eligible.stream().filter(row -> (offset ? row.memoryOffsetMhz() : row.memoryMhz()) == selectedBoundary)
                .min(Comparator.comparingInt((BayesianGpuOptimizer.Candidate row) ->
                        Math.abs(Objects.requireNonNullElse(row.coreMhz(), 0)
                                - Objects.requireNonNullElse(preferred == null ? null : preferred.coreMhz(), 0))))
                .orElse(preferred);
    }

    /**
     * Extend tiers with enough stable evidence and a material best-to-median advantage.
     * The caller rotates between eligible tiers instead of extending only the global winner.
     */
    static BayesianGpuOptimizer.Observation promisingEfficientRegion(
            List<BayesianGpuOptimizer.Observation> observations) {
        return promisingEfficientRegions(observations).stream().findFirst().orElse(null);
    }

    static List<BayesianGpuOptimizer.Observation> promisingEfficientRegions(
            List<BayesianGpuOptimizer.Observation> observations) {
        return observations.stream().filter(BayesianGpuOptimizer.Observation::stable)
                .collect(java.util.stream.Collectors.groupingBy(row -> row.candidate().powerWatts())).values().stream()
                .map(rows -> {
                    if (rows.size() < 3) return null;
                    List<Double> scores = rows.stream().map(BayesianGpuOptimizer.Observation::score)
                            .sorted().toList();
                    double median = scores.get(scores.size() / 2);
                    BayesianGpuOptimizer.Observation best = rows.stream()
                            .max(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score)).orElse(null);
                    return best != null && best.score() >= median * REFINEMENT_ADVANTAGE ? best : null;
                }).filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score).reversed()).toList();
    }

    static List<BayesianGpuOptimizer.Candidate> confirmationCandidates(
            List<BayesianGpuOptimizer.Observation> observations, List<BayesianGpuOptimizer.Candidate> activeCandidates) {
        return observations.stream().filter(row -> row.stable() && activeCandidates.contains(row.candidate()))
                .collect(java.util.stream.Collectors.groupingBy(row -> row.candidate().powerWatts(),
                        TreeMap::new, java.util.stream.Collectors.toList())).values().stream()
                .flatMap(rows -> {
                    Set<BayesianGpuOptimizer.Candidate> finalists = new LinkedHashSet<>();
                    rows.stream().sorted(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score).reversed())
                            .map(BayesianGpuOptimizer.Observation::candidate).distinct().limit(2).forEach(finalists::add);
                    // A faster, less efficient point can win the daily-profit comparison.
                    rows.stream().filter(row -> row.sample() != null && Double.isFinite(row.sample().hashrateHs())
                                    && row.sample().hashrateHs() > 0)
                            .max(Comparator.comparingDouble(row -> row.sample().hashrateHs()))
                            .map(BayesianGpuOptimizer.Observation::candidate).ifPresent(finalists::add);
                    return finalists.stream();
                }).toList();
    }

    static List<Integer> powerTiers(int min, int start, int max) {
        if (max - min < 2) return List.of();
        if (start <= min) return List.of(min, min + (max - min) / 2, max);
        if (start >= max) return List.of(min, min + (max - min) / 2, max);
        if (start - min >= 1 && max - start >= 1) return List.of(min, start, max);
        return List.of(min, min + (max - min) / 2, max);
    }

    private static List<Integer> values(boolean enabled, Integer min, Integer max) {
        if (!enabled || min == null || max == null) return List.of(0);
        Set<Integer> grid = new LinkedHashSet<>();
        for (int step = 0; step <= 8; step++) grid.add(min + (max - min) * step / 8);
        return List.copyOf(grid);
    }

    public record SampleSummary(boolean stable, double hashrateHs, double watts, int samples,
                                Double maxTemperatureC, Integer observedCoreMhz, Integer observedMemoryMhz,
                                String detail, boolean driverFailure) {
        static SampleSummary unstable(String detail, boolean driverFailure) {
            return new SampleSummary(false, 0, 0, 0, null, null, null, detail, driverFailure);
        }
        SampleSummary withFailure(String detail) {
            return new SampleSummary(false, hashrateHs, watts, samples, maxTemperatureC,
                    observedCoreMhz, observedMemoryMhz, detail, false);
        }
    }
    public record RunObservation(Integer coreMhz, Integer memoryMhz, int powerWatts, boolean stable,
                                 double score, int validSamples, Double hashrateHs, Double measuredWatts,
                                 Integer observedCoreMhz, Integer observedMemoryMhz, String detail,
                                 Integer memoryOffsetMhz) { }
    private record QueuedWorker(String deviceId, String coinId) { }
    public record Session(boolean running, String phase, Instant startedAt, String deviceId, String model,
                          String coinId, String algorithm, String softwareId, int candidateIndex, int candidateCount,
                          Integer currentCoreMhz, Integer currentMemoryMhz, Integer currentPowerWatts,
                          int sampleIndex, int sampleCount, List<RunObservation> observations,
                          AutoTuningProfileStore.Profile result, Integer currentMemoryOffsetMhz) {
        public Session(boolean running, String phase, Instant startedAt, String deviceId, String model,
                       String coinId, String algorithm, String softwareId, int candidateIndex, int candidateCount,
                       Integer currentCoreMhz, Integer currentMemoryMhz, Integer currentPowerWatts,
                       int sampleIndex, int sampleCount, List<RunObservation> observations,
                       AutoTuningProfileStore.Profile result) {
            this(running, phase, startedAt, deviceId, model, coinId, algorithm, softwareId, candidateIndex,
                    candidateCount, currentCoreMhz, currentMemoryMhz, currentPowerWatts, sampleIndex,
                    sampleCount, observations, result, null);
        }
        static Session idle() { return new Session(false, "idle", null, null, null, null, null, null, 0, 0,
                null, null, null, 0, 0, List.of(), null); }
        Session withPhase(String phase) { return new Session(true, phase, startedAt, deviceId, model, coinId, algorithm,
                softwareId, candidateIndex, candidateCount, currentCoreMhz, currentMemoryMhz, currentPowerWatts, sampleIndex, sampleCount, observations, result, currentMemoryOffsetMhz); }
        Session withCandidateCount(int value) { return new Session(running, phase, startedAt, deviceId, model, coinId, algorithm,
                softwareId, candidateIndex, value, currentCoreMhz, currentMemoryMhz, currentPowerWatts, sampleIndex, sampleCount, observations, result, currentMemoryOffsetMhz); }
        Session withCandidate(BayesianGpuOptimizer.Candidate c, int index, int samples, boolean confirming) {
            return new Session(true, confirming ? "auto_tuning_phase_confirming" : "auto_tuning_phase_sampling", startedAt,
                deviceId, model, coinId, algorithm, softwareId, index, candidateCount, c.coreMhz(), c.memoryMhz(), c.powerWatts(), 0, samples, observations, result, c.memoryOffsetMhz()); }
        Session withSampleIndex(int value) { return new Session(running, phase, startedAt, deviceId, model, coinId, algorithm,
                softwareId, candidateIndex, candidateCount, currentCoreMhz, currentMemoryMhz, currentPowerWatts, value, sampleCount, observations, result, currentMemoryOffsetMhz); }
        Session withObservations(List<BayesianGpuOptimizer.Observation> rows) {
            List<RunObservation> values = rows.stream().map(row -> {
                SampleSummary sample = row.sample();
                return new RunObservation(row.candidate().coreMhz(), row.candidate().memoryMhz(), row.candidate().powerWatts(),
                        row.stable(), row.score(), sample == null ? 0 : sample.samples(),
                        sample == null || sample.samples() == 0 ? null : sample.hashrateHs(),
                        sample == null || sample.samples() == 0 ? null : sample.watts(),
                        sample == null ? null : sample.observedCoreMhz(),
                        sample == null ? null : sample.observedMemoryMhz(), row.detail(), row.candidate().memoryOffsetMhz());
            }).toList();
            return new Session(running, phase, startedAt, deviceId, model, coinId, algorithm, softwareId, candidateIndex,
                    candidateCount, currentCoreMhz, currentMemoryMhz, currentPowerWatts, sampleIndex, sampleCount, values, result, currentMemoryOffsetMhz);
        }
        Session completed(AutoTuningProfileStore.Profile profile, String phase) { return new Session(false, phase, startedAt, deviceId,
                model, coinId, algorithm, softwareId, candidateCount, candidateCount, profile.coreMhz(), profile.memoryMhz(),
                profile.powerLimitWatts(), sampleCount, sampleCount, observations, profile, profile.memoryOffsetMhz()); }
        Session failed(String detail) { return new Session(false, detail, startedAt, deviceId, model, coinId, algorithm, softwareId,
                candidateIndex, candidateCount, currentCoreMhz, currentMemoryMhz, currentPowerWatts, sampleIndex, sampleCount, observations, result, currentMemoryOffsetMhz); }
    }
}
