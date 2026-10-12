package de.verdox.solarminer.pcagent.mining.network;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
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
import java.util.List;
import java.util.Map;
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
    private final AgentIdentityService ownIdentity;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1200)).build();
    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();
    /** Read-only LAN board consumed by REST; all peer HTTP work happens off request threads. */
    private volatile List<AgentCard> snapshot = List.of();

    public NetworkDashboardService(AgentNetworkDiscoveryService discovery, MiningService ownMining,
                                   MarketDataService ownEarnings, AgentIdentityService ownIdentity,
                                   ObjectMapper mapper,
                                   @org.springframework.beans.factory.annotation.Value("${server.port:8084}") int ownDashboardPort) {
        this.discovery = discovery;
        this.ownMining = ownMining;
        this.ownEarnings = ownEarnings;
        this.ownIdentity = ownIdentity;
        this.ownDashboardPort = ownDashboardPort;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final int ownDashboardPort;

    /** One card per machine from the last completed background refresh; never performs I/O. */
    public List<AgentCard> cards() {
        return snapshot;
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
        return new AgentCard(discovery.agentId(), ownIdentity.displayName(), null, ownDashboardPort,
                true, true, null, workerRows(workers), earningsRows(forecasts), totals(workers, forecasts),
                Instant.now());
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
                    result.snapshot().totals(), now);
        } else if (result.sharingDisabled()) {
            // The machine is there; its operator keeps worker and earnings data private.
            card = new AgentCard(peer.agentId(), peer.name(), peer.host(), peer.dashboardPort(),
                    true, false, "LAN visibility is disabled on this agent", List.of(), List.of(),
                    new Totals(0, 0, 0, 0), now);
        } else {
            card = new AgentCard(peer.agentId(), peer.name(), peer.host(), peer.dashboardPort(),
                    false, false, "Peer is not reachable", List.of(), List.of(),
                    new Totals(0, 0, 0, 0), now);
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

    private record FetchResult(PeerSnapshot snapshot, boolean sharingDisabled) { }

    private static List<WorkerRow> workerRows(List<MinerStats.Worker> workers) {
        return workers.stream()
                .filter(worker -> worker.miningStatus() == MinerStats.MinerStatus.MINING
                        || worker.miningStatus() == MinerStats.MinerStatus.ERROR)
                .map(worker -> new WorkerRow(worker.workerDisplayName(), worker.currentAlgorithm(),
                        worker.hardwareType(), worker.hardwareModel(),
                        worker.terahashPerSecond() * 1e12,
                        worker.approximatedPowerUsageWatts(), worker.miningStatus().name()))
                .toList();
    }

    private static List<EarningsRow> earningsRows(List<MarketDataService.Forecast> forecasts) {
        return forecasts.stream()
                .filter(forecast -> forecast.available() && forecast.hashrateHps() > 0)
                .map(forecast -> new EarningsRow(forecast.coin(), forecast.ticker(),
                        forecast.hashrateHps(), forecast.usdPerDay()))
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

    /** The wire shape a peer publishes; also what the dashboard renders per machine. */
    public record AgentCard(String agentId, String name, String host, int dashboardPort,
                            boolean reachable, boolean own, String error,
                            List<WorkerRow> workers, List<EarningsRow> earnings, Totals totals,
                            Instant collectedAt) { }

    public record WorkerRow(String name, String algorithm, String hardwareType, String hardwareModel,
                            double hashrateHps, long watts, String status) { }

    public record EarningsRow(String coin, String ticker, double hashrateHps, double usdPerDay) { }

    public record Totals(int workerCount, double hashrateHps, long watts, double usdPerDay) { }

    /** Deserialized peer payload; unknown fields stay tolerated across agent versions. */
    public record PeerSnapshot(List<WorkerRow> workers, List<EarningsRow> earnings, Totals totals) { }

    /** What this agent publishes on {@code GET /api/agent/external/network-snapshot}. */
    public PeerSnapshot ownSnapshot() {
        AgentCard own = snapshot.stream().filter(AgentCard::own).findFirst().orElse(null);
        return own == null ? new PeerSnapshot(List.of(), List.of(), new Totals(0, 0, 0, 0))
                : new PeerSnapshot(own.workers(), own.earnings(), own.totals());
    }

    private record CachedSnapshot(AgentCard card, Instant expiresAt) { }
}
