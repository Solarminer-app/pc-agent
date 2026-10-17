package de.verdox.solarminer.pcagent.mining.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.economics.CoinHoppingService;
import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Read-only scenario inputs. A peer shares these only under its existing LAN visibility consent. */
@Service
public class FleetComparisonService {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private final CoinHoppingService candidates;
    private final WorkerAssignmentService assignments;
    private final LocalGpuPowerService power;
    private final AgentIdentityService identity;
    private final EnergyJournalService energy;
    private final NetworkDashboardService network;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private volatile List<AgentResult> latest = List.of();

    public FleetComparisonService(CoinHoppingService candidates, WorkerAssignmentService assignments,
                                  LocalGpuPowerService power, AgentIdentityService identity,
                                  EnergyJournalService energy, NetworkDashboardService network,
                                  ObjectMapper json) {
        this.candidates = candidates;
        this.assignments = assignments;
        this.power = power;
        this.identity = identity;
        this.energy = energy;
        this.network = network;
        this.json = json;
    }

    public record WorkerPoint(String deviceId, String model, String configuredMiner, int manualTargetWatts,
                              List<CoinHoppingService.Candidate> candidates) { }
    public record AgentPoints(EnergyJournalService.Settings tariff, List<WorkerPoint> workers) { }
    public record AgentResult(String agentId, String name, boolean own, boolean available,
                              AgentPoints points) { }

    public AgentPoints sharedPoints() {
        return ownPoints;
    }

    private volatile AgentPoints ownPoints;

    private AgentPoints collectOwnPoints() {
        var limits = power.snapshot();
        List<WorkerPoint> workers = new ArrayList<>();
        for (var gpu : limits) {
            var assignment = assignments.workers().stream()
                    .filter(worker -> gpu.deviceId().equals(worker.deviceId())).findFirst().orElse(null);
            workers.add(new WorkerPoint(gpu.deviceId(), gpu.model(),
                    assignment == null ? null : assignment.minerSoftwareId(),
                    gpu.maxWatts(), candidates.candidates(gpu.deviceId(), 0)));
        }
        return new AgentPoints(energy.overview().settings(), List.copyOf(workers));
    }

    public List<AgentResult> fleet() { return latest; }

    @EventListener(ApplicationReadyEvent.class)
    public void warm() { Thread.startVirtualThread(this::refresh); }

    @Scheduled(fixedDelayString = "${solarminer.agent.fleet-comparison-refresh-ms:30000}")
    public synchronized void refresh() {
        ownPoints = collectOwnPoints();
        var cards = network.cards();
        var own = cards.stream().filter(NetworkDashboardService.AgentCard::own).findFirst().orElse(null);
        List<AgentResult> result = new ArrayList<>();
        result.add(new AgentResult(own == null ? "local" : own.agentId(),
                own == null ? identity.displayName() : own.name(), true, true, ownPoints));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<AgentResult>> peers = cards.stream()
                    .filter(card -> !card.own())
                    .map(card -> CompletableFuture.supplyAsync(() -> fetch(card), executor)).toList();
            peers.forEach(peer -> result.add(peer.join()));
        }
        latest = List.copyOf(result);
    }

    private AgentResult fetch(NetworkDashboardService.AgentCard peer) {
        if (!peer.reachable() || peer.error() != null || peer.host() == null)
            return new AgentResult(peer.agentId(), peer.name(), false, false, null);
        try {
            String host = peer.host().contains(":") ? "[" + peer.host() + "]" : peer.host();
            URI uri = URI.create("http://" + host + ":" + peer.dashboardPort()
                    + "/api/agent/external/fleet-comparison");
            var request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return new AgentResult(peer.agentId(), peer.name(), false, true,
                    json.readValue(response.body(), AgentPoints.class));
        } catch (Exception ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return new AgentResult(peer.agentId(), peer.name(), false, false, null);
    }
}
