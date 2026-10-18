package de.verdox.solarminer.pcagent.mining.network;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.SweepRunState;
import de.verdox.solarminer.pcagent.controller.AgentStatusService;
import de.verdox.solarminer.pcagent.controller.MiningController;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.EfficiencySweepService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the network dashboard: for every peer agent found by
 * {@link AgentNetworkDiscoveryService}, fetches its read-only
 * {@code GET /api/agent/external/network-snapshot} over LAN HTTP and merges the worker rows
 * with this agent's own live stats. Fetches are cached per peer for one channel tick; an
 * unreachable peer stays listed with its identity so the operator sees the gap instead of a
 * vanishing machine.
 */
@Service
public class NetworkDashboardService {
    private static final Duration FETCH_TIMEOUT = Duration.ofMillis(1500);
    private static final Duration CACHE_TTL = Duration.ofSeconds(4);

    private final AgentNetworkDiscoveryService discovery;
    private final MiningService ownMining;
    private final MarketDataService ownEarnings;
    private final EnergyJournalService ownEnergy;
    private final WorkerAssignmentService ownWorkers;
    private de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService coinRevenueMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCoinRevenueMode(de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService service) {
        this.coinRevenueMode = service;
    }
    private final AgentControlSettingsService ownControls;
    private final AgentStatusService status;
    private final BenchmarkSessionService benchmarks;
    private final EfficiencySweepService sweeps;
    private final AgentIdentityService ownIdentity;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1200)).build();
    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();
    /** Read-only LAN board consumed by REST; all peer HTTP work happens off request threads. */
    private volatile List<AgentCard> snapshot = List.of();

    public NetworkDashboardService(AgentNetworkDiscoveryService discovery, MiningService ownMining,
                                   MarketDataService ownEarnings, EnergyJournalService ownEnergy,
                                   WorkerAssignmentService ownWorkers, AgentControlSettingsService ownControls,
                                   AgentIdentityService ownIdentity,
                                   @org.springframework.context.annotation.Lazy AgentStatusService status,
                                   BenchmarkSessionService benchmarks, EfficiencySweepService sweeps,
                                   ObjectMapper mapper,
                                   @org.springframework.beans.factory.annotation.Value("${server.port:8084}") int ownDashboardPort) {
        this.discovery = discovery;
        this.ownMining = ownMining;
        this.ownEarnings = ownEarnings;
        this.ownEnergy = ownEnergy;
        this.ownWorkers = ownWorkers;
        this.ownControls = ownControls;
        this.status = status;
        this.benchmarks = benchmarks;
        this.sweeps = sweeps;
        this.ownIdentity = ownIdentity;
        this.ownDashboardPort = ownDashboardPort;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final int ownDashboardPort;

    /** One card per machine from the last completed background refresh; never performs I/O. */
    public List<AgentCard> cards() {
        return snapshot;
    }

    public EnergyJournalService.History ownEnergyHistory(Instant from, Instant to) { return ownEnergy.history(from, to); }

    /** On-demand, aggregate-only history across currently discovered peers that opted into LAN visibility. */
    public EnergyJournalService.History networkEnergyHistory(Instant from, Instant to) {
        EnergyJournalService.History own = ownEnergy.history(from, to);
        Map<String, MutablePoint> hourly = new java.util.TreeMap<>(), daily = new java.util.TreeMap<>();
        addHistory(own, hourly, daily);
        List<AgentNetworkDiscoveryService.Peer> peers = discovery.peers();
        try (var fetches = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.CompletableFuture<EnergyJournalService.History>> requests = peers.stream()
                    .map(peer -> java.util.concurrent.CompletableFuture.supplyAsync(() -> peerEnergyHistory(peer, from, to), fetches))
                    .toList();
            for (var request : requests) {
                EnergyJournalService.History history = request.join();
                if (history != null) addHistory(history, hourly, daily);
            }
        }
        return new EnergyJournalService.History(from, to, hourly.values().stream().map(MutablePoint::point).toList(),
                daily.values().stream().map(MutablePoint::point).toList());
    }

    private EnergyJournalService.History peerEnergyHistory(AgentNetworkDiscoveryService.Peer peer, Instant from, Instant to) {
            try {
                String query = "?from=" + java.net.URLEncoder.encode(from.toString(), java.nio.charset.StandardCharsets.UTF_8)
                        + "&to=" + java.net.URLEncoder.encode(to.toString(), java.nio.charset.StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + peer.host() + ":" + peer.dashboardPort()
                                + "/api/agent/external/energy-history" + query)).timeout(FETCH_TIMEOUT).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return mapper.readValue(response.body(), EnergyJournalService.History.class);
            } catch (Exception ignored) {
                if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            return null;
    }

    private static void addHistory(EnergyJournalService.History history, Map<String, MutablePoint> hourly,
                                   Map<String, MutablePoint> daily) {
        history.hourly().forEach(point -> hourly.computeIfAbsent(point.at(), MutablePoint::new).add(point));
        history.daily().forEach(point -> daily.computeIfAbsent(point.at(), MutablePoint::new).add(point));
    }
    private static final class MutablePoint {
        final String at; double kwh, hashrateSeconds; long runtime, measured;
        final Map<String, Double> costs = new java.util.TreeMap<>();
        final java.util.Set<String> algorithms = new java.util.TreeSet<>();
        boolean missingCosts;
        boolean missingAlgorithms;
        MutablePoint(String at) { this.at = at; }
        void add(EnergyJournalService.HistoryPoint p) {
            kwh += p.kilowattHours(); hashrateSeconds += p.averageHashrateHs() * p.runtimeSeconds(); runtime += p.runtimeSeconds(); measured += p.measuredSeconds();
            if (p.estimatedCosts() == null || p.estimatedCosts().isEmpty()) missingCosts = true;
            else p.estimatedCosts().forEach((currency, value) -> costs.merge(currency, value, Double::sum));
            if (p.runtimeSeconds() > 0 && (p.algorithms() == null || p.algorithms().isEmpty())) missingAlgorithms = true;
            if (p.algorithms() != null) algorithms.addAll(p.algorithms());
        }
        EnergyJournalService.HistoryPoint point() { return new EnergyJournalService.HistoryPoint(at, kwh,
                runtime == 0 ? 0 : hashrateSeconds / runtime, runtime, measured,
                missingCosts ? null : Map.copyOf(costs), missingAlgorithms ? null : List.copyOf(algorithms)); }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmSnapshot() {
        Thread.startVirtualThread(this::refreshSnapshot);
    }

    @Scheduled(fixedDelayString = "${solarminer.agent.network-dashboard-refresh-ms:5000}")
    public void refreshSnapshot() {
        List<AgentNetworkDiscoveryService.Peer> peers = discovery.peers();
        List<AgentCard> peerCards;
        if (peers.isEmpty()) {
            peerCards = List.of();
        } else {
            // Peer fetches run concurrently; one slow machine never delays the others.
            try (var fetches = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                peerCards = peers.stream()
                        .map(peer -> java.util.concurrent.CompletableFuture.supplyAsync(() -> peerCard(peer), fetches))
                        .map(java.util.concurrent.CompletableFuture::join)
                        .toList();
            }
        }
        List<AgentCard> result = new ArrayList<>();
        result.add(ownCard());
        result.addAll(peerCards);
        snapshot = List.copyOf(result);
    }

    private AgentCard ownCard() {
        List<MinerStats.Worker> workers = ownMining.getWorkerStats();
        List<MarketDataService.Forecast> forecasts = ownEarnings.forecasts(workers);
        EfficiencySweepService.Session sweep = sweeps.status();
        BenchmarkSessionService.Session benchmark = benchmarks.status();
        Set<String> sweepWorkerIds = sweep.running() ? sweep.runs().stream()
                .filter(run -> run.status() == SweepRunState.RUNNING)
                .map(EfficiencySweepService.RunStatus::deviceId)
                .collect(java.util.stream.Collectors.toSet()) : Set.of();
        return new AgentCard(discovery.agentId(), ownIdentity.displayName(), null, ownDashboardPort,
                true, true, null, assignedWorkerRows(ownWorkers.workers(),
                        benchmark.running(), sweepWorkerIds), earningsRows(forecasts),
                totals(workers, forecasts), sharedEnergy(ownEnergy.overview()),
                ownControls.get().externalControlEnabled(), Instant.now(),
                activeSweepRuns(sweep), activeBenchmarkRuns(benchmark, ownWorkers.workers()));
    }

    private AgentCard peerCard(AgentNetworkDiscoveryService.Peer peer) {
        Instant now = Instant.now();
        CachedSnapshot cached = cache.get(peer.agentId());
        if (cached != null && cached.expiresAt().isAfter(now)) return cached.card();
        FetchResult result = fetch(peer);
        AgentCard card;
        if (result.snapshot() != null) {
            card = new AgentCard(peer.agentId(), peer.name(), peer.host(), peer.dashboardPort(),
                    true, false, null, result.snapshot().workers(), result.snapshot().earnings(),
                    result.snapshot().totals(), result.snapshot().energy(),
                    Boolean.TRUE.equals(result.snapshot().controlEnabled()), now,
                    orEmpty(result.snapshot().activeSweeps()), orEmpty(result.snapshot().activeBenchmarks()));
        } else if (result.sharingDisabled()) {
            // The machine is there; its operator keeps worker, earnings and energy data private.
            card = new AgentCard(peer.agentId(), peer.name(), peer.host(), peer.dashboardPort(),
                    true, false, "LAN visibility is disabled on this agent", List.of(), List.of(),
                    new Totals(0, 0, 0, 0), null, false, now, List.of(), List.of());
        } else {
            card = new AgentCard(peer.agentId(), peer.name(), peer.host(), peer.dashboardPort(),
                    false, false, "Peer is not reachable", List.of(), List.of(),
                    new Totals(0, 0, 0, 0), null, false, now, List.of(), List.of());
        }
        cache.put(peer.agentId(), new CachedSnapshot(card, now.plus(CACHE_TTL)));
        return card;
    }

    private FetchResult fetch(AgentNetworkDiscoveryService.Peer peer) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + peer.host() + ":" + peer.dashboardPort()
                                    + "/api/agent/external/network-snapshot"))
                    .timeout(FETCH_TIMEOUT).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403) return new FetchResult(null, true);
            if (response.statusCode() != 200) return new FetchResult(null, false);
            return new FetchResult(mapper.readValue(response.body(), PeerSnapshot.class), false);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new FetchResult(null, false);
        }
    }

    /**
     * Fetches wallet targets only after an operator explicitly chooses a peer on the Wallets
     * page. They are deliberately excluded from the recurring network-dashboard snapshot.
     */
    public PeerWalletTargets walletTargets(String agentId) {
        AgentNetworkDiscoveryService.Peer peer = discovery.peers().stream()
                .filter(candidate -> candidate.agentId().equals(agentId)).findFirst().orElse(null);
        if (peer == null) return new PeerWalletTargets(false, "peer_wallet_targets_unavailable", List.of());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + peer.host() + ":" + peer.dashboardPort()
                            + "/api/agent/external/wallet-targets"))
                    .timeout(FETCH_TIMEOUT).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403)
                return new PeerWalletTargets(false, "wallet_targets_not_available_from_this_agent", List.of());
            if (response.statusCode() != 200)
                return new PeerWalletTargets(false, "peer_wallet_targets_unavailable", List.of());
            List<MiningController.WalletTarget> targets = mapper.readerForListOf(MiningController.WalletTarget.class)
                    .readValue(response.body());
            return new PeerWalletTargets(true, null, List.copyOf(targets));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new PeerWalletTargets(false, "peer_wallet_targets_unavailable", List.of());
        }
    }

    private record FetchResult(PeerSnapshot snapshot, boolean sharingDisabled) { }

    static List<WorkerRow> workerRows(List<MinerStats.Worker> workers) {
        return workers.stream()
                .filter(worker -> worker.miningStatus() == MinerStats.MinerStatus.MINING
                        || worker.miningStatus() == MinerStats.MinerStatus.ERROR)
                .map(worker -> new WorkerRow(worker.workerDisplayName(), worker.currentAlgorithm(),
                        worker.hardwareType(), worker.hardwareModel(),
                        worker.terahashPerSecond() * 1e12,
                        worker.approximatedPowerUsageWatts(), worker.miningStatus().name(),
                        Coin.byAlgorithm(worker.currentAlgorithm()).map(Coin::id).orElse(null),
                        worker.deviceId(), true, true, true, false, false))
                .toList();
    }

    static List<WorkerRow> assignedWorkerRows(List<WorkerAssignmentService.WorkerView> workers) {
        return assignedWorkerRows(workers, false, Set.of());
    }

    static List<WorkerRow> assignedWorkerRows(List<WorkerAssignmentService.WorkerView> workers,
                                              boolean benchmarkRunning, Set<String> sweepWorkerIds) {
        return workers.stream().filter(worker -> !Coin.NONE.id().equals(worker.coin()))
                .map(worker -> {
                    MinerStats.Worker telemetry = worker.telemetry();
                    return new WorkerRow(worker.hardwareModel(), worker.algorithm(), worker.hardwareType(),
                            worker.hardwareModel(), telemetry == null ? 0 : telemetry.terahashPerSecond() * 1e12,
                            telemetry == null ? 0 : telemetry.approximatedPowerUsageWatts(),
                            worker.status().name(), worker.coin(), worker.deviceId(),
                            worker.configured(), worker.minerInstalled(), worker.externalControlEnabled(),
                            benchmarkRunning && worker.status() == MinerStats.MinerStatus.MINING,
                            sweepWorkerIds.contains(worker.deviceId())
                                    && worker.status() == MinerStats.MinerStatus.MINING);
                }).toList();
    }

    static List<EarningsRow> earningsRows(List<MarketDataService.Forecast> forecasts) {
        return forecasts.stream()
                .filter(forecast -> forecast.available() && forecast.hashrateHps() > 0)
                .map(forecast -> new EarningsRow(forecast.coin(), forecast.ticker(),
                        forecast.hashrateHps(), forecast.usdPerDay(), true))
                .toList();
    }

    private static Totals totals(List<MinerStats.Worker> workers,
                                 List<MarketDataService.Forecast> forecasts) {
        double hashrate = workers.stream().mapToDouble(w -> w.terahashPerSecond() * 1e12).sum();
        long watts = workers.stream().mapToLong(MinerStats.Worker::approximatedPowerUsageWatts).sum();
        double usdPerDay = forecasts.stream().filter(MarketDataService.Forecast::available)
                .mapToDouble(MarketDataService.Forecast::usdPerDay).sum();
        return new Totals(workers.size(), hashrate, watts, usdPerDay);
    }

    static SharedEnergy sharedEnergy(EnergyJournalService.Overview overview) {
        double activeWattHours = overview.activeSessions().stream()
                .mapToDouble(EnergyJournalService.Session::wattHours).sum();
        long activeRuntimeSeconds = overview.activeSessions().stream()
                .mapToLong(EnergyJournalService.Session::runtimeSeconds).max().orElse(0);
        return new SharedEnergy(overview.settings(), overview.today(), overview.last7Days(),
                overview.last30Days(), activeWattHours, activeRuntimeSeconds,
                overview.activeSessions().size());
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /** Only typed measurement data crosses the LAN; diagnostic detail and step notes stay local. */
    static List<SweepProgress> activeSweepRuns(EfficiencySweepService.Session session) {
        if (!session.running()) return List.of();
        return session.runs().stream()
                .filter(run -> run.status() == SweepRunState.RUNNING)
                .map(run -> new SweepProgress(run.id(), run.deviceId(), run.model(), run.coin(), run.algorithm(),
                        run.mode().wireName(), run.limitWatts(), orEmpty(run.plannedLimits()),
                        run.steps().stream().map(step -> new SweepStep(step.limitWatts(), step.medianHashrateHs(),
                                step.avgPowerWatts(), step.stable())).toList(),
                        run.samples(), run.samplesRequired(), run.referenceBatch()))
                .toList();
    }

    /** One current card per selected GPU, including devices still awaiting their first sample. */
    static List<BenchmarkProgress> activeBenchmarkRuns(BenchmarkSessionService.Session session,
                                                        List<WorkerAssignmentService.WorkerView> workers) {
        if (!session.running()) return List.of();
        Map<String, BenchmarkProgress> byDevice = new LinkedHashMap<>();
        for (WorkerAssignmentService.WorkerView worker : workers) {
            if (!"GPU".equals(worker.hardwareType()) || !session.selectedWorkers().contains(worker.deviceId())) continue;
            byDevice.put(worker.deviceId(), new BenchmarkProgress(worker.deviceId(), worker.hardwareModel(),
                    worker.algorithm(), 0, BenchmarkSessionService.REQUIRED_SAMPLES_PER_WORKER, null, null));
        }
        for (BenchmarkSessionService.LiveSeries trace : session.liveSeries()) {
            if (!"GPU".equals(trace.hardwareType())) continue;
            BenchmarkSessionService.SamplePoint latest = trace.points().isEmpty() ? null : trace.points().getLast();
            byDevice.put(trace.deviceId(), new BenchmarkProgress(trace.deviceId(), trace.hardwareModel(),
                    trace.algorithm(), trace.points().size(), BenchmarkSessionService.REQUIRED_SAMPLES_PER_WORKER,
                    latest == null ? null : latest.hashrateHs(), latest == null ? null : latest.powerWatts()));
        }
        return List.copyOf(byDevice.values());
    }

    /** The wire shape a peer publishes; also what the dashboard renders per machine. */
    public record AgentCard(String agentId, String name, String host, int dashboardPort,
                            boolean reachable, boolean own, String error,
                            List<WorkerRow> workers, List<EarningsRow> earnings, Totals totals,
                            SharedEnergy energy, boolean controlEnabled, Instant collectedAt,
                            List<SweepProgress> activeSweeps, List<BenchmarkProgress> activeBenchmarks) { }

    public record SweepStep(int limitWatts, Double medianHashrateHs, Double avgPowerWatts, boolean stable) { }

    public record SweepProgress(String id, String deviceId, String model, String coin, String algorithm,
                                String mode, Integer limitWatts, List<Integer> plannedLimits, List<SweepStep> steps,
                                int samples, int samplesRequired, Integer referenceBatch) { }

    public record BenchmarkProgress(String deviceId, String model, String algorithm, int samples,
                                    int samplesRequired, Double hashrateHs, Long powerWatts) { }

    public record WorkerRow(String name, String algorithm, String hardwareType, String hardwareModel,
                            double hashrateHps, long watts, String status, String coin,
                            String deviceId, Boolean configured, Boolean minerInstalled,
                            Boolean controlAllowed, Boolean benchmarking, Boolean sweeping) { }

    /** Nullable for backward-compatible deserialization of snapshots from older LAN peers. */
    public record EarningsRow(String coin, String ticker, double hashrateHps, double usdPerDay,
                              Boolean available) { }

    public record Totals(int workerCount, double hashrateHps, long watts, double usdPerDay) { }

    /** Aggregate-only energy data shared under the existing explicit LAN-visibility consent. */
    public record SharedEnergy(EnergyJournalService.Settings settings,
                               EnergyJournalService.Aggregate today,
                               EnergyJournalService.Aggregate last7Days,
                               EnergyJournalService.Aggregate last30Days,
                               double activeWattHours, long activeRuntimeSeconds,
                               int activeSessionCount) { }

    /** On-demand import response. A target is only a draft until the local operator saves it. */
    public record PeerWalletTargets(boolean available, String error, List<MiningController.WalletTarget> targets) { }

    /** Deserialized peer payload; unknown fields stay tolerated across agent versions. */
    public record PeerSnapshot(List<WorkerRow> workers, List<EarningsRow> earnings, Totals totals,
                               SharedEnergy energy, Boolean controlEnabled,
                               List<SweepProgress> activeSweeps, List<BenchmarkProgress> activeBenchmarks) { }

    /** What this agent publishes on {@code GET /api/agent/external/network-snapshot}. */
    public PeerSnapshot ownSnapshot() {
        AgentCard own = snapshot.stream().filter(AgentCard::own).findFirst().orElse(null);
        return own == null ? new PeerSnapshot(List.of(), List.of(), new Totals(0, 0, 0, 0),
                sharedEnergy(ownEnergy.overview()), ownControls.get().externalControlEnabled(),
                activeSweepRuns(sweeps.status()), activeBenchmarkRuns(benchmarks.status(), ownWorkers.workers()))
                : new PeerSnapshot(own.workers(), own.earnings(), own.totals(), own.energy(),
                own.controlEnabled(), activeSweepRuns(sweeps.status()),
                activeBenchmarkRuns(benchmarks.status(), ownWorkers.workers()));
    }

    public WorkerControlResult controlPeer(String agentId, String deviceId, boolean enabled) {
        AgentNetworkDiscoveryService.Peer peer = discovery.peers().stream()
                .filter(candidate -> candidate.agentId().equals(agentId)).findFirst().orElse(null);
        if (peer == null) return new WorkerControlResult(false, "peer_worker_unavailable");
        try {
            String action = enabled ? "start" : "pause";
            String encodedDevice = java.net.URLEncoder.encode(deviceId, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + peer.host() + ":"
                            + peer.dashboardPort() + "/api/agent/external/network-workers/"
                            + encodedDevice + "/" + action))
                    .timeout(FETCH_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403)
                return new WorkerControlResult(false, "peer_worker_control_disabled");
            if (response.statusCode() != 200 || !Boolean.parseBoolean(response.body()))
                return new WorkerControlResult(false, "peer_worker_action_failed");
            cache.remove(agentId);
            Thread.startVirtualThread(this::refreshSnapshot);
            return new WorkerControlResult(true, null);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return new WorkerControlResult(false, "peer_worker_unavailable");
        }
    }

    public boolean controlOwnWorker(String deviceId, boolean enabled) {
        if (!ownControls.workerEnabled(deviceId)) return false;
        if (enabled && !ownMining.beginWorkerStart(deviceId)) return false;
        if (enabled) status.invalidate("workers", "overview");
        try {
            String mode = ownControls.get().powerModeFor(deviceId);
            boolean revenueMode = ("COIN_REVENUE_PER_KWH".equals(mode) || "REVENUE_PER_KWH".equals(mode)
                    || "PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode))
                    && !de.verdox.solarminer.pcagent.coin.WorkerIds.isCpu(deviceId);
            boolean applied;
            if (enabled && revenueMode) {
                WorkerAssignmentService.WorkerView worker = ownWorkers.workers().stream()
                        .filter(value -> deviceId.equals(value.deviceId())).findFirst().orElse(null);
                applied = worker != null && coinRevenueMode != null
                        && coinRevenueMode.applyBestStartPoint(deviceId, worker.coin(), mode, true).success();
            } else applied = enabled ? ownWorkers.start(deviceId) : ownWorkers.pause(deviceId);
            if (applied && enabled && ownControls.get().manuallyStopped(deviceId))
                applied = ownControls.setWorkerManuallyStopped(deviceId, false);
            if (applied && !enabled && ownControls.get().startsOnBoot(deviceId))
                applied = ownControls.setWorkerManuallyStopped(deviceId, true);
            if (applied && !enabled && coinRevenueMode != null) coinRevenueMode.cancelLocalStart(deviceId);
            if (applied) status.invalidate("workers", "overview", "power-control", "energy");
            return applied;
        } finally {
            if (enabled) {
                ownMining.endWorkerStart(deviceId);
                status.invalidate("workers", "overview");
            }
        }
    }

    public record WorkerControlResult(boolean applied, String error) { }

    private record CachedSnapshot(AgentCard card, Instant expiresAt) { }
}
