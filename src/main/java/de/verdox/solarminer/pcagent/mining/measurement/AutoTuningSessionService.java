package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
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
    private static final int MAX_CANDIDATES = 12, SAMPLES_PER_CANDIDATE = 16, MAX_RUN_MINUTES = 40;
    private static final int MAX_HASHRATE_OUTLIERS = 2;
    private final MiningService mining;
    private final LocalGpuPowerService power;
    private final GpuTuningService tuning;
    private final AutoTuningProfileStore profiles;
    private final LocalRunLock runLock;
    private final MinerFactory miners;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pc-agent-auto-tuning"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final AtomicBoolean awaitingWorker = new AtomicBoolean();
    private volatile String awaitingDeviceId;
    private volatile Session session = Session.idle();

    @org.springframework.beans.factory.annotation.Autowired
    public AutoTuningSessionService(MiningService mining, LocalGpuPowerService power, GpuTuningService tuning,
                                   AutoTuningProfileStore profiles, LocalRunLock runLock, MinerFactory miners,
                                   @Value("${solarminer.agent.auto-tuning.max-temperature-c:85}") double maxTemperatureC) {
        this(mining, power, tuning, profiles, runLock, miners, maxTemperatureC, 5_000);
    }

    AutoTuningSessionService(MiningService mining, LocalGpuPowerService power, GpuTuningService tuning,
                             AutoTuningProfileStore profiles, LocalRunLock runLock, MinerFactory miners,
                             double maxTemperatureC, long sampleIntervalMillis) {
        this.mining = mining; this.power = power; this.tuning = tuning; this.profiles = profiles;
        this.runLock = runLock; this.miners = miners;
        this.maxTemperatureC = Double.isFinite(maxTemperatureC)
                ? Math.max(65, Math.min(90, maxTemperatureC)) : 85;
        this.sampleIntervalMillis = sampleIntervalMillis;
    }

    public synchronized Session start(String deviceId, String coinId) {
        if (session.running()) throw new IllegalStateException("auto_tuning_busy");
        Coin coin = Coin.byIdOrNull(coinId);
        if (coin == null || !coin.isGpu()) throw new IllegalArgumentException("auto_tuning_requires_gpu_coin");
        LocalGpuPowerService.Gpu gpu = power.refresh().stream().filter(value -> value.deviceId().equals(deviceId)).findFirst().orElse(null);
        if (gpu == null) throw new IllegalArgumentException("gpu_not_found");
        if (!gpu.supportsDynamicPowerScaling() || gpu.currentPowerLimitWatts() == null)
            throw new IllegalStateException("gpu_power_control_unavailable");
        if (gpu.safeTuningMaxWatts() == null)
            throw new IllegalStateException("auto_tuning_driver_default_power_unavailable");
        int startingWatts = power.configuredPowerLimitWatts(gpu);
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
        if (!caps.coreClockControl() && !caps.memoryClockControl())
            throw new IllegalStateException(caps.reason() == null ? "gpu_clock_controls_unavailable" : caps.reason());
        GpuTuningService.ClockTelemetry observedClocks = "NVIDIA".equalsIgnoreCase(gpu.vendor())
                ? tuning.readEffectiveClocks(deviceId) : null;
        if ("NVIDIA".equalsIgnoreCase(gpu.vendor()) && observedClocks == null)
            throw new IllegalStateException("auto_tuning_clock_readback_unavailable");
        String software = miners.minerSoftwareForWorker(coin, deviceId);
        if (software == null || software.isBlank())
            throw new IllegalStateException("auto_tuning_miner_unavailable");
        String softwareVersion = miners.minerVersionForWorker(coin, deviceId);
        GpuTuningService.MemoryOffsetRange offsetRange = tuning.memoryOffsetRange(deviceId);
        if (offsetRange == null && "NVIDIA".equalsIgnoreCase(gpu.vendor())
                && gpu.model() != null && gpu.model().matches("(?i).*RTX\\s*20[0-9]{2}.*"))
            caps = new GpuTuningService.Capabilities(caps.deviceId(), caps.coreClockControl(), false,
                    caps.voltageControl(), caps.coreMinMhz(), caps.coreMaxMhz(), caps.memoryMinMhz(),
                    caps.memoryMaxMhz(), caps.reason(), caps.supportedPairs());
        List<BayesianGpuOptimizer.Candidate> candidates = candidates(gpu, caps, observedClocks, startingWatts, offsetRange);
        if (candidates.isEmpty()) throw new IllegalStateException("auto_tuning_no_safe_clock_candidates");
        if (candidates.stream().map(BayesianGpuOptimizer.Candidate::powerWatts).distinct().count() < 3)
            throw new IllegalStateException("auto_tuning_power_range_too_narrow");
        int plannedCandidates = candidates.stream().collect(java.util.stream.Collectors.groupingBy(
                BayesianGpuOptimizer.Candidate::powerWatts)).values().stream()
                .mapToInt(rows -> Math.min(MAX_CANDIDATES / 3, rows.size())).sum();
        if (!runLock.tryBegin("auto-tuning")) throw new IllegalStateException("power_mode_busy");
        cancel.set(false);
        session = new Session(true, "auto_tuning_phase_prepare", Instant.now(), deviceId, gpu.model(), coin.id(),
                coin.assignmentAlgorithm(), software, 0, plannedCandidates, null, null, null, 0, SAMPLES_PER_CANDIDATE, List.of(), null);
        GpuTuningService.Capabilities runCaps = caps;
        try { executor.execute(() -> run(gpu, coin, software, softwareVersion, runCaps, observedClocks, offsetRange, candidates)); }
        catch (RuntimeException rejected) {
            session = Session.idle();
            runLock.end();
            throw new IllegalStateException("auto_tuning_busy", rejected);
        }
        return session;
    }

    /** Called by the operating mode after miner startup; the miner may need time to report MINING. */
    public void startWhenReady(String deviceId, String coinId) {
        if (session.running() || !awaitingWorker.compareAndSet(false, true)) return;
        awaitingDeviceId = deviceId;
        cancel.set(false);
        try {
            executor.execute(() -> {
                try {
                    for (int attempt = 0; attempt < 30 && !cancel.get(); attempt++) {
                        if (controls == null || !"AUTO_TUNING".equals(controls.get().powerModeFor(deviceId))) return;
                        if (session.running()) return;
                        MinerStats.Worker active = worker(deviceId);
                        if (!runLock.busy() && active != null
                                && active.miningStatus() == MinerStats.MinerStatus.MINING) {
                            start(deviceId, coinId);
                            return;
                        }
                        Thread.sleep(1_000);
                    }
                    session = session.failed("auto_tuning_requires_active_worker");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException failure) {
                    session = session.failed(Objects.toString(failure.getMessage(), "auto_tuning_failed"));
                } finally { awaitingDeviceId = null; awaitingWorker.set(false); }
            });
        } catch (RuntimeException rejected) { awaitingDeviceId = null; awaitingWorker.set(false); }
    }

    public Session status() { return session; }
    public synchronized Session cancel() { cancel.set(true); return session; }
    public void cancelForDevice(String deviceId) {
        if (deviceId != null && (deviceId.equals(session.deviceId()) || deviceId.equals(awaitingDeviceId)))
            cancel.set(true);
    }

    @PreDestroy
    public void shutdown() {
        cancel.set(true);
        executor.shutdownNow();
        try { executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void run(LocalGpuPowerService.Gpu gpu, Coin coin, String software, String softwareVersion,
                     GpuTuningService.Capabilities caps, GpuTuningService.ClockTelemetry observedClocks,
                     GpuTuningService.MemoryOffsetRange offsetBaseline,
                     List<BayesianGpuOptimizer.Candidate> candidates) {
        RunContext context = new RunContext(UUID.randomUUID().toString(), gpu, coin, software, softwareVersion);
        int originalPower = gpu.currentPowerLimitWatts();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(MAX_RUN_MINUTES);
        List<BayesianGpuOptimizer.Observation> observations = new ArrayList<>();
        List<BayesianGpuOptimizer.Candidate> activeCandidates = candidates;
        int measuredCandidates = 0;
        try {
            session = session.withPhase("auto_tuning_phase_sampling");
            int startingWatts = power.configuredPowerLimitWatts(gpu);
            List<Integer> tiers = candidates.stream().map(BayesianGpuOptimizer.Candidate::powerWatts).distinct()
                    .sorted(Comparator.comparingInt((Integer watts) -> Math.abs(watts - startingWatts))
                            .thenComparingInt(Integer::intValue)).toList();
            if (tiers.size() < 3) throw new IllegalStateException("auto_tuning_power_range_too_narrow");
            List<BayesianGpuOptimizer.Observation> historical = historicalObservations(context, activeCandidates);
            for (int index = 0; index < MAX_CANDIDATES; index++) {
                if (cancel.get()) break;
                if (System.nanoTime() >= deadline) throw new IllegalStateException("auto_tuning_time_limit");
                int tierWatts = tiers.get(Math.min(index / (MAX_CANDIDATES / 3), 2));
                List<BayesianGpuOptimizer.Candidate> tierCandidates = activeCandidates.stream()
                        .filter(row -> row.powerWatts() == tierWatts).toList();
                List<BayesianGpuOptimizer.Observation> tierObservations = observations.stream()
                        .filter(row -> row.candidate().powerWatts() == tierWatts).toList();
                List<BayesianGpuOptimizer.Observation> training = new ArrayList<>(historical.stream()
                        .filter(row -> row.candidate().powerWatts() == tierWatts
                                && tierObservations.stream().noneMatch(current -> current.candidate().equals(row.candidate())))
                        .toList());
                training.addAll(tierObservations);
                BayesianGpuOptimizer.Candidate candidate = BayesianGpuOptimizer.next(tierCandidates, training, tierObservations);
                if (index == 0 && tierCandidates.stream().anyMatch(row -> row.memoryOffsetMhz() != null))
                    candidate = tierCandidates.stream().filter(row -> row.memoryOffsetMhz() != null
                            && row.memoryOffsetMhz() != 0).findFirst().orElse(candidate);
                else if (index == 0 && caps.memoryClockControl() && observedClocks != null)
                    candidate = tierCandidates.stream()
                            .filter(row -> row.memoryMhz() != null
                                    && Math.abs(row.memoryMhz() - observedClocks.memoryMhz())
                                    > memoryTolerance(row.memoryMhz()))
                            .findFirst().orElse(candidate);
                if (candidate == null) continue;
                session = session.withCandidate(candidate, ++measuredCandidates);
                GpuTuningService.ApplyResult clockWrite = candidate.memoryOffsetMhz() == null
                        ? tuning.apply(gpu.deviceId(), candidate.coreMhz(), candidate.coreMhz(),
                        candidate.memoryMhz(), candidate.memoryMhz())
                        : tuning.apply(gpu.deviceId(), candidate.coreMhz(), candidate.coreMhz(),
                        null, null, candidate.memoryOffsetMhz(), candidate.memoryOffsetPstate());
                boolean capApplied = clockWrite.applied() && power.setAutoTuningPowerTarget(candidate.powerWatts(), gpu);
                if (!capApplied) {
                    saveMeasurement(context, candidate, "RESULT", null, null, null, null, null,
                            0, null, null, false,
                            clockWrite.applied() ? "gpu_power_control_unavailable" : clockWrite.reason());
                    observations.add(new BayesianGpuOptimizer.Observation(candidate, 0, false,
                            clockWrite.applied() ? "gpu_power_control_unavailable" : clockWrite.reason()));
                    session = session.withObservations(observations);
                    if (!clockWrite.applied() && candidate.memoryOffsetMhz() != null) {
                        if (!tuning.restoreDefaults(gpu.deviceId()).applied())
                            throw new IllegalStateException("gpu_clock_restore_failed");
                        GpuTuningService.Capabilities coreOnly = new GpuTuningService.Capabilities(caps.deviceId(),
                                caps.coreClockControl(), false, caps.voltageControl(), caps.coreMinMhz(), caps.coreMaxMhz(),
                                caps.memoryMinMhz(), caps.memoryMaxMhz(), caps.reason(), caps.supportedPairs());
                        activeCandidates = candidates(gpu, coreOnly, observedClocks, startingWatts);
                        historical = historicalObservations(context, activeCandidates);
                    }
                    continue;
                }
                SampleSummary sample = collect(context, candidate, deadline, observedClocks, offsetBaseline);
                saveMeasurement(context, candidate, "RESULT", sample.samples() > 0 ? sample.hashrateHs() : null,
                        sample.samples() > 0 ? sample.watts() : null, sample.maxTemperatureC(),
                        sample.observedCoreMhz(), sample.observedMemoryMhz(), sample.samples(),
                        null, null, sample.stable(), sample.detail());
                observations.add(new BayesianGpuOptimizer.Observation(candidate,
                        sample.stable() ? sample.hashrateHs() / sample.watts() : 0,
                        sample.stable(), sample.detail(), sample));
                session = session.withObservations(observations);
                if ("auto_tuning_memory_lock_ineffective".equals(sample.detail())) {
                    if (!tuning.restoreDefaults(gpu.deviceId()).applied())
                        throw new IllegalStateException("gpu_clock_restore_failed");
                    GpuTuningService.Capabilities coreOnly = new GpuTuningService.Capabilities(caps.deviceId(),
                            caps.coreClockControl(), false, caps.voltageControl(), caps.coreMinMhz(), caps.coreMaxMhz(),
                            caps.memoryMinMhz(), caps.memoryMaxMhz(), caps.reason(), caps.supportedPairs());
                    activeCandidates = candidates(gpu, coreOnly, observedClocks, startingWatts);
                    if (activeCandidates.isEmpty()) throw new IllegalStateException("auto_tuning_no_safe_clock_candidates");
                    historical = historicalObservations(context, activeCandidates);
                }
                if (sample.driverFailure()) {
                    if (!"auto_tuning_worker_stopped".equals(sample.detail()) || !recoverWorker(gpu, coin, originalPower))
                        throw new IllegalStateException(sample.detail());
                }
            }
            if (cancel.get()) throw new IllegalStateException("auto_tuning_cancelled");
            for (int tierWatts : tiers) {
                BayesianGpuOptimizer.Observation bestAtTier = observations.stream()
                        .filter(row -> row.stable() && row.candidate().powerWatts() == tierWatts)
                        .max(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score)).orElse(null);
                if (bestAtTier == null) throw new IllegalStateException("auto_tuning_no_stable_tier");
            }
            AutoTuningProfileStore.Profile profile = profiles.mostEfficientWithinBudget(gpu.deviceId(), coin.id(),
                    software, softwareVersion, gpu.model(), gpu.vendor(), gpu.safeTuningMaxWatts(),
                    coin.assignmentAlgorithm());
            if (profile == null) throw new IllegalStateException("auto_tuning_profile_save_failed");
            if (!tuning.restoreDefaults(gpu.deviceId()).applied()
                    || !power.setAutoTuningPowerTarget(originalPower, gpu))
                throw new IllegalStateException("gpu_clock_restore_failed");
            if (controls != null && modes != null
                    && "AUTO_TUNING".equals(controls.get().powerModeFor(gpu.deviceId()))) {
                var applied = modes.apply(gpu.deviceId(), coin.id());
                if (applied.error() != null) throw new IllegalStateException(applied.error());
            }
            session = session.completed(profile, "auto_tuning_complete");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            tuning.restoreDefaults(gpu.deviceId());
            power.setAutoTuningPowerTarget(originalPower, gpu);
            session = session.failed(Objects.toString(failure.getMessage(), "auto_tuning_failed"));
        } finally { runLock.end(); }
    }

    private boolean recoverWorker(LocalGpuPowerService.Gpu gpu, Coin coin, int originalPower) throws InterruptedException {
        if (!tuning.restoreDefaults(gpu.deviceId()).applied()
                || !power.setAutoTuningPowerTarget(originalPower, gpu)) return false;
        for (int attempt = 0; attempt < 3 && !cancel.get(); attempt++) {
            Thread.sleep(5_000);
            if (miners.gpuMinerForWorker(coin.id(), gpu.deviceId()).orElseThrow()
                    .resumeGpu(gpu.vendor(), gpu.index())) return true;
        }
        return false;
    }

    private SampleSummary collect(RunContext context, BayesianGpuOptimizer.Candidate candidate,
                                  long deadline, GpuTuningService.ClockTelemetry baselineClocks,
                                  GpuTuningService.MemoryOffsetRange offsetBaseline) throws InterruptedException {
        List<Double> rates = new ArrayList<>(), watts = new ArrayList<>(), temps = new ArrayList<>();
        List<Integer> observedCore = new ArrayList<>(), observedMemory = new ArrayList<>();
        int consecutiveMemoryMismatches = 0;
        for (int i = 0; i < SAMPLES_PER_CANDIDATE; i++) {
            if (cancel.get()) return SampleSummary.unstable("auto_tuning_cancelled", false);
            if (System.nanoTime() >= deadline) throw new IllegalStateException("auto_tuning_time_limit");
            Thread.sleep(sampleIntervalMillis);
            session = session.withSampleIndex(i + 1);
            MinerStats.Worker worker = worker(session.deviceId());
            if (worker == null || worker.miningStatus() != MinerStats.MinerStatus.MINING) {
                saveMeasurement(context, candidate, "SAMPLE", null, null, null, null, null,
                        i + 1, null, null, null, "auto_tuning_worker_stopped");
                return SampleSummary.unstable("auto_tuning_worker_stopped", true);
            }
            double rate = worker.terahashPerSecond() * 1e12;
            double powerWatts = worker.approximatedPowerUsageWatts();
            LocalGpuPowerService.Gpu gpu = power.snapshot().stream()
                    .filter(value -> session.deviceId().equals(value.deviceId())).findFirst().orElse(null);
            Double temp = gpu == null ? null : power.readTemperatureC(gpu);
            boolean validRate = Double.isFinite(rate) && rate > 0;
            boolean validPower = Double.isFinite(powerWatts) && powerWatts > 0;
            boolean validTemp = temp != null && Double.isFinite(temp);
            GpuTuningService.ClockTelemetry clocks = validRate && validPower && validTemp
                    && "NVIDIA".equalsIgnoreCase(gpu.vendor()) ? tuning.readEffectiveClocks(gpu.deviceId()) : null;
            boolean offsetNoEffect = clocks != null && candidate.memoryOffsetMhz() != null
                    && baselineClocks != null && offsetBaseline != null
                    && candidate.memoryOffsetPstate() == offsetBaseline.pstate()
                    && !candidate.memoryOffsetMhz().equals(offsetBaseline.currentMhz())
                    && Math.abs(clocks.memoryMhz() - baselineClocks.memoryMhz()) < 20;
            boolean memoryMismatch = clocks != null && (candidate.memoryMhz() != null
                    && Math.abs(clocks.memoryMhz() - candidate.memoryMhz()) > memoryTolerance(candidate.memoryMhz())
                    || candidate.memoryOffsetMhz() != null && !Objects.equals(candidate.memoryOffsetMhz(),
                    tuning.readMemoryOffset(gpu.deviceId(), candidate.memoryOffsetPstate()))
                    || offsetNoEffect);
            String issue = !validRate || !validPower ? "auto_tuning_sample_unavailable"
                    : !validTemp ? "auto_tuning_temperature_unavailable"
                    : temp > maxTemperatureC ? "auto_tuning_temperature_limit"
                    : "NVIDIA".equalsIgnoreCase(gpu.vendor()) && clocks == null
                    ? "auto_tuning_clock_readback_unavailable"
                    : memoryMismatch ? "auto_tuning_memory_lock_ineffective" : null;
            saveMeasurement(context, candidate, "SAMPLE", validRate ? rate : null,
                    validPower ? powerWatts : null, validTemp ? temp : null,
                    clocks == null ? null : clocks.coreMhz(), clocks == null ? null : clocks.memoryMhz(),
                    i + 1, worker.acceptedShares(), worker.rejectedShares(), null, issue);
            if (!validRate || !validPower)
                continue;
            if (!validTemp)
                return SampleSummary.unstable("auto_tuning_temperature_unavailable", true);
            if (temp > maxTemperatureC)
                return SampleSummary.unstable("auto_tuning_temperature_limit", true);
            if ("NVIDIA".equalsIgnoreCase(gpu.vendor())) {
                if (clocks == null) return SampleSummary.unstable("auto_tuning_clock_readback_unavailable", false);
                observedCore.add(clocks.coreMhz()); observedMemory.add(clocks.memoryMhz());
            }
            rates.add(rate); watts.add(powerWatts); temps.add(temp);
            consecutiveMemoryMismatches = memoryMismatch ? consecutiveMemoryMismatches + 1 : 0;
            if (consecutiveMemoryMismatches >= 3)
                return summarize(rates, watts, temps, observedCore, observedMemory)
                        .withFailure("auto_tuning_memory_lock_ineffective");
        }
        if (rates.size() < SAMPLES_PER_CANDIDATE - 2) return SampleSummary.unstable("auto_tuning_insufficient_samples", false);
        SampleSummary summary = summarize(rates, watts, temps, observedCore, observedMemory);
        double median = summary.hashrateHs();
        if (rates.stream().filter(value -> value < median * .8 || value > median * 1.2).count() > MAX_HASHRATE_OUTLIERS)
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
        return mining.getWorkerStats().stream().filter(value -> deviceId.equals(value.deviceId())).findFirst().orElse(null);
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
        Integer safeMaxWatts = gpu.safeTuningMaxWatts();
        if (safeMaxWatts == null || safeMaxWatts < gpu.minWatts()) return List.of();
        List<Integer> cores = values(caps.coreClockControl(), caps.coreMinMhz(), caps.coreMaxMhz());
        List<Integer> memories = values(caps.memoryClockControl(), caps.memoryMinMhz(), caps.memoryMaxMhz());
        if (startWatts < gpu.minWatts() || startWatts > safeMaxWatts) return List.of();
        List<Integer> power = powerTiers(gpu.minWatts(), startWatts, safeMaxWatts);
        Set<BayesianGpuOptimizer.Candidate> unique = new LinkedHashSet<>();
        if (offsetRange != null && observedClocks != null && caps.coreClockControl()) {
            int low = Math.max(-200, offsetRange.minMhz());
            int high = Math.min(100, offsetRange.maxMhz());
            if (low <= high) {
                List<Integer> offsets = new ArrayList<>(new LinkedHashSet<>(List.of(
                        Math.clamp(-100, low, high), Math.clamp(0, low, high), Math.clamp(100, low, high))));
                List<Integer> coreTargets = caps.supportedPairs().stream()
                        .filter(pair -> pair.memoryMhz() == observedClocks.memoryMhz())
                        .map(GpuTuningService.ClockPair::coreMhz).distinct()
                        .sorted(Comparator.comparingInt((Integer core) -> Math.abs(core - observedClocks.coreMhz())))
                        .toList();
                if (coreTargets.isEmpty()) coreTargets = cores;
                for (int target : power) for (int core : coreTargets) for (int offset : offsets)
                    unique.add(new BayesianGpuOptimizer.Candidate(core, null, target,
                            position(core, caps.coreMinMhz(), caps.coreMaxMhz()),
                            position(offset, low, high), position(target, gpu.minWatts(), safeMaxWatts),
                            offset, offsetRange.pstate()));
                return List.copyOf(unique);
            }
        }
        if (!caps.supportedPairs().isEmpty()) {
            int highestSupportedMemory = caps.supportedPairs().stream()
                    .mapToInt(GpuTuningService.ClockPair::memoryMhz)
                    .filter(value -> observedClocks == null || value <= observedClocks.memoryMhz())
                    .max().orElse(0);
            for (var pair : caps.supportedPairs()) for (int target : power) {
                if (!caps.memoryClockControl() && pair.memoryMhz() != highestSupportedMemory) continue;
                Integer memory = caps.memoryClockControl() ? pair.memoryMhz() : null;
                unique.add(new BayesianGpuOptimizer.Candidate(pair.coreMhz(), memory, target,
                        position(pair.coreMhz(), caps.coreMinMhz(), caps.coreMaxMhz()),
                        memory == null ? 0 : position(memory, caps.memoryMinMhz(), caps.memoryMaxMhz()),
                        position(target, gpu.minWatts(), safeMaxWatts)));
            }
            if (caps.memoryClockControl() && observedClocks != null) {
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
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) {
            Integer core = caps.coreClockControl() ? cores.get(i) : null;
            Integer memory = caps.memoryClockControl() ? memories.get(j) : null;
            unique.add(new BayesianGpuOptimizer.Candidate(core, memory, power.get(k),
                    caps.coreClockControl() ? i / 2d : 0, caps.memoryClockControl() ? j / 2d : 0, k / 2d));
        }
        return List.copyOf(unique);
    }

    private static double position(int value, int min, int max) {
        return max == min ? 0 : (value - min) / (double) (max - min);
    }

    static List<Integer> powerTiers(int min, int start, int max) {
        if (max - min < 2) return List.of();
        if (start <= min) return List.of(min, min + (max - min) / 2, max);
        if (start >= max) return List.of(min, min + (max - min) / 2, max);
        if (start - min >= 1 && max - start >= 1) return List.of(min, start, max);
        return List.of(min, min + (max - min) / 2, max);
    }

    private static List<Integer> values(boolean enabled, Integer min, Integer max) {
        if (!enabled || min == null || max == null) return List.of(0, 0, 0);
        return List.of(min, min + (max - min) / 2, max);
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
        Session withCandidate(BayesianGpuOptimizer.Candidate c, int index) { return new Session(true, "auto_tuning_phase_sampling", startedAt,
                deviceId, model, coinId, algorithm, softwareId, index, candidateCount, c.coreMhz(), c.memoryMhz(), c.powerWatts(), 0, SAMPLES_PER_CANDIDATE, observations, result, c.memoryOffsetMhz()); }
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
