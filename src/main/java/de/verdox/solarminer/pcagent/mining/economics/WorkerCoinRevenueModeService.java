package de.verdox.solarminer.pcagent.mining.economics;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-worker coin and power-point optimization using the existing measured hopping candidates. */
@Service
public class WorkerCoinRevenueModeService {
    private static final Duration COOLDOWN = Duration.ofMinutes(30);
    private static final double MIN_GAIN = 0.05;
    private final AgentControlSettingsService controls;
    private final CoinHoppingService hopping;
    private final WorkerAssignmentService assignments;
    private final LocalGpuPowerService gpus;
    private final LocalRunLock runLock;
    private final EconomicPlanningService planning;
    private final Map<String, Instant> switchedAt = new ConcurrentHashMap<>();

    public WorkerCoinRevenueModeService(AgentControlSettingsService controls, CoinHoppingService hopping,
                                        WorkerAssignmentService assignments, LocalGpuPowerService gpus, LocalRunLock runLock,
                                        EconomicPlanningService planning) {
        this.controls = controls;
        this.hopping = hopping;
        this.assignments = assignments;
        this.gpus = gpus;
        this.runLock = runLock;
        this.planning = planning;
    }

    @Scheduled(initialDelayString = "${solarminer.agent.worker-revenue-initial-delay-ms:15000}",
            fixedDelayString = "${solarminer.agent.worker-revenue-interval-ms:60000}")
    public void evaluate() {
        for (Map.Entry<String, String> entry : controls.get().workerPowerModes().entrySet()) {
            if (!"COIN_REVENUE_PER_KWH".equals(entry.getValue())) continue;
            try { evaluateWorker(entry.getKey()); } catch (RuntimeException ignored) { /* Leave the running point unchanged. */ }
        }
    }

    private void evaluateWorker(String id) {
        Instant now = Instant.now();
        if (planning.capabilities().activePlanExpiresAt() != null) return;
        if (switchedAt.containsKey(id) && now.isBefore(switchedAt.get(id).plus(COOLDOWN))) return;
        WorkerAssignmentService.WorkerView worker = assignments.workers().stream()
                .filter(value -> id.equals(value.deviceId())).findFirst().orElse(null);
        if (worker == null || worker.status() != MinerStats.MinerStatus.MINING) return;
        LocalGpuPowerService.Gpu gpu = gpus.snapshot().stream()
                .filter(value -> id.equals(value.deviceId())).findFirst().orElse(null);
        if (gpu == null || gpu.currentPowerLimitWatts() == null) return;
        List<CoinHoppingService.Candidate> choices = hopping.candidates(id, 0);
        if (choices.size() < 2) return;
        CoinHoppingService.Candidate current = choices.stream().filter(value -> worker.coin().equals(value.coin())
                && gpu.currentPowerLimitWatts() == value.limitWatts()).findFirst().orElse(null);
        CoinHoppingService.Candidate best = choices.getFirst();
        if (current == null || best.usdPerKwh() <= current.usdPerKwh() * (1 + MIN_GAIN)) return;
        if (!runLock.tryBegin("worker-coin-revenue")) return;
        try {
            if (!"COIN_REVENUE_PER_KWH".equals(controls.get().powerModeFor(id))) return;
            try {
                if (!best.coin().equals(current.coin()) && !assignments.applyEconomicAssignment(id, best.coin())) return;
                boolean applied = gpus.setTotalPowerTarget(best.limitWatts(), List.of(gpu));
                if (applied && !best.coin().equals(current.coin())) applied = assignments.startAtCurrentPowerTarget(id);
                if (applied) {
                    switchedAt.put(id, Instant.now());
                    return;
                }
            } catch (RuntimeException ignored) {
                // Best-effort restoration below also covers a failed miner start.
            }
            try {
                if (!best.coin().equals(current.coin())) assignments.applyEconomicAssignment(id, current.coin());
                gpus.setTotalPowerTarget(current.limitWatts(), List.of(gpu));
                if (!best.coin().equals(current.coin())) assignments.startAtCurrentPowerTarget(id);
            } catch (RuntimeException ignored) { /* The miner remains stopped on a failed recovery. */ }
        } finally { runLock.end(); }
    }

    /** A local operator stop ends the previous automatic decision cycle for this worker. */
    public void resetCooldown(String workerId) {
        if (workerId != null) switchedAt.remove(workerId);
    }

    public void resetCooldownForCoin(String coinId) {
        assignments.workers().stream().filter(worker -> coinId.equals(worker.coin()))
                .map(WorkerAssignmentService.WorkerView::deviceId).forEach(this::resetCooldown);
    }

    public void resetAllCooldowns() {
        switchedAt.clear();
    }

    public StartSelection selectBestStartPoint(String workerId, String assignedCoin, String mode) {
        List<CoinHoppingService.Candidate> choices = hopping.candidates(workerId, 0);
        if ("REVENUE_PER_KWH".equals(mode))
            choices = choices.stream().filter(candidate -> assignedCoin.equals(candidate.coin())).toList();
        else if (!"COIN_REVENUE_PER_KWH".equals(mode))
            return new StartSelection(null, "power_mode_invalid");
        return choices.isEmpty()
                ? new StartSelection(null, "power_mode_no_measured_point")
                : new StartSelection(choices.getFirst(), null);
    }

    /** Applies the best measured revenue point and optionally starts/restarts its miner. */
    public StartResult applyBestStartPoint(String workerId, String assignedCoin, String mode, boolean startWorker) {
        StartSelection selection = selectBestStartPoint(workerId, assignedCoin, mode);
        if (selection.error() != null) return new StartResult(false, selection.error(), null, null);
        if (planning.capabilities().activePlanExpiresAt() != null || !runLock.tryBegin("worker-revenue-start"))
            return new StartResult(false, "power_mode_busy", null, null);
        try {
            WorkerAssignmentService.WorkerView worker = assignments.workers().stream()
                    .filter(value -> workerId.equals(value.deviceId())).findFirst().orElse(null);
            LocalGpuPowerService.Gpu gpu = gpus.snapshot().stream()
                    .filter(value -> workerId.equals(value.deviceId())).findFirst().orElse(null);
            if (worker == null || gpu == null || gpu.currentPowerLimitWatts() == null)
                return new StartResult(false, "gpu_not_found", null, null);
            String oldCoin = worker.coin();
            int oldLimit = gpu.currentPowerLimitWatts();
            boolean wasMining = worker.status() == MinerStats.MinerStatus.MINING;
            String targetCoin = selection.candidate().coin();
            boolean coinChanged = !targetCoin.equals(oldCoin);
            boolean assignmentChanged = false;
            try {
                if (coinChanged) {
                    if (!assignments.applyEconomicAssignment(workerId, targetCoin))
                        return new StartResult(false, "power_mode_apply_failed", null, null);
                    assignmentChanged = true;
                }
                if (!gpus.setTotalPowerTarget(selection.candidate().limitWatts(), List.of(gpu)))
                    throw new IllegalStateException("power_mode_apply_failed");
                if ((startWorker || (wasMining && coinChanged)) && !assignments.startAtCurrentPowerTarget(workerId))
                    throw new IllegalStateException("power_mode_apply_failed");
                switchedAt.put(workerId, Instant.now());
                return new StartResult(true, null, targetCoin, selection.candidate().limitWatts());
            } catch (RuntimeException failure) {
                if (assignmentChanged) assignments.applyEconomicAssignment(workerId, oldCoin);
                gpus.setTotalPowerTarget(oldLimit, List.of(gpu));
                if (wasMining && assignmentChanged) assignments.startAtCurrentPowerTarget(workerId);
                return new StartResult(false, "power_mode_apply_failed", null, null);
            }
        } finally { runLock.end(); }
    }

    public record StartSelection(CoinHoppingService.Candidate candidate, String error) { }
    public record StartResult(boolean success, String error, String coin, Integer watts) { }
}
