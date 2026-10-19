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
    private static final int MAX_CANDIDATES = 12, SAMPLES_PER_CANDIDATE = 8;
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
        GpuTuningService.ClockTelemetry clockCeiling = "NVIDIA".equalsIgnoreCase(gpu.vendor())
                ? tuning.readEffectiveClocks(deviceId) : null;
        if ("NVIDIA".equalsIgnoreCase(gpu.vendor()) && clockCeiling == null)
            throw new IllegalStateException("auto_tuning_clock_readback_unavailable");
        String software = miners.minerSoftwareForWorker(coin, deviceId);
        String softwareVersion = miners.minerVersionForWorker(coin, deviceId);
        List<BayesianGpuOptimizer.Candidate> candidates = candidates(gpu, caps, clockCeiling, startingWatts);
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
        try { executor.execute(() -> run(gpu, coin, software, softwareVersion, candidates)); }
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
                     List<BayesianGpuOptimizer.Candidate> candidates) {
        int originalPower = gpu.currentPowerLimitWatts();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(15);
        List<BayesianGpuOptimizer.Observation> observations = new ArrayList<>();
        Map<BayesianGpuOptimizer.Candidate, SampleSummary> samplesByCandidate = new HashMap<>();
        int measuredCandidates = 0;
        try {
            session = session.withPhase("auto_tuning_phase_sampling");
            int startingWatts = power.configuredPowerLimitWatts(gpu);
            List<Integer> tiers = candidates.stream().map(BayesianGpuOptimizer.Candidate::powerWatts).distinct()
                    .sorted(Comparator.comparingInt((Integer watts) -> Math.abs(watts - startingWatts))
                            .thenComparingInt(Integer::intValue)).toList();
            if (tiers.size() < 3) throw new IllegalStateException("auto_tuning_power_range_too_narrow");
            for (int index = 0; index < MAX_CANDIDATES; index++) {
                if (cancel.get()) break;
                if (System.nanoTime() >= deadline) throw new IllegalStateException("auto_tuning_time_limit");
                int tierWatts = tiers.get(Math.min(index / (MAX_CANDIDATES / 3), 2));
                List<BayesianGpuOptimizer.Candidate> tierCandidates = candidates.stream()
                        .filter(row -> row.powerWatts() == tierWatts).toList();
                List<BayesianGpuOptimizer.Observation> tierObservations = observations.stream()
                        .filter(row -> row.candidate().powerWatts() == tierWatts).toList();
                BayesianGpuOptimizer.Candidate candidate = BayesianGpuOptimizer.next(tierCandidates, tierObservations);
                if (candidate == null) continue;
                session = session.withCandidate(candidate, ++measuredCandidates);
                GpuTuningService.ApplyResult clockWrite = tuning.apply(gpu.deviceId(), candidate.coreMhz(), candidate.coreMhz(),
                        candidate.memoryMhz(), candidate.memoryMhz());
                boolean capApplied = clockWrite.applied() && power.setAutoTuningPowerTarget(candidate.powerWatts(), gpu);
                if (!capApplied) {
                    observations.add(new BayesianGpuOptimizer.Observation(candidate, 0, false,
                            clockWrite.applied() ? "gpu_power_control_unavailable" : clockWrite.reason()));
                    session = session.withObservations(observations);
                    continue;
                }
                SampleSummary sample = collect(candidate, deadline);
                samplesByCandidate.put(candidate, sample);
                observations.add(new BayesianGpuOptimizer.Observation(candidate,
                        sample.stable() ? sample.hashrateHs() / sample.watts() : 0,
                        sample.stable(), sample.detail(), sample));
                session = session.withObservations(observations);
                if (sample.driverFailure()) {
                    if (!"auto_tuning_worker_stopped".equals(sample.detail()) || !recoverWorker(gpu, coin, originalPower))
                        throw new IllegalStateException(sample.detail());
                }
            }
            if (cancel.get()) throw new IllegalStateException("auto_tuning_cancelled");
            List<BayesianGpuOptimizer.Observation> tierBest = new ArrayList<>();
            for (int tierWatts : tiers) {
                BayesianGpuOptimizer.Observation bestAtTier = observations.stream()
                        .filter(row -> row.stable() && row.candidate().powerWatts() == tierWatts)
                        .max(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score)).orElse(null);
                if (bestAtTier == null) throw new IllegalStateException("auto_tuning_no_stable_tier");
                tierBest.add(bestAtTier);
            }
            BayesianGpuOptimizer.Observation best = tierBest.stream()
                    .max(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score)).orElseThrow();
            var candidate = best.candidate();
            SampleSummary finalSample = samplesByCandidate.get(candidate);
            if (finalSample == null) throw new IllegalStateException("auto_tuning_no_stable_candidate");
            List<AutoTuningProfileStore.Profile> matrix = tierBest.stream().map(row -> {
                var point = row.candidate(); var sample = samplesByCandidate.get(point);
                return new AutoTuningProfileStore.Profile(gpu.deviceId(), gpu.model(), gpu.vendor(), coin.id(),
                        coin.assignmentAlgorithm(), software, softwareVersion, point.coreMhz(), point.memoryMhz(),
                        point.powerWatts(), sample.hashrateHs(), sample.watts(), sample.samples(),
                        sample.hashrateHs() / sample.watts(), Instant.now());
            }).toList();
            if (!tuning.restoreDefaults(gpu.deviceId()).applied()
                    || !power.setAutoTuningPowerTarget(originalPower, gpu))
                throw new IllegalStateException("gpu_clock_restore_failed");
            if (!profiles.saveMatrix(matrix)) throw new IllegalStateException("auto_tuning_profile_save_failed");
            if (controls != null && modes != null
                    && "AUTO_TUNING".equals(controls.get().powerModeFor(gpu.deviceId()))) {
                var applied = modes.apply(gpu.deviceId(), coin.id());
                if (applied.error() != null) throw new IllegalStateException(applied.error());
            }
            AutoTuningProfileStore.Profile profile = matrix.stream().filter(row -> row.powerLimitWatts().equals(candidate.powerWatts()))
                    .findFirst().orElseThrow();
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

    private SampleSummary collect(BayesianGpuOptimizer.Candidate candidate, long deadline) throws InterruptedException {
        List<Double> rates = new ArrayList<>(), watts = new ArrayList<>(), temps = new ArrayList<>();
        List<Integer> observedCore = new ArrayList<>(), observedMemory = new ArrayList<>();
        Long acceptedBefore = null, rejectedBefore = null, acceptedNow = null, rejectedNow = null;
        for (int i = 0; i < SAMPLES_PER_CANDIDATE; i++) {
            if (cancel.get()) return SampleSummary.unstable("auto_tuning_cancelled", false);
            if (System.nanoTime() >= deadline) throw new IllegalStateException("auto_tuning_time_limit");
            Thread.sleep(sampleIntervalMillis);
            session = session.withSampleIndex(i + 1);
            MinerStats.Worker worker = worker(session.deviceId());
            if (worker == null || worker.miningStatus() != MinerStats.MinerStatus.MINING)
                return SampleSummary.unstable("auto_tuning_worker_stopped", true);
            double rate = worker.terahashPerSecond() * 1e12;
            double powerWatts = worker.approximatedPowerUsageWatts();
            LocalGpuPowerService.Gpu gpu = power.snapshot().stream()
                    .filter(value -> session.deviceId().equals(value.deviceId())).findFirst().orElse(null);
            Double temp = gpu == null ? null : power.readTemperatureC(gpu);
            if (!Double.isFinite(rate) || rate <= 0 || !Double.isFinite(powerWatts) || powerWatts <= 0)
                continue;
            if (temp == null || !Double.isFinite(temp))
                return SampleSummary.unstable("auto_tuning_temperature_unavailable", true);
            if (temp > maxTemperatureC)
                return SampleSummary.unstable("auto_tuning_temperature_limit", true);
            if ("NVIDIA".equalsIgnoreCase(gpu.vendor())) {
                GpuTuningService.ClockTelemetry clocks = tuning.readEffectiveClocks(gpu.deviceId());
                if (clocks == null) return SampleSummary.unstable("auto_tuning_clock_readback_unavailable", false);
                observedCore.add(clocks.coreMhz()); observedMemory.add(clocks.memoryMhz());
            }
            rates.add(rate); watts.add(powerWatts); temps.add(temp);
            if (acceptedBefore == null) acceptedBefore = worker.acceptedShares();
            acceptedNow = worker.acceptedShares(); rejectedNow = worker.rejectedShares();
            if (rejectedBefore == null) rejectedBefore = rejectedNow;
        }
        if (rates.size() < SAMPLES_PER_CANDIDATE - 2) return SampleSummary.unstable("auto_tuning_insufficient_samples", false);
        double median = SampleCollector.median(rates.stream().mapToDouble(Double::doubleValue).toArray());
        if (rates.stream().anyMatch(value -> value < median * .8 || value > median * 1.2))
            return SampleSummary.unstable("auto_tuning_unstable_hashrate", false);
        if (!observedCore.isEmpty() && (candidate.coreMhz() != null && observedCore.stream()
                .filter(value -> Math.abs(value - candidate.coreMhz()) > Math.max(75, candidate.coreMhz() * .12))
                .count() > 2 || candidate.memoryMhz() != null && observedMemory.stream()
                .filter(value -> Math.abs(value - candidate.memoryMhz()) > Math.max(100, candidate.memoryMhz() * .12))
                .count() > 2))
            return SampleSummary.unstable("auto_tuning_clock_not_reached", false);
        long accepted = acceptedNow == null || acceptedBefore == null ? 0 : Math.max(0, acceptedNow - acceptedBefore);
        long rejected = rejectedNow == null || rejectedBefore == null ? 0 : Math.max(0, rejectedNow - rejectedBefore);
        if (acceptedNow == null || rejectedNow == null || acceptedBefore == null || rejectedBefore == null)
            return SampleSummary.unstable("auto_tuning_share_counters_unavailable", false);
        if (accepted == 0 && rejected == 0)
            return SampleSummary.unstable("auto_tuning_no_share_evidence", false);
        if (rejected > 0)
            return SampleSummary.unstable("auto_tuning_rejected_share_limit", false);
        return new SampleSummary(true, median, watts.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                rates.size(), temps.stream().mapToDouble(Double::doubleValue).max().stream().boxed().findFirst().orElse(null),
                observedCore.isEmpty() ? null : (int) Math.round(observedCore.stream().mapToInt(Integer::intValue).average().orElse(0)),
                observedMemory.isEmpty() ? null : (int) Math.round(observedMemory.stream().mapToInt(Integer::intValue).average().orElse(0)),
                null, false);
    }

    private MinerStats.Worker worker(String deviceId) {
        return mining.getWorkerStats().stream().filter(value -> deviceId.equals(value.deviceId())).findFirst().orElse(null);
    }

    static List<BayesianGpuOptimizer.Candidate> candidates(LocalGpuPowerService.Gpu gpu,
                                                            GpuTuningService.Capabilities caps,
                                                            GpuTuningService.ClockTelemetry clockCeiling) {
        return candidates(gpu, caps, clockCeiling, gpu.currentPowerLimitWatts() == null
                ? gpu.maxWatts() : gpu.currentPowerLimitWatts());
    }

    static List<BayesianGpuOptimizer.Candidate> candidates(LocalGpuPowerService.Gpu gpu,
                                                            GpuTuningService.Capabilities caps,
                                                            GpuTuningService.ClockTelemetry clockCeiling,
                                                            int startWatts) {
        Integer safeMaxWatts = gpu.safeTuningMaxWatts();
        if (safeMaxWatts == null || safeMaxWatts < gpu.minWatts()) return List.of();
        List<Integer> cores = values(caps.coreClockControl(), caps.coreMinMhz(), caps.coreMaxMhz());
        List<Integer> memories = values(caps.memoryClockControl(), caps.memoryMinMhz(), caps.memoryMaxMhz());
        if (startWatts < gpu.minWatts() || startWatts > safeMaxWatts) return List.of();
        List<Integer> power = powerTiers(gpu.minWatts(), startWatts, safeMaxWatts);
        Set<BayesianGpuOptimizer.Candidate> unique = new LinkedHashSet<>();
        if (!caps.supportedPairs().isEmpty()) {
            int highestSupportedMemory = caps.supportedPairs().stream()
                    .mapToInt(GpuTuningService.ClockPair::memoryMhz)
                    .filter(value -> clockCeiling == null || value <= clockCeiling.memoryMhz())
                    .max().orElse(0);
            for (var pair : caps.supportedPairs()) for (int target : power) {
                if (clockCeiling != null && (pair.coreMhz() > clockCeiling.coreMhz()
                        || pair.memoryMhz() > clockCeiling.memoryMhz())) continue;
                if (!caps.memoryClockControl() && pair.memoryMhz() != highestSupportedMemory) continue;
                Integer memory = caps.memoryClockControl() ? pair.memoryMhz() : null;
                unique.add(new BayesianGpuOptimizer.Candidate(pair.coreMhz(), memory, target,
                        position(pair.coreMhz(), caps.coreMinMhz(), caps.coreMaxMhz()),
                        memory == null ? 0 : position(memory, caps.memoryMinMhz(), caps.memoryMaxMhz()),
                        position(target, gpu.minWatts(), safeMaxWatts)));
            }
            return List.copyOf(unique);
        }
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) {
            Integer core = caps.coreClockControl() ? cores.get(i) : null;
            Integer memory = caps.memoryClockControl() ? memories.get(j) : null;
            if (clockCeiling != null && (core != null && core > clockCeiling.coreMhz()
                    || memory != null && memory > clockCeiling.memoryMhz())) continue;
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
    }
    public record RunObservation(Integer coreMhz, Integer memoryMhz, int powerWatts, boolean stable,
                                 double score, int validSamples, Double hashrateHs, Double measuredWatts,
                                 Integer observedCoreMhz, Integer observedMemoryMhz, String detail) { }
    public record Session(boolean running, String phase, Instant startedAt, String deviceId, String model,
                          String coinId, String algorithm, String softwareId, int candidateIndex, int candidateCount,
                          Integer currentCoreMhz, Integer currentMemoryMhz, Integer currentPowerWatts,
                          int sampleIndex, int sampleCount, List<RunObservation> observations,
                          AutoTuningProfileStore.Profile result) {
        static Session idle() { return new Session(false, "idle", null, null, null, null, null, null, 0, 0,
                null, null, null, 0, 0, List.of(), null); }
        Session withPhase(String phase) { return new Session(true, phase, startedAt, deviceId, model, coinId, algorithm,
                softwareId, candidateIndex, candidateCount, currentCoreMhz, currentMemoryMhz, currentPowerWatts, sampleIndex, sampleCount, observations, result); }
        Session withCandidate(BayesianGpuOptimizer.Candidate c, int index) { return new Session(true, "auto_tuning_phase_sampling", startedAt,
                deviceId, model, coinId, algorithm, softwareId, index, candidateCount, c.coreMhz(), c.memoryMhz(), c.powerWatts(), 0, SAMPLES_PER_CANDIDATE, observations, result); }
        Session withSampleIndex(int value) { return new Session(running, phase, startedAt, deviceId, model, coinId, algorithm,
                softwareId, candidateIndex, candidateCount, currentCoreMhz, currentMemoryMhz, currentPowerWatts, value, sampleCount, observations, result); }
        Session withObservations(List<BayesianGpuOptimizer.Observation> rows) {
            List<RunObservation> values = rows.stream().map(row -> {
                SampleSummary sample = row.sample();
                return new RunObservation(row.candidate().coreMhz(), row.candidate().memoryMhz(), row.candidate().powerWatts(),
                        row.stable(), row.score(), sample == null ? 0 : sample.samples(),
                        sample == null || !sample.stable() ? null : sample.hashrateHs(),
                        sample == null || !sample.stable() ? null : sample.watts(),
                        sample == null ? null : sample.observedCoreMhz(),
                        sample == null ? null : sample.observedMemoryMhz(), row.detail());
            }).toList();
            return new Session(running, phase, startedAt, deviceId, model, coinId, algorithm, softwareId, candidateIndex,
                    candidateCount, currentCoreMhz, currentMemoryMhz, currentPowerWatts, sampleIndex, sampleCount, values, result);
        }
        Session completed(AutoTuningProfileStore.Profile profile, String phase) { return new Session(false, phase, startedAt, deviceId,
                model, coinId, algorithm, softwareId, candidateCount, candidateCount, profile.coreMhz(), profile.memoryMhz(),
                profile.powerLimitWatts(), sampleCount, sampleCount, observations, profile); }
        Session failed(String detail) { return new Session(false, detail, startedAt, deviceId, model, coinId, algorithm, softwareId,
                candidateIndex, candidateCount, currentCoreMhz, currentMemoryMhz, currentPowerWatts, sampleIndex, sampleCount, observations, result); }
    }
}
