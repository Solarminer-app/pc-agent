package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Refines each measured pair inside its sweep's device slot and exclusive run ownership. */
@Service
public class SweepRefinementService {
    private final AutoTuningSessionService tuner;
    private final MinerFactory miners;
    private final LocalGpuPowerService power;
    private final GpuTuningService clocks;
    private final WorkerBenchmarkPolicyService policies;
    private final AutoTuningProfileStore profiles;
    private final Map<String, Progress> progress = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService revenueModes;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRevenueModes(@org.springframework.context.annotation.Lazy de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService modes) { this.revenueModes = modes; }
    private de.verdox.solarminer.pcagent.mining.economics.MeasuredOperatingPointService operatingPoints;
    @org.springframework.beans.factory.annotation.Autowired
    public void setOperatingPoints(de.verdox.solarminer.pcagent.mining.economics.MeasuredOperatingPointService points) { this.operatingPoints = points; }

    public SweepRefinementService(@org.springframework.context.annotation.Lazy AutoTuningSessionService tuner,
            MinerFactory miners, LocalGpuPowerService power, GpuTuningService clocks, WorkerBenchmarkPolicyService policies, AutoTuningProfileStore profiles) {
        this.tuner = tuner; this.miners = miners; this.power = power; this.clocks = clocks; this.policies = policies; this.profiles = profiles;
    }
    void begin(boolean enabled, List<EfficiencySweepService.Target> targets, boolean resume) {
        var previous = Map.copyOf(progress);
        this.enabled = enabled; progress.clear();
        if (enabled) for (var target : targets) {
            var old = previous.get(id(target));
            var gpu = target.gpu();
            if (resume && old != null && "COMPLETE".equals(old.state())
                    && !profiles.operatingCandidates(gpu.deviceId(), target.coin(), target.softwareId(),
                    MinerFactory.minerVersionForSoftware(target.softwareId()), gpu.model(), gpu.vendor(),
                    Coin.byIdOrNull(target.coin()).assignmentAlgorithm(), gpu.maxWatts(), policies.ceiling(gpu.deviceId(), 85)).isEmpty())
                progress.put(id(target), old);
            else put(target, "WAITING", "benchmark_optimization_waiting");
        }
    }
    public Status status() {
        var rows = progress.values().stream().map(row -> {
            var session = "RUNNING".equals(row.state()) ? tuner.sessionForDevice(row.deviceId()) : null;
            return session != null && session.running() && row.coin().equals(session.coinId()) && row.softwareId().equals(session.softwareId())
                    ? new Progress(row.id(), row.deviceId(), row.coin(), row.softwareId(), row.state(), session.phase(),
                    session.candidateIndex(), session.candidateCount(), session.sampleIndex(), session.sampleCount()) : row;
        }).sorted(Comparator.comparing(Progress::id)).toList();
        return new Status(enabled, rows);
    }
    boolean incomplete() { return enabled && progress.values().stream().anyMatch(p -> !"COMPLETE".equals(p.state())); }
    void finishPending() {
        progress.replaceAll((id, row) -> Set.of("WAITING", "RUNNING").contains(row.state())
                ? new Progress(id, row.deviceId(), row.coin(), row.softwareId(), "CANCELLED", "auto_tuning_cancelled", 0, 0, 0, 0) : row);
    }
    private void put(EfficiencySweepService.Target target, String state, String detail) {
        progress.put(id(target), new Progress(id(target), target.gpu().deviceId(), target.coin(), target.softwareId(), state, detail, 0, 0, 0, 0));
    }
    private static String id(EfficiencySweepService.Target target) { return target.gpu().deviceId() + "|" + target.coin() + "|" + target.softwareId(); }

    void resetBeforeCurve(EfficiencySweepService.Target target) {
        var gpu = target.gpu();
        if (operatingPoints != null) operatingPoints.invalidate(gpu.deviceId());
        if (revenueModes != null) revenueModes.resetCooldown(gpu.deviceId());
        boolean restored = true;
        for (Coin coin : Coin.gpuCoins()) for (var adapter : miners.gpuMinerAdapters(coin))
            if (adapter.miner() instanceof MinerNativeTuning nativeTuning) restored = nativeTuning.clearNativeTuning(gpu) && restored;
        var caps = clocks.refreshCapabilities(gpu.deviceId());
        if (caps.coreClockControl() || caps.memoryClockControl() || clocks.memoryOffsetRange(gpu.deviceId()) != null)
            restored = clocks.restoreDefaults(gpu.deviceId()).applied() && restored;
        if (!restored) throw new IllegalStateException("gpu_clock_restore_failed");
    }
    void failed(EfficiencySweepService.Target target, String detail) { if (enabled) put(target, "FAILED", detail); }

    /** Caller retains the physical-device slot until synchronous tuning and cleanup finish. */
    boolean refine(EfficiencySweepService.Target target, GpuEfficiencyStore.Profile baseline, BooleanSupplier cancelled) {
        if (!enabled) return false;
        if (progress.get(id(target)) != null && "COMPLETE".equals(progress.get(id(target)).state())) return false;
        if (baseline == null || baseline.bestStableWatts() == null) {
            put(target, "SKIPPED", "benchmark_optimization_no_stable_curve"); return false;
        }
        if (cancelled.getAsBoolean()) { put(target, "CANCELLED", "auto_tuning_cancelled"); return false; }
        var gpu = target.gpu();
        var miner = miners.gpuMiner(Coin.byIdOrNull(target.coin()), target.softwareId());
        String state = "FAILED", detail = "auto_tuning_failed";
        boolean unsafe = false, tuningStarted = false;
        try {
            var caps = clocks.refreshCapabilities(gpu.deviceId());
            if (!caps.coreClockControl() && !caps.memoryClockControl() && clocks.memoryOffsetRange(gpu.deviceId()) == null) {
                throw new IllegalStateException("auto_tuning_selected_control_unavailable");
            }
            miners.setMeasurementContext(gpu.deviceId(), target.coin(), target.softwareId(), "AUTO_TUNING");
            if (!power.setSweepPowerTarget(baseline.bestStableWatts(), gpu)) throw new IllegalStateException("power_mode_apply_failed");
            if (!miner.resumeGpu(gpu.vendor(), gpu.index()))
                throw new IllegalStateException(Objects.toString(miner.lastError(), "auto_tuning_requires_active_worker"));
            Instant deadline = Instant.now().plusSeconds(240);
            put(target, "RUNNING", "benchmark_optimization_starting");
            while (!cancelled.getAsBoolean()) {
                Double temperature = power.readTemperatureC(gpu);
                if (!policies.temperatureSafe(gpu.deviceId(), temperature, 85)) throw new IllegalStateException(
                        temperature == null || !Double.isFinite(temperature) || temperature <= 0
                                ? "auto_tuning_temperature_unavailable" : "auto_tuning_temperature_limit");
                boolean mining = miner.workerStats(power.refresh()).stream().anyMatch(worker ->
                        gpu.deviceId().equals(worker.deviceId()) && worker.miningStatus() == MinerStats.MinerStatus.MINING
                                && Coin.byIdOrNull(target.coin()).matchesAlgorithm(worker.currentAlgorithm())
                                && SampleCollector.hasValidHashrate(worker));
                if (mining) break;
                if (!Instant.now().isBefore(deadline)) throw new IllegalStateException("auto_tuning_requires_active_worker");
                Thread.sleep(500);
            }
            if (cancelled.getAsBoolean()) throw new IllegalStateException("auto_tuning_cancelled");
            AutoTuningSessionService.Session result;
            while (true) {
                if (cancelled.getAsBoolean()) throw new IllegalStateException("auto_tuning_cancelled");
                try {
                    tuningStarted = true;
                    result = tuner.refineWithinSweep(gpu.deviceId(), target.coin(), baseline.bestStableWatts(), cancelled);
                    break;
                } catch (IllegalStateException capacity) {
                    if (!"auto_tuning_capacity_reached".equals(capacity.getMessage())) throw capacity;
                    Thread.sleep(500);
                }
            }
            detail = result.phase();
            state = "auto_tuning_complete".equals(detail) && result.result() != null ? "COMPLETE"
                    : cancelled.getAsBoolean() ? "CANCELLED" : "FAILED";
            unsafe = Set.of("auto_tuning_temperature_limit", "auto_tuning_temperature_unavailable", "auto_tuning_gpu_unresponsive",
                    "gpu_clock_restore_failed", "auto_tuning_gpu_reset_failed", "auto_tuning_gpu_requires_attention").contains(detail);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            detail = Objects.toString(failure.getMessage(), "auto_tuning_failed");
            state = cancelled.getAsBoolean() || failure instanceof InterruptedException ? "CANCELLED"
                    : "auto_tuning_selected_control_unavailable".equals(detail) ? "SKIPPED" : "FAILED";
            unsafe = detail.contains("temperature") || detail.contains("gpu_reset") || detail.contains("clock_restore") || detail.contains("unresponsive");
        } finally {
            // Always stop before removing native settings: otherwise the adapter would restart.
            try {
                if (!miner.stopGpu(gpu.vendor(), gpu.index())) { unsafe = true; state = "FAILED"; detail = "benchmark_stop_failed"; }
                else if (tuningStarted) {
                    boolean restored = !(miner instanceof MinerNativeTuning nativeTuning) || nativeTuning.clearNativeTuning(gpu);
                    restored = clocks.restoreDefaults(gpu.deviceId()).applied() && restored;
                    if (!restored) { unsafe = true; state = "FAILED"; detail = "gpu_clock_restore_failed"; }
                }
            } catch (RuntimeException failure) { unsafe = true; state = "FAILED"; detail = "gpu_clock_restore_failed"; }
            miners.clearBenchmarkSoftware(gpu.deviceId());
            put(target, state, detail);
        }
        return unsafe;
    }
    public record Progress(String id, String deviceId, String coin, String softwareId, String state, String detail,
                           int candidateIndex, int candidateCount, int sampleIndex, int sampleCount) { }
    public record Status(boolean enabled, List<Progress> runs) { }
}
