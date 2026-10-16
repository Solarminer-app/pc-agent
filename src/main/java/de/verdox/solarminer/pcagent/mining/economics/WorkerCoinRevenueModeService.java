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
                if (applied && !best.coin().equals(current.coin())) applied = assignments.start(id);
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
                if (!best.coin().equals(current.coin())) assignments.start(id);
            } catch (RuntimeException ignored) { /* The miner remains stopped on a failed recovery. */ }
        } finally { runLock.end(); }
    }
}
