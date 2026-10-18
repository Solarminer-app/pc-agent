package de.verdox.solarminer.pcagent.mining.economics;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerCoinPolicy;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore;
import de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService;
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
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;

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
    private final MarketDataService market;
    private final WorkerAssignmentService assignments;
    /** REST audit R1: fee-route readiness for the read model comes from the health snapshot. */
    private final de.verdox.solarminer.pcagent.mining.proxy.ProxyHealthService proxyHealth;
    private final LocalRunLock runLock;
    private AutoTuningProfileStore autoTuningProfiles;
    private WorkerPowerModeService workerPowerModes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAutoTuning(AutoTuningProfileStore profiles, WorkerPowerModeService modes) {
        this.autoTuningProfiles = profiles;
        this.workerPowerModes = modes;
    }
    private WorkerCoinRevenueModeService workerCoinRevenueMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkerCoinRevenueMode(@org.springframework.context.annotation.Lazy WorkerCoinRevenueModeService service) {
        this.workerCoinRevenueMode = service;
    }
    private final ScheduledExecutorService expiryExecutor = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().name("pc-agent-economic-plan-expiry").daemon(true).unstarted(runnable));
    private volatile AppliedPlan lastPlan;

    public EconomicPlanningService(AgentControlSettingsService controls, MiningService mining,
                                   LocalGpuPowerService gpus, MinerFactory miners,
                                   ProxyConfigurationService proxy,
                                   GpuEfficiencyStore efficiencyProfiles, MiningPerformanceProfileStore benchmarkProfiles,
                                   MarketDataService market,
                                   WorkerAssignmentService assignments,
                                   de.verdox.solarminer.pcagent.mining.proxy.ProxyHealthService proxyHealth,
                                   LocalRunLock runLock) {
        this.controls = controls;
        this.mining = mining;
        this.gpus = gpus;
        this.miners = miners;
        this.proxy = proxy;
        this.efficiencyProfiles = efficiencyProfiles;
        this.benchmarkProfiles = benchmarkProfiles;
        this.market = market;
        this.assignments = assignments;
        this.proxyHealth = proxyHealth;
        this.runLock = runLock;
    }

    public Capabilities capabilities() {
        List<MinerStats.Worker> live = mining.getWorkerStats();
        List<WorkerCapability> workers = new ArrayList<>();
        workers.add(worker(WorkerIds.CPU, "CPU", "CPU", List.of(Coin.MONERO.id()), live));
        // REST audit R6: capabilities is a read model; it uses the driver snapshot.
        for (LocalGpuPowerService.Gpu gpu : gpus.snapshot()) {
            workers.add(worker(gpu.deviceId(), "GPU", gpu.model(), Coin.gpuCoins().stream().map(Coin::id).sorted().toList(), live));
        }
        return new Capabilities(PROTOCOL_VERSION, Instant.now(), workers, lastPlan == null ? null : lastPlan.expiresAt());
    }

    private WorkerCapability worker(String id, String type, String model, List<String> coins, List<MinerStats.Worker> live) {
        String selectedCoin = controls.get().coinFor(id);
        Coin selected = Coin.byIdOrNull(selectedCoin);
        LocalGpuPowerService.Gpu gpu = gpus.snapshot().stream().filter(card -> id.equals(card.deviceId())).findFirst().orElse(null);
        List<AutoTuningTier> autoTuningTiers = List.of();
        if ("AUTO_TUNING".equals(controls.get().powerModeFor(id)) && selected != null && selected.isGpu()
                && gpu != null && autoTuningProfiles != null) {
            autoTuningTiers = autoTuningProfiles.matrix(id, selectedCoin,
                    miners.minerSoftwareForWorker(selected, id), miners.minerVersionForWorker(selected, id),
                    gpu.model(), gpu.vendor(), selected.assignmentAlgorithm()).stream()
                    .filter(point -> point.powerLimitWatts() >= gpu.minWatts()
                            && point.powerLimitWatts() <= gpu.maxWatts()).map(point -> {
                MarketDataService.Forecast forecast = market.forecastForProfile(selectedCoin, point.hashrateHs());
                return new AutoTuningTier(point.powerLimitWatts(), point.hashrateHs(), point.calibratedAt(),
                        forecast != null && forecast.available() && !forecast.stale() ? forecast.usdPerDay() : null,
                        forecast == null ? null : forecast.updatedAt());
            }).toList();
        }
        List<AutoTuningTier> publishedTiers = autoTuningTiers;
        MinerStats.Worker observed = live.stream().filter(value -> id.equals(value.deviceId()))
                .filter(value -> {
                    Coin assigned = Coin.byIdOrNull(selectedCoin);
                    return assigned != null && assigned.matchesAlgorithm(value.currentAlgorithm());
                })
                .filter(value -> value.miningStatus() == MinerStats.MinerStatus.MINING)
                .findFirst().orElse(null);
        List<CoinCapability> candidates = coins.stream().map(coin -> {
            boolean configured = configured(coin);
            // REST audit R1: read model reads the background health snapshot, not live HTTP.
            boolean routeReady = proxyHealth.feeReady(coin);
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
                    observed.approximatedPowerUsageWatts(), Instant.now(), "OBSERVED")
                    : coin.equals(selectedCoin) && !publishedTiers.isEmpty()
                    ? new PerformanceProfile(publishedTiers.getLast().hashrateHs(), publishedTiers.getLast().watts(),
                    publishedTiers.getLast().measuredAt(), "AUTO_TUNING") : null;
            String reason = !configured ? "Lokale Pool-/Miner-Konfiguration fehlt"
                    : !routeReady ? "Fee-/Proxy-Route ist nicht bereit"
                    : profile == null ? "Kein lokales Leistungsprofil; zuerst Benchmark oder stabilen Messpunkt erzeugen"
                    : null;
            MarketDataService.Forecast forecast = profile == null ? null : market.forecastForProfile(coin, profile.hashrateHps());
            boolean marketReady = forecast != null && forecast.available() && !forecast.stale()
                    && forecast.updatedAt() != null && !forecast.updatedAt().isBefore(Instant.now().minus(Duration.ofHours(2)))
                    && Double.isFinite(forecast.usdPerDay()) && forecast.usdPerDay() > 0;
            return new CoinCapability(coin, algorithm(coin), configured, routeReady, profile, reason,
                    marketReady ? forecast.usdPerDay() : null, marketReady ? forecast.updatedAt() : null);
        }).toList();
        return new WorkerCapability(id, type, model, selectedCoin, controls.get().workerEnabled(id),
                controls.get().coinPolicyFor(id), candidates, controls.get().powerModeFor(id), publishedTiers);
    }

    /** Applies only a plan that is locally opted-in and fully measured; it never accepts credentials. */
    public synchronized PlanResult apply(PlanRequest request) {
        if (!runLock.tryBegin("economic-plan")) return PlanResult.rejected("coin_hopping_measurement_or_plan_active");
        try {
            return applyLocked(request);
        } finally {
            runLock.end();
        }
    }

    private PlanResult applyLocked(PlanRequest request) {
        if (request == null || request.planId() == null || request.planId().isBlank() || request.assignments() == null)
            return PlanResult.rejected("Plan ist unvollständig");
        if (request.activeWorkerIds() == null && request.autoTuningBudgets() != null)
            return PlanResult.rejected("economic_plan_invalid_auto_tuning_budget");
        if (request.validForSeconds() < 30 || request.validForSeconds() > Duration.ofMinutes(30).toSeconds())
            return PlanResult.rejected("Plan-Laufzeit muss zwischen 30 Sekunden und 30 Minuten liegen");
        Map<String, WorkerCapability> workers = capabilities().workers().stream()
                .collect(java.util.stream.Collectors.toMap(WorkerCapability::workerId, value -> value));
        if (request.activeWorkerIds() != null) {
            if (request.targetWatts() != null || request.activeWorkerIds().stream().anyMatch(id -> id == null || id.isBlank())
                    || request.activeWorkerIds().size() != Set.copyOf(request.activeWorkerIds()).size())
                return PlanResult.rejected("economic_plan_invalid_worker_set");
            if (request.autoTuningBudgets() != null) {
                if (!Set.copyOf(request.activeWorkerIds()).containsAll(request.autoTuningBudgets().keySet()))
                    return PlanResult.rejected("economic_plan_invalid_auto_tuning_budget");
                for (var budget : request.autoTuningBudgets().entrySet()) {
                    WorkerCapability worker = workers.get(budget.getKey());
                    if (worker == null || !"AUTO_TUNING".equals(worker.powerMode()) || budget.getValue() == null
                            || worker.autoTuningTiers().stream().noneMatch(tier -> tier.watts() == budget.getValue()
                            && tier.measuredAt() != null && !tier.measuredAt().isBefore(Instant.now().minus(Duration.ofDays(30)))
                            && tier.usdPerDay() != null && tier.usdPerDay() > 0
                            && tier.marketUpdatedAt() != null
                            && !tier.marketUpdatedAt().isBefore(Instant.now().minus(Duration.ofHours(2)))))
                        return PlanResult.rejected("economic_plan_invalid_auto_tuning_budget");
                }
            }
            if (request.assignments().stream().anyMatch(assignment -> assignment == null
                    || assignment.workerId() == null || assignment.coin() == null)
                    || request.assignments().size() != request.assignments().stream()
                    .map(Assignment::workerId).collect(java.util.stream.Collectors.toSet()).size())
                return PlanResult.rejected("economic_plan_invalid_worker_set");
            Set<String> activeIds = Set.copyOf(request.activeWorkerIds());
            Map<String, Assignment> requestedCoins = request.assignments().stream()
                    .collect(java.util.stream.Collectors.toMap(Assignment::workerId, value -> value));
            if (!activeIds.containsAll(requestedCoins.keySet()))
                return PlanResult.rejected("economic_plan_invalid_worker_set");
            for (String id : request.activeWorkerIds()) {
                WorkerCapability worker = workers.get(id);
                if (worker == null || WorkerCoinPolicy.from(worker.coinPolicy()) != WorkerCoinPolicy.AUTO
                        || !worker.externalControlEnabled()) return PlanResult.rejected("economic_plan_worker_not_allowed");
                if (requestedCoins.get(id) == null && "MANUAL".equals(worker.powerMode()))
                    return PlanResult.rejected("economic_plan_invalid_worker_set");
                String chosenCoin = requestedCoins.containsKey(id) ? requestedCoins.get(id).coin() : worker.selectedCoin();
                CoinCapability selected = worker.coins().stream()
                        .filter(value -> value.coin().equals(chosenCoin)).findFirst().orElse(null);
                if (selected == null || selected.profile() == null || selected.usdPerDay() == null
                        || selected.profile().measuredAt() == null
                        || selected.profile().measuredAt().isBefore(Instant.now().minus(Duration.ofDays(30)))
                        || selected.marketUpdatedAt() == null
                        || selected.marketUpdatedAt().isBefore(Instant.now().minus(Duration.ofHours(2)))
                        || !selected.configured() || !selected.feeRouteReady())
                    return PlanResult.rejected("economic_plan_unmeasured_worker");
            }
        }
        for (Assignment assignment : request.assignments()) {
            WorkerCapability worker = workers.get(assignment.workerId());
            if (worker == null || WorkerCoinPolicy.from(worker.coinPolicy()) != WorkerCoinPolicy.AUTO
                    || !worker.externalControlEnabled())
                return PlanResult.rejected("Worker ist nicht lokal für ökonomische Auswahl freigegeben: " + assignment.workerId());
            CoinCapability coin = worker.coins().stream().filter(value -> value.coin().equals(assignment.coin())).findFirst().orElse(null);
            if (coin == null || coin.profile() == null
                    || request.activeWorkerIds() != null && (coin.usdPerDay() == null || coin.marketUpdatedAt() == null))
                return PlanResult.rejected("Kein verifiziertes Leistungsprofil für " + assignment.workerId() + "/" + assignment.coin());
            if (!coin.configured() || !coin.feeRouteReady()) return PlanResult.rejected("Route ist nicht bereit: " + assignment.coin());
        }
        long previousTarget = mining.desiredGlobalPowerTarget();
        Map<String, String> previousCoins = new LinkedHashMap<>();
        Map<String, String> plannedCoins = new LinkedHashMap<>();
        if (lastPlan != null) {
            previousCoins.putAll(lastPlan.previousCoins());
            plannedCoins.putAll(lastPlan.plannedCoins());
        }
        Map<String, Boolean> previousRunning = new LinkedHashMap<>();
        Map<String, Boolean> plannedRunning = new LinkedHashMap<>();
        Map<String, String> rollbackCoins = new LinkedHashMap<>();
        Map<String, Boolean> rollbackRunning = new LinkedHashMap<>();
        if (request.activeWorkerIds() != null) {
            Set<String> active = Set.copyOf(request.activeWorkerIds());
            for (WorkerCapability worker : workers.values()) {
                if (WorkerCoinPolicy.from(worker.coinPolicy()) != WorkerCoinPolicy.AUTO || !worker.externalControlEnabled()) continue;
                String id = worker.workerId();
                boolean wasRunning = mining.getWorkerStats().stream().anyMatch(value -> id.equals(value.deviceId())
                        && value.miningStatus() == MinerStats.MinerStatus.MINING);
                rollbackRunning.put(id, wasRunning);
                previousRunning.put(id, lastPlan != null && lastPlan.previousRunning().containsKey(id)
                        ? lastPlan.previousRunning().get(id) : wasRunning);
                plannedRunning.put(id, active.contains(id));
            }
        }
        for (Assignment assignment : request.assignments()) {
            rollbackCoins.put(assignment.workerId(), controls.get().coinFor(assignment.workerId()));
            previousCoins.put(assignment.workerId(), lastPlan != null && lastPlan.previousCoins().containsKey(assignment.workerId())
                    ? lastPlan.previousCoins().get(assignment.workerId()) : controls.get().coinFor(assignment.workerId()));
            plannedCoins.put(assignment.workerId(), assignment.coin());
            if (!assignments.applyEconomicAssignment(assignment.workerId(), assignment.coin())) {
                restore(rollbackCoins, rollbackRunning);
                return PlanResult.rejected("Coin-Wechsel konnte nicht sicher angewendet werden: " + assignment.workerId());
            }
        }
        if (request.targetWatts() != null && request.targetWatts() > 0 && !mining.setExternalTarget(request.targetWatts())) {
            restore(rollbackCoins, rollbackRunning);
            return PlanResult.rejected("Leistungsziel konnte nicht sicher angewendet werden");
        }
        if (request.activeWorkerIds() != null) {
            for (Map.Entry<String, Boolean> entry : plannedRunning.entrySet()) {
                try {
                    boolean running = mining.getWorkerStats().stream().anyMatch(value ->
                            entry.getKey().equals(value.deviceId())
                                    && value.miningStatus() == MinerStats.MinerStatus.MINING);
                    Integer budget = request.autoTuningBudgets() == null ? null
                            : request.autoTuningBudgets().get(entry.getKey());
                    if (running == entry.getValue() && budget == null) continue;
                    boolean ok = entry.getValue()
                            ? running ? workerPowerModes != null && workerPowerModes.applyForBudget(entry.getKey(),
                            controls.get().coinFor(entry.getKey()), budget).error() == null
                            : budget == null ? assignments.startUnderEconomicPlan(entry.getKey())
                            : assignments.startUnderEconomicPlan(entry.getKey(), budget)
                            : assignments.pause(entry.getKey());
                    if (!ok) {
                        restore(rollbackCoins, rollbackRunning);
                        return PlanResult.rejected("economic_plan_worker_action_failed");
                    }
                    if (!entry.getValue() && workerCoinRevenueMode != null)
                        workerCoinRevenueMode.cancelLocalStart(entry.getKey());
                } catch (RuntimeException failure) {
                    restore(rollbackCoins, rollbackRunning);
                    return PlanResult.rejected("economic_plan_worker_action_failed");
                }
            }
        }
        Instant expiresAt = Instant.now().plusSeconds(request.validForSeconds());
        AppliedPlan applied = new AppliedPlan(request.planId(), expiresAt, previousCoins, plannedCoins, previousTarget,
                request.targetWatts(), previousRunning, plannedRunning);
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
        for (Map.Entry<String, Boolean> previous : applied.previousRunning().entrySet()) {
            if (!java.util.Objects.equals(applied.plannedRunning().get(previous.getKey()),
                    mining.getWorkerStats().stream().anyMatch(value -> previous.getKey().equals(value.deviceId())
                            && value.miningStatus() == MinerStats.MinerStatus.MINING))) continue;
            try {
                // A missing Node heartbeat must not restart a worker using stale economics.
                if (assignments.pause(previous.getKey()) && workerCoinRevenueMode != null)
                    workerCoinRevenueMode.cancelLocalStart(previous.getKey());
            } catch (RuntimeException ignored) { }
        }
        if (applied.plannedTargetWatts() != null && applied.previousTargetWatts() > 0
                && mining.desiredGlobalPowerTarget() == applied.plannedTargetWatts()) {
            mining.setExternalTarget(applied.previousTargetWatts());
        }
        lastPlan = null;
    }

    private void restore(Map<String, String> coins, Map<String, Boolean> running) {
        for (Map.Entry<String, String> entry : coins.entrySet()) {
            try { assignments.applyEconomicAssignment(entry.getKey(), entry.getValue()); }
            catch (RuntimeException ignored) { }
        }
        for (Map.Entry<String, Boolean> entry : running.entrySet()) {
            try {
                if (entry.getValue()) assignments.start(entry.getKey());
                else assignments.pause(entry.getKey());
            } catch (RuntimeException ignored) { }
        }
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
                                   boolean externalControlEnabled, String coinPolicy, List<CoinCapability> coins,
                                   String powerMode, List<AutoTuningTier> autoTuningTiers) {
        public WorkerCapability(String workerId, String hardwareType, String hardwareModel, String selectedCoin,
                                boolean externalControlEnabled, String coinPolicy, List<CoinCapability> coins,
                                String powerMode) {
            this(workerId, hardwareType, hardwareModel, selectedCoin, externalControlEnabled, coinPolicy, coins,
                    powerMode, List.of());
        }
        public WorkerCapability(String workerId, String hardwareType, String hardwareModel, String selectedCoin,
                                boolean externalControlEnabled, String coinPolicy, List<CoinCapability> coins) {
            this(workerId, hardwareType, hardwareModel, selectedCoin, externalControlEnabled, coinPolicy, coins, "MANUAL");
        }
    }
    public record AutoTuningTier(int watts, double hashrateHs, Instant measuredAt,
                                 Double usdPerDay, Instant marketUpdatedAt) { }
    public record CoinCapability(String coin, String algorithm, boolean configured, boolean feeRouteReady,
                                 PerformanceProfile profile, String unavailableReason,
                                 Double usdPerDay, Instant marketUpdatedAt) {
        public CoinCapability(String coin, String algorithm, boolean configured, boolean feeRouteReady,
                              PerformanceProfile profile, String unavailableReason) {
            this(coin, algorithm, configured, feeRouteReady, profile, unavailableReason, null, null);
        }
    }
    public record PerformanceProfile(double hashrateHps, long watts, Instant measuredAt, String confidence) { }
    public record PlanRequest(String planId, long validForSeconds, Long targetWatts, List<Assignment> assignments,
                              List<String> activeWorkerIds, Map<String, Integer> autoTuningBudgets) {
        public PlanRequest(String planId, long validForSeconds, Long targetWatts, List<Assignment> assignments,
                           List<String> activeWorkerIds) {
            this(planId, validForSeconds, targetWatts, assignments, activeWorkerIds, null);
        }
        public PlanRequest(String planId, long validForSeconds, Long targetWatts, List<Assignment> assignments) {
            this(planId, validForSeconds, targetWatts, assignments, null);
        }
    }
    public record Assignment(String workerId, String coin) { }
    public record PlanResult(boolean accepted, String reason, Instant expiresAt) {
        static PlanResult rejected(String reason) { return new PlanResult(false, reason, null); }
    }
    private record AppliedPlan(String id, Instant expiresAt, Map<String, String> previousCoins,
                               Map<String, String> plannedCoins, long previousTargetWatts,
                               Long plannedTargetWatts, Map<String, Boolean> previousRunning,
                               Map<String, Boolean> plannedRunning) { }
}
