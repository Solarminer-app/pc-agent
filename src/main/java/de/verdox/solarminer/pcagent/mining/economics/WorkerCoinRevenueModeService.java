package de.verdox.solarminer.pcagent.mining.economics;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-worker coin and power-point optimization using the existing measured hopping candidates. */
@Service
public class WorkerCoinRevenueModeService {
    private static final Logger log = LoggerFactory.getLogger(WorkerCoinRevenueModeService.class);
    private static final Duration COOLDOWN = Duration.ofMinutes(30);
    private static final double MIN_GAIN = 0.05;
    private final AgentControlSettingsService controls;
    private final CoinHoppingService hopping;
    private final WorkerAssignmentService assignments;
    private final LocalGpuPowerService gpus;
    private final LocalRunLock runLock;
    private final EconomicPlanningService planning;
    private final Map<String, Instant> switchedAt = new ConcurrentHashMap<>();
    private final java.util.Set<String> locallyStarted = ConcurrentHashMap.newKeySet();

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
            try { evaluateWorker(entry.getKey()); }
            catch (RuntimeException failure) { log.warn("Worker revenue evaluation failed for {}", entry.getKey(), failure); }
        }
    }

    private void evaluateWorker(String id) {
        Instant now = Instant.now();
        if (planning.capabilities().activePlanExpiresAt() != null) return;
        WorkerAssignmentService.WorkerView worker = assignments.workers().stream()
                .filter(value -> id.equals(value.deviceId())).findFirst().orElse(null);
        if (worker == null) return;
        if (worker.status() != MinerStats.MinerStatus.MINING) {
            if (locallyStarted.contains(id) && runLock.tryBegin("worker-coin-revenue-recovery")) {
                try {
                    if (locallyStarted.contains(id) && "COIN_REVENUE_PER_KWH".equals(controls.get().powerModeFor(id))) {
                        log.warn("Worker {} stopped unexpectedly; attempting to resume the best measured coin", id);
                        StartResult recovery = applyBestStartPoint(id, worker.coin(), "COIN_REVENUE_PER_KWH", true, true);
                        if (!recovery.success()) log.warn("Worker {} could not resume: {}", id, recovery.error());
                    }
                } finally { runLock.end(); }
            }
            return;
        }
        if (switchedAt.containsKey(id) && now.isBefore(switchedAt.get(id).plus(COOLDOWN))) return;
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
                if (!best.coin().equals(current.coin()) && !assignments.applyEconomicAssignment(id, best.coin())) {
                    log.warn("Worker {} could not stop and assign miner {} for revenue switch", id, best.coin());
                    return;
                }
                boolean applied = gpus.setTotalPowerTarget(best.limitWatts(), List.of(gpu));
                if (applied && !best.coin().equals(current.coin())) applied = assignments.startAtCurrentPowerTarget(id);
                if (applied) {
                    log.info("Worker {} selected {} at {} W instead of {} at {} W for higher measured USD/kWh",
                            id, best.coin(), best.limitWatts(), current.coin(), current.limitWatts());
                    switchedAt.put(id, Instant.now());
                    return;
                }
            } catch (RuntimeException failure) {
                log.warn("Worker {} could not switch from {} to {}; restoring the previous miner", id, current.coin(), best.coin(), failure);
            }
            try {
                boolean restored = best.coin().equals(current.coin()) || assignments.applyEconomicAssignment(id, current.coin());
                if (!restored)
                    log.error("Worker {} could not restore assignment {}", id, current.coin());
                if (!gpus.setTotalPowerTarget(current.limitWatts(), List.of(gpu)))
                    log.error("Worker {} could not restore power target {} W", id, current.limitWatts());
                if (restored && !best.coin().equals(current.coin()) && !assignments.startAtCurrentPowerTarget(id))
                    log.error("Worker {} could not restart previous miner {}", id, current.coin());
            } catch (RuntimeException failure) { log.error("Worker {} could not restore miner {}", id, current.coin(), failure); }
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

    /** An explicit operator pause or reassignment cancels automatic process recovery. */
    public void cancelLocalStart(String workerId) { locallyStarted.remove(workerId); }

    public void cancelLocalStartsForCoin(String coinId) {
        assignments.workers().stream().filter(worker -> coinId.equals(worker.coin()))
                .map(WorkerAssignmentService.WorkerView::deviceId).forEach(this::cancelLocalStart);
    }

    public void cancelAllLocalStarts() { locallyStarted.clear(); }

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
        return applyBestStartPoint(workerId, assignedCoin, mode, startWorker, false);
    }

    /** The mode-change controller already owns the run lock. */
    public StartResult applyBestStartPoint(String workerId, String assignedCoin, String mode,
                                           boolean startWorker, boolean lockHeld) {
        StartSelection selection = selectBestStartPoint(workerId, assignedCoin, mode);
        if (selection.error() != null) return new StartResult(false, selection.error(), null, null);
        if (!lockHeld && !runLock.tryBegin("worker-revenue-start"))
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
                if (startWorker || wasMining) locallyStarted.add(workerId);
                switchedAt.put(workerId, Instant.now());
                return new StartResult(true, null, targetCoin, selection.candidate().limitWatts());
            } catch (RuntimeException failure) {
                log.warn("Worker {} could not apply revenue point {} at {} W; restoring {} at {} W",
                        workerId, targetCoin, selection.candidate().limitWatts(), oldCoin, oldLimit, failure);
                boolean restored = !assignmentChanged || assignments.applyEconomicAssignment(workerId, oldCoin);
                if (!restored)
                    log.error("Worker {} could not restore assignment {}", workerId, oldCoin);
                if (!gpus.setTotalPowerTarget(oldLimit, List.of(gpu)))
                    log.error("Worker {} could not restore power target {} W", workerId, oldLimit);
                if (wasMining && assignmentChanged && restored && !assignments.startAtCurrentPowerTarget(workerId))
                    log.error("Worker {} could not restart the previous miner {}", workerId, oldCoin);
                return new StartResult(false, "power_mode_apply_failed", null, null);
            }
        } finally { if (!lockHeld) runLock.end(); }
    }

    public record StartSelection(CoinHoppingService.Candidate candidate, String error) { }
    public record StartResult(boolean success, String error, String coin, Integer watts) { }
}
