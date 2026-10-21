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
    private de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService workerPolicies;
    @org.springframework.beans.factory.annotation.Autowired
    public void setWorkerPolicies(de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService policies) {
        this.workerPolicies = policies;
    }
    private MeasuredOperatingPointService points;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMeasuredPoints(MeasuredOperatingPointService points) { this.points = points; }
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
        if (controls.get().manuallyStopped(id)) return;
        Instant now = Instant.now();
        String mode = controls.get().powerModeFor(id);
        boolean dailyProfitMode = "PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode);
        boolean fixedCoinMode = "PROFIT_PER_DAY".equals(mode);
        if (planning.capabilities().activePlanExpiresAt() != null) return;
        WorkerAssignmentService.WorkerView worker = assignments.workers().stream()
                .filter(value -> id.equals(value.deviceId())).findFirst().orElse(null);
        if (worker == null) return;
        // Miner engines own bounded crash recovery. Re-selecting here used to call resumeGpu
        // and refill that budget forever, racing the engine watchdog and explicit stops.
        if (worker.status() != MinerStats.MinerStatus.MINING) return;
        if (switchedAt.containsKey(id) && now.isBefore(switchedAt.get(id).plus(COOLDOWN))) return;
        LocalGpuPowerService.Gpu gpu = gpus.snapshot().stream()
                .filter(value -> id.equals(value.deviceId())).findFirst().orElse(null);
        if (gpu == null || gpu.currentPowerLimitWatts() == null) return;
        List<CoinHoppingService.Candidate> choices = hopping.candidates(id, 0).stream()
                .filter(value -> workerPolicies == null || workerPolicies.permits(id, worker.coin(), worker.minerSoftwareId(), value.coin(), value.minerSoftwareId()))
                .filter(value -> !dailyProfitMode || value.profitUsdPerDay() != null)
                .filter(value -> !fixedCoinMode || worker.coin().equals(value.coin()))
                .filter(value -> !"PROFIT_PER_DAY".equals(mode)
                        || java.util.Objects.equals(worker.minerSoftwareId(), value.minerSoftwareId()))
                .toList();
        if (choices.isEmpty()) return;
        CoinHoppingService.Candidate current = choices.stream().filter(value -> worker.coin().equals(value.coin())
                && java.util.Objects.equals(worker.minerSoftwareId(), value.minerSoftwareId())
                && gpu.currentPowerLimitWatts() == value.limitWatts()
                && (points == null || points.matches(id, value))).findFirst().orElse(null);
        CoinHoppingService.Candidate best = bestCandidate(choices, mode);
        if (best == null) return;
        boolean samePoint = worker.coin().equals(best.coin())
                && java.util.Objects.equals(worker.minerSoftwareId(), best.minerSoftwareId())
                && gpu.currentPowerLimitWatts() == best.limitWatts() && (points == null || points.matches(id, best));
        if (samePoint || current != null && !worthSwitching(score(current, mode), score(best, mode))) return;
        if (!runLock.tryBegin("worker-coin-revenue")) return;
        try {
            if (controls.get().manuallyStopped(id) || !mode.equals(controls.get().powerModeFor(id))) return;
            applyBestStartPoint(id, worker.coin(), mode, false, true);
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
        if (hopping.selectionError() != null) return new StartSelection(null, hopping.selectionError());
        var assignedWorker = workerPolicies == null ? null : assignments.workers().stream().filter(w -> workerId.equals(w.deviceId())).findFirst().orElse(null);
        List<CoinHoppingService.Candidate> choices = hopping.candidates(workerId, 0).stream()
                .filter(value -> workerPolicies == null || assignedWorker != null && workerPolicies.permits(workerId,
                        assignedCoin, assignedWorker.minerSoftwareId(), value.coin(), value.minerSoftwareId())).toList();
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
        if (choices.isEmpty()) return new StartSelection(null, "power_mode_no_validated_point");
        if ("PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode))
            choices = choices.stream().filter(candidate -> candidate.profitUsdPerDay() != null).toList();
        CoinHoppingService.Candidate best = bestCandidate(choices, mode);
        return best == null
                ? new StartSelection(null, dailyProfitMode(mode) ? "power_mode_profit_unavailable" : "power_mode_no_validated_point")
                : new StartSelection(best, null);
    }

    private static boolean dailyProfitMode(String mode) {
        return "PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode);
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
        if (controls.get().manuallyStopped(workerId))
            return new StartResult(false, "worker_manually_stopped", null, null);
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
            var previousPoint = points == null ? null : points.current(workerId);
            boolean settingsChanged = points != null && points.needsRestart(workerId, selection.candidate());
            boolean stoppedForSettings = false;
            try {
                if (wasMining && settingsChanged) {
                    if (!assignments.stopForBenchmarkRestart(workerId))
                        throw new IllegalStateException("benchmark_stop_failed");
                    stoppedForSettings = true;
                }
                if (coinChanged || minerChanged) {
                    if (!assignments.applyEconomicAssignment(workerId, targetCoin, selection.candidate().minerSoftwareId()))
                        throw new IllegalStateException("power_mode_apply_failed");
                    assignmentChanged = true;
                }
                if (!(points == null ? gpus.setTotalPowerTarget(selection.candidate().limitWatts(), List.of(gpu))
                        : points.apply(selection.candidate(), gpu)))
                    throw new IllegalStateException("power_mode_apply_failed");
                if ((startWorker || (wasMining && (coinChanged || minerChanged || settingsChanged)))
                        && !assignments.startAtCurrentPowerTarget(workerId))
                    throw new IllegalStateException("power_mode_apply_failed");
                switchedAt.put(workerId, Instant.now());
                return new StartResult(true, null, targetCoin, selection.candidate().limitWatts());
            } catch (RuntimeException failure) {
                log.warn("Worker {} could not apply revenue point {} at {} W; restoring {} at {} W",
                        workerId, targetCoin, selection.candidate().limitWatts(), oldCoin, oldLimit, failure);
                boolean restored = !assignmentChanged || assignments.applyEconomicAssignment(workerId, oldCoin, oldMiner);
                if (!restored)
                    log.error("Worker {} could not restore assignment {}", workerId, oldCoin);
                boolean stopRequired = settingsChanged || selection.candidate().tunedProfile() != null;
                boolean safeToRestore = !stopRequired || assignments.stopForBenchmarkRestart(workerId);
                boolean pointRestored = safeToRestore && (points == null || !stopRequired
                        ? gpus.setTotalPowerTarget(oldLimit, List.of(gpu)) : points.restore(previousPoint, gpu, oldLimit));
                if (!pointRestored)
                    log.error("Worker {} could not restore power target {} W", workerId, oldLimit);
                if (wasMining && (assignmentChanged || stoppedForSettings) && restored && pointRestored
                        && !assignments.startAtCurrentPowerTarget(workerId))
                    log.error("Worker {} could not restart the previous miner {}", workerId, oldCoin);
                return new StartResult(false, "power_mode_apply_failed", null, null);
            }
        } finally { if (!lockHeld) runLock.end(); }
    }

    public record StartSelection(CoinHoppingService.Candidate candidate, String error) { }
    public record StartResult(boolean success, String error, String coin, Integer watts) { }
}
