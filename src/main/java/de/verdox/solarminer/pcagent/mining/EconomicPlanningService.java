package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerCoinPolicy;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deliberate boundary for economic dispatch. The Node may propose a short-lived plan, but the
 * agent remains the authority for local consent, installed miners, configured routes and GPU
 * safety. Unknown performance is intentionally not eligible for automatic coin switching.
 */
@Service
public class EconomicPlanningService {
    public static final int PROTOCOL_VERSION = 1;
    private final AgentControlSettingsService controls;
    private final MiningService mining;
    private final LocalGpuPowerService gpus;
    private final MinerFactory miners;
    private final ProxyConfigurationService proxy;
    private final GpuEfficiencyStore efficiencyProfiles;
    private final MiningPerformanceProfileStore benchmarkProfiles;
    private final WorkerAssignmentService assignments;
    private final ScheduledExecutorService expiryExecutor = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().name("pc-agent-economic-plan-expiry").daemon(true).unstarted(runnable));
    private volatile AppliedPlan lastPlan;

    public EconomicPlanningService(AgentControlSettingsService controls, MiningService mining,
                                   LocalGpuPowerService gpus, MinerFactory miners,
                                   ProxyConfigurationService proxy,
                                   GpuEfficiencyStore efficiencyProfiles, MiningPerformanceProfileStore benchmarkProfiles,
                                   WorkerAssignmentService assignments) {
        this.controls = controls;
        this.mining = mining;
        this.gpus = gpus;
        this.miners = miners;
        this.proxy = proxy;
        this.efficiencyProfiles = efficiencyProfiles;
        this.benchmarkProfiles = benchmarkProfiles;
        this.assignments = assignments;
    }

    public Capabilities capabilities() {
        List<MinerStats.Worker> live = mining.getWorkerStats();
        List<WorkerCapability> workers = new ArrayList<>();
        workers.add(worker(WorkerIds.CPU, "CPU", "CPU", List.of(Coin.MONERO.id()), live));
        for (LocalGpuPowerService.Gpu gpu : gpus.discover()) {
            workers.add(worker(gpu.deviceId(), "GPU", gpu.model(), Coin.gpuCoins().stream().map(Coin::id).sorted().toList(), live));
        }
        return new Capabilities(PROTOCOL_VERSION, Instant.now(), workers, lastPlan == null ? null : lastPlan.expiresAt());
    }

    private WorkerCapability worker(String id, String type, String model, List<String> coins, List<MinerStats.Worker> live) {
        String selectedCoin = controls.get().coinFor(id);
        MinerStats.Worker observed = live.stream().filter(value -> id.equals(value.deviceId()))
                .filter(value -> algorithm(selectedCoin).equalsIgnoreCase(value.currentAlgorithm()))
                .filter(value -> value.miningStatus() == MinerStats.MinerStatus.MINING)
                .findFirst().orElse(null);
        List<CoinCapability> candidates = coins.stream().map(coin -> {
            boolean configured = configured(coin);
            boolean routeReady = proxy.feeReady(coin);
            // A current mining observation is the only locally trustworthy cold-start profile.
            // Benchmark/sweep profiles can later populate the same field without changing C10.
            GpuEfficiencyStore.Profile sweep = efficiencyProfiles.find(id, algorithm(coin));
            MiningPerformanceProfileStore.Profile benchmark = benchmarkProfiles.find(id, coin, algorithm(coin));
            boolean observedProfile = coin.equals(selectedCoin) && observed != null
                    && observed.terahashPerSecond() > 0 && observed.approximatedPowerUsageWatts() > 0;
            boolean sweepProfile = sweep != null && sweep.bestHashrateHs() != null && sweep.bestHashrateHs() > 0
                    && sweep.bestPowerWatts() != null && sweep.bestPowerWatts() > 0 && sweep.testedAt() != null;
            boolean benchmarkProfile = benchmark != null && benchmark.hashrateHps() > 0 && benchmark.powerWatts() > 0
                    && benchmark.observations() >= BenchmarkSessionService.REQUIRED_SAMPLES_PER_WORKER
                    && benchmark.measuredAt() != null;
            PerformanceProfile profile = benchmarkProfile && (!sweepProfile || !benchmark.measuredAt().isBefore(sweep.testedAt()))
                    ? new PerformanceProfile(benchmark.hashrateHps(), benchmark.powerWatts(), benchmark.measuredAt(), "BENCHMARK")
                    : sweepProfile ? new PerformanceProfile(sweep.bestHashrateHs(),
                    Math.round(sweep.bestPowerWatts()), sweep.testedAt(), "SWEEP")
                    : observedProfile ? new PerformanceProfile(observed.terahashPerSecond() * 1_000_000_000_000d,
                    observed.approximatedPowerUsageWatts(), Instant.now(), "OBSERVED") : null;
            String reason = !configured ? "Lokale Pool-/Miner-Konfiguration fehlt"
                    : !routeReady ? "Fee-/Proxy-Route ist nicht bereit"
                    : profile == null ? "Kein lokales Leistungsprofil; zuerst Benchmark oder stabilen Messpunkt erzeugen"
                    : null;
            return new CoinCapability(coin, algorithm(coin), configured, routeReady, profile, reason);
        }).toList();
        return new WorkerCapability(id, type, model, selectedCoin, controls.get().workerEnabled(id),
                controls.get().coinPolicyFor(id), candidates);
    }

    /** Applies only a plan that is locally opted-in and fully measured; it never accepts credentials. */
    public synchronized PlanResult apply(PlanRequest request) {
        if (request == null || request.planId() == null || request.planId().isBlank() || request.assignments() == null)
            return PlanResult.rejected("Plan ist unvollständig");
        if (request.validForSeconds() < 30 || request.validForSeconds() > Duration.ofMinutes(30).toSeconds())
            return PlanResult.rejected("Plan-Laufzeit muss zwischen 30 Sekunden und 30 Minuten liegen");
        Map<String, WorkerCapability> workers = capabilities().workers().stream()
                .collect(java.util.stream.Collectors.toMap(WorkerCapability::workerId, value -> value));
        for (Assignment assignment : request.assignments()) {
            WorkerCapability worker = workers.get(assignment.workerId());
            if (worker == null || WorkerCoinPolicy.from(worker.coinPolicy()) != WorkerCoinPolicy.AUTO
                    || !worker.externalControlEnabled())
                return PlanResult.rejected("Worker ist nicht lokal für ökonomische Auswahl freigegeben: " + assignment.workerId());
            CoinCapability coin = worker.coins().stream().filter(value -> value.coin().equals(assignment.coin())).findFirst().orElse(null);
            if (coin == null || coin.profile() == null)
                return PlanResult.rejected("Kein verifiziertes Leistungsprofil für " + assignment.workerId() + "/" + assignment.coin());
            if (!coin.configured() || !coin.feeRouteReady()) return PlanResult.rejected("Route ist nicht bereit: " + assignment.coin());
        }
        long previousTarget = mining.desiredGlobalPowerTarget();
        Map<String, String> previousCoins = new LinkedHashMap<>();
        Map<String, String> plannedCoins = new LinkedHashMap<>();
        for (Assignment assignment : request.assignments()) {
            previousCoins.put(assignment.workerId(), controls.get().coinFor(assignment.workerId()));
            plannedCoins.put(assignment.workerId(), assignment.coin());
            if (!assignments.applyEconomicAssignment(assignment.workerId(), assignment.coin()))
                return PlanResult.rejected("Coin-Wechsel konnte nicht sicher angewendet werden: " + assignment.workerId());
        }
        if (request.targetWatts() != null && request.targetWatts() > 0 && !mining.setExternalTarget(request.targetWatts()))
            return PlanResult.rejected("Leistungsziel konnte nicht sicher angewendet werden");
        Instant expiresAt = Instant.now().plusSeconds(request.validForSeconds());
        AppliedPlan applied = new AppliedPlan(request.planId(), expiresAt, previousCoins, plannedCoins, previousTarget,
                request.targetWatts());
        lastPlan = applied;
        expiryExecutor.schedule(() -> expire(applied), request.validForSeconds(), TimeUnit.SECONDS);
        return new PlanResult(true, null, expiresAt);
    }

    /** A stale Node decision must not leave a changed coin assignment behind indefinitely. */
    private synchronized void expire(AppliedPlan applied) {
        if (lastPlan != applied || Instant.now().isBefore(applied.expiresAt())) return;
        for (Map.Entry<String, String> previous : applied.previousCoins().entrySet()) {
            // Do not overwrite an operator change made after the plan was accepted.
            if (applied.plannedCoins().get(previous.getKey()).equals(controls.get().coinFor(previous.getKey())))
                assignments.applyEconomicAssignment(previous.getKey(), previous.getValue());
        }
        if (applied.plannedTargetWatts() != null && applied.previousTargetWatts() > 0
                && mining.desiredGlobalPowerTarget() == applied.plannedTargetWatts()) {
            mining.setExternalTarget(applied.previousTargetWatts());
        }
        lastPlan = null;
    }

    private boolean configured(String coinId) {
        return miners.miner(coinId).map(miner -> miner.configured()).orElse(false);
    }

    private static String algorithm(String coinId) {
        Coin coin = Coin.byIdOrNull(coinId);
        return coin == null ? null : coin.algorithm();
    }

    public record Capabilities(int protocolVersion, Instant collectedAt, List<WorkerCapability> workers, Instant activePlanExpiresAt) { }
    public record WorkerCapability(String workerId, String hardwareType, String hardwareModel, String selectedCoin,
                                   boolean externalControlEnabled, String coinPolicy, List<CoinCapability> coins) { }
    public record CoinCapability(String coin, String algorithm, boolean configured, boolean feeRouteReady,
                                 PerformanceProfile profile, String unavailableReason) { }
    public record PerformanceProfile(double hashrateHps, long watts, Instant measuredAt, String confidence) { }
    public record PlanRequest(String planId, long validForSeconds, Long targetWatts, List<Assignment> assignments) { }
    public record Assignment(String workerId, String coin) { }
    public record PlanResult(boolean accepted, String reason, Instant expiresAt) {
        static PlanResult rejected(String reason) { return new PlanResult(false, reason, null); }
    }
    private record AppliedPlan(String id, Instant expiresAt, Map<String, String> previousCoins,
                               Map<String, String> plannedCoins, long previousTargetWatts,
                               Long plannedTargetWatts) { }
}
