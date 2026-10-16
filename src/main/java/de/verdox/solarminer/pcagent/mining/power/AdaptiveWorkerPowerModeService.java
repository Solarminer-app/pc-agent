package de.verdox.solarminer.pcagent.mining.power;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Rechecks measured fixed-coin GPU modes while mining, with a gain gate and cooldown. */
@Service
public class AdaptiveWorkerPowerModeService {
    private static final double MIN_GAIN = 0.05;
    private static final Duration COOLDOWN = Duration.ofMinutes(30);

    private final AgentControlSettingsService controls;
    private final WorkerAssignmentService assignments;
    private final WorkerPowerModeService modes;
    private final LocalGpuPowerService gpus;
    private final MiningPerformanceProfileStore profiles;
    private final GpuEfficiencyStore sweeps;
    private final LocalRunLock runLock;
    private final Map<String, Instant> adjustedAt = new HashMap<>();

    public AdaptiveWorkerPowerModeService(AgentControlSettingsService controls, WorkerAssignmentService assignments,
                                          WorkerPowerModeService modes, LocalGpuPowerService gpus,
                                          MiningPerformanceProfileStore profiles, GpuEfficiencyStore sweeps,
                                          LocalRunLock runLock) {
        this.controls = controls;
        this.assignments = assignments;
        this.modes = modes;
        this.gpus = gpus;
        this.profiles = profiles;
        this.sweeps = sweeps;
        this.runLock = runLock;
    }

    @Scheduled(initialDelayString = "${solarminer.agent.worker-power-retune-initial-delay-ms:60000}",
            fixedDelayString = "${solarminer.agent.worker-power-retune-interval-ms:60000}")
    public void evaluate() {
        Instant now = Instant.now();
        for (WorkerAssignmentService.WorkerView worker : assignments.workers()) {
            try { evaluateWorker(worker, now); }
            catch (RuntimeException ignored) { /* A failed optimization must leave mining untouched. */ }
        }
    }

    void evaluateWorker(WorkerAssignmentService.WorkerView worker, Instant now) {
        String mode = controls.get().powerModeFor(worker.deviceId());
        if (!("EFFICIENCY".equals(mode) || "REVENUE_PER_KWH".equals(mode))
                || !"GPU".equals(worker.hardwareType()) || worker.status() != MinerStats.MinerStatus.MINING
                || worker.telemetry() == null || worker.deviceId() == null) return;
        Coin coin = Coin.byIdOrNull(worker.coin());
        if (coin == null || !coin.isGpu() || !coin.matchesAlgorithm(worker.telemetry().currentAlgorithm())) return;
        Instant last = adjustedAt.get(worker.deviceId());
        if (last != null && now.isBefore(last.plus(COOLDOWN))) return;
        LocalGpuPowerService.Gpu gpu = gpus.snapshot().stream()
                .filter(card -> worker.deviceId().equals(card.deviceId())).findFirst().orElse(null);
        if (gpu == null || !gpu.supportsDynamicPowerScaling() || gpu.currentPowerLimitWatts() == null) return;
        WorkerPowerModeService.Selection choice = modes.selectForMode(worker.deviceId(), coin.id(), mode);
        if (choice.error() != null || choice.watts() == null || choice.watts().equals(gpu.currentPowerLimitWatts())) return;
        Double currentScore = score(worker.deviceId(), coin, gpu.currentPowerLimitWatts());
        Double bestScore = score(worker.deviceId(), coin, choice.watts());
        if (!worthChanging(currentScore, bestScore)) return;
        if (!runLock.tryBegin("worker-power-retune")) return;
        try {
            // Recheck the mode and live ownership after acquiring the measurement lock.
            if (!mode.equals(controls.get().powerModeFor(worker.deviceId()))) return;
            boolean stillMining = assignments.workers().stream().anyMatch(live ->
                    worker.deviceId().equals(live.deviceId()) && live.status() == MinerStats.MinerStatus.MINING
                            && live.telemetry() != null && coin.matchesAlgorithm(live.telemetry().currentAlgorithm()));
            if (!stillMining) return;
            LocalGpuPowerService.Gpu fresh = gpus.refresh().stream()
                    .filter(card -> worker.deviceId().equals(card.deviceId())).findFirst().orElse(null);
            if (fresh == null || fresh.currentPowerLimitWatts() == null
                    || !fresh.currentPowerLimitWatts().equals(gpu.currentPowerLimitWatts())) return;
            if (gpus.setTotalPowerTarget(choice.watts(), List.of(fresh))) adjustedAt.put(worker.deviceId(), now);
        } finally { runLock.end(); }
    }

    static boolean worthChanging(Double current, Double candidate) {
        return current != null && candidate != null && Double.isFinite(current) && Double.isFinite(candidate)
                && current > 0 && candidate > current * (1 + MIN_GAIN);
    }

    private Double score(String deviceId, Coin coin, int watts) {
        Double measured = profiles.all().stream()
                .filter(profile -> deviceId.equals(profile.workerId()) && coin.id().equals(profile.coin())
                        && coin.matchesAlgorithm(profile.algorithm()) && Integer.valueOf(watts).equals(profile.powerTargetWatts())
                        && profile.observations() >= 12 && profile.hashrateHps() > 0 && profile.powerWatts() > 0)
                .mapToDouble(profile -> profile.hashrateHps() / profile.powerWatts()).max().stream().boxed().findFirst().orElse(null);
        if (measured != null) return measured;
        GpuEfficiencyStore.Profile sweep = sweeps.find(deviceId, coin.assignmentAlgorithm());
        if (sweep == null || !coin.id().equals(sweep.coin()) || sweep.steps() == null) return null;
        return sweep.steps().stream().filter(step -> step.limitWatts() == watts && step.stable()
                        && step.medianHashrateHs() != null && step.avgPowerWatts() != null
                        && step.medianHashrateHs() > 0 && step.avgPowerWatts() > 0)
                .mapToDouble(step -> step.medianHashrateHs() / step.avgPowerWatts()).max().stream().boxed().findFirst().orElse(null);
    }
}
