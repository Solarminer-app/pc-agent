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
            if (!scheduledMode(entry.getValue())) continue;
            try { evaluateWorker(entry.getKey()); }
            catch (RuntimeException failure) { log.warn("Worker revenue evaluation failed for {}", entry.getKey(), failure); }
        }
    }

    private void evaluateWorker(String id) {
        Instant now = Instant.now();
        String mode = controls.get().powerModeFor(id);
        boolean dailyProfitMode = "PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode);
        boolean fixedCoinMode = "PROFIT_PER_DAY".equals(mode);
        if (planning.capabilities().activePlanExpiresAt() != null) return;
        WorkerAssignmentService.WorkerView worker = assignments.workers().stream()
                .filter(value -> id.equals(value.deviceId())).findFirst().orElse(null);
        if (worker == null) return;
        if (worker.status() != MinerStats.MinerStatus.MINING) {
            if (locallyStarted.contains(id) && runLock.tryBegin("worker-coin-revenue-recovery")) {
                try {
                    String currentMode = controls.get().powerModeFor(id);
                    if (locallyStarted.contains(id) && automaticRevenueMode(currentMode)
                            && !"REVENUE_PER_KWH".equals(currentMode)) {
                        log.warn("Worker {} stopped unexpectedly; attempting to resume the best measured coin", id);
                        StartResult recovery = applyBestStartPoint(id, worker.coin(), currentMode, true, true);
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
        List<CoinHoppingService.Candidate> choices = hopping.candidates(id, 0).stream()
                .filter(value -> !dailyProfitMode || value.profitUsdPerDay() != null)
                .filter(value -> !fixedCoinMode || worker.coin().equals(value.coin()))
                .filter(value -> !"PROFIT_PER_DAY".equals(mode)
                        || java.util.Objects.equals(worker.minerSoftwareId(), value.minerSoftwareId()))
                .toList();
        if (choices.isEmpty() || "COIN_REVENUE_PER_KWH".equals(mode) && choices.size() < 2) return;
        CoinHoppingService.Candidate current = choices.stream().filter(value -> worker.coin().equals(value.coin())
                && java.util.Objects.equals(worker.minerSoftwareId(), value.minerSoftwareId())
                && gpu.currentPowerLimitWatts() == value.limitWatts()).findFirst().orElse(null);
        CoinHoppingService.Candidate best = bestCandidate(choices, mode);
        if (best == null) return;
        boolean samePoint = worker.coin().equals(best.coin())
                && java.util.Objects.equals(worker.minerSoftwareId(), best.minerSoftwareId())
                && gpu.currentPowerLimitWatts() == best.limitWatts();
        if (samePoint || current != null && !worthSwitching(score(current, mode), score(best, mode))) return;
        if (!runLock.tryBegin("worker-coin-revenue")) return;
        try {
            if (!mode.equals(controls.get().powerModeFor(id))) return;
            try {
                boolean minerChanged = !java.util.Objects.equals(best.minerSoftwareId(), worker.minerSoftwareId());
                boolean fixedDailyProfit = "PROFIT_PER_DAY".equals(mode);
                if (!fixedDailyProfit && (!best.coin().equals(worker.coin()) || minerChanged)
                        && !assignments.applyEconomicAssignment(id, best.coin(), best.minerSoftwareId())) {
                    throw new IllegalStateException("Could not assign the selected miner");
                }
                boolean applied = gpus.setTotalPowerTarget(best.limitWatts(), List.of(gpu));
                if (applied && !fixedDailyProfit && (!best.coin().equals(worker.coin()) || minerChanged))
                    applied = assignments.startAtCurrentPowerTarget(id);
                if (applied) {
                    log.info("Worker {} selected {} at {} W instead of {} at {} W for mode {}",
                            id, best.coin(), best.limitWatts(), worker.coin(), gpu.currentPowerLimitWatts(), mode);
                    switchedAt.put(id, Instant.now());
                    return;
                }
            } catch (RuntimeException failure) {
                log.warn("Worker {} could not switch from {} to {}; restoring the previous miner", id, worker.coin(), best.coin(), failure);
            }
            try {
                boolean changed = !"PROFIT_PER_DAY".equals(mode) && (!best.coin().equals(worker.coin())
                        || !java.util.Objects.equals(best.minerSoftwareId(), worker.minerSoftwareId()));
                boolean restored = !changed || assignments.applyEconomicAssignment(id, worker.coin(), worker.minerSoftwareId());
                if (!restored)
                    log.error("Worker {} could not restore assignment {}", id, worker.coin());
                if (!gpus.setTotalPowerTarget(gpu.currentPowerLimitWatts(), List.of(gpu)))
                    log.error("Worker {} could not restore power target {} W", id, gpu.currentPowerLimitWatts());
                if (restored && changed && !assignments.startAtCurrentPowerTarget(id))
                    log.error("Worker {} could not restart previous miner {}", id, worker.coin());
            } catch (RuntimeException failure) { log.error("Worker {} could not restore miner {}", id, worker.coin(), failure); }
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
        if ("REVENUE_PER_KWH".equals(mode) || "PROFIT_PER_DAY".equals(mode))
            choices = choices.stream().filter(candidate -> assignedCoin.equals(candidate.coin())).toList();
        else if (!"COIN_REVENUE_PER_KWH".equals(mode) && !"COIN_PROFIT_PER_DAY".equals(mode))
            return new StartSelection(null, "power_mode_invalid");
        if ("PROFIT_PER_DAY".equals(mode)) {
            WorkerAssignmentService.WorkerView worker = assignments.workers().stream()
                    .filter(value -> workerId.equals(value.deviceId())).findFirst().orElse(null);
            if (worker != null) choices = choices.stream()
                    .filter(candidate -> java.util.Objects.equals(worker.minerSoftwareId(), candidate.minerSoftwareId())).toList();
        }
        if ("PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode))
            choices = choices.stream().filter(candidate -> candidate.profitUsdPerDay() != null).toList();
        CoinHoppingService.Candidate best = bestCandidate(choices, mode);
        return best == null
                ? new StartSelection(null, dailyProfitMode(mode) ? "power_mode_profit_unavailable" : "power_mode_no_measured_point")
                : new StartSelection(best, null);
    }

    private static boolean dailyProfitMode(String mode) {
        return "PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode);
    }

    private static boolean automaticRevenueMode(String mode) {
        return scheduledMode(mode);
    }

    private static boolean scheduledMode(String mode) {
        return "COIN_REVENUE_PER_KWH".equals(mode) || "PROFIT_PER_DAY".equals(mode)
                || "COIN_PROFIT_PER_DAY".equals(mode);
    }

    private static CoinHoppingService.Candidate bestCandidate(List<CoinHoppingService.Candidate> candidates, String mode) {
        if (candidates.isEmpty()) return null;
        if ("PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode))
            return candidates.stream().filter(candidate -> candidate.profitUsdPerDay() != null)
                    .max(java.util.Comparator.comparingDouble(CoinHoppingService.Candidate::profitUsdPerDay)).orElse(null);
        return candidates.stream().max(java.util.Comparator.comparingDouble(CoinHoppingService.Candidate::usdPerKwh)).orElse(null);
    }

    private static double score(CoinHoppingService.Candidate candidate, String mode) {
        return "PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode)
                ? candidate.profitUsdPerDay() : candidate.usdPerKwh();
    }

    private static boolean worthSwitching(double current, double candidate) {
        if (!Double.isFinite(current) || !Double.isFinite(candidate) || candidate <= current) return false;
        return current <= 0 ? true : candidate > current * (1 + MIN_GAIN);
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
            String oldMiner = worker.minerSoftwareId();
            int oldLimit = gpu.currentPowerLimitWatts();
            boolean wasMining = worker.status() == MinerStats.MinerStatus.MINING;
            String targetCoin = selection.candidate().coin();
            boolean coinChanged = !targetCoin.equals(oldCoin);
            boolean minerChanged = !java.util.Objects.equals(selection.candidate().minerSoftwareId(), oldMiner);
            boolean assignmentChanged = false;
            try {
                if (coinChanged || minerChanged) {
                    if (!assignments.applyEconomicAssignment(workerId, targetCoin, selection.candidate().minerSoftwareId()))
                        return new StartResult(false, "power_mode_apply_failed", null, null);
                    assignmentChanged = true;
                }
                if (!gpus.setTotalPowerTarget(selection.candidate().limitWatts(), List.of(gpu)))
                    throw new IllegalStateException("power_mode_apply_failed");
                if ((startWorker || (wasMining && (coinChanged || minerChanged)))
                        && !assignments.startAtCurrentPowerTarget(workerId))
                    throw new IllegalStateException("power_mode_apply_failed");
                if (startWorker || wasMining) locallyStarted.add(workerId);
                switchedAt.put(workerId, Instant.now());
                return new StartResult(true, null, targetCoin, selection.candidate().limitWatts());
            } catch (RuntimeException failure) {
                log.warn("Worker {} could not apply revenue point {} at {} W; restoring {} at {} W",
                        workerId, targetCoin, selection.candidate().limitWatts(), oldCoin, oldLimit, failure);
                boolean restored = !assignmentChanged || assignments.applyEconomicAssignment(workerId, oldCoin, oldMiner);
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
