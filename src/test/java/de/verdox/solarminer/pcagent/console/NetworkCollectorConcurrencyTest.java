package de.verdox.solarminer.pcagent.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.verdox.solarminer.pcagent.controller.AgentStatusService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.EfficiencySweepService;
import de.verdox.solarminer.pcagent.mining.network.AgentNetworkDiscoveryService;
import de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NetworkCollectorConcurrencyTest {
    @Test void bothPeersAreRequestedBeforeWaitingForEitherResponse() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        AtomicInteger concurrentResponses = new AtomicInteger();
        HttpServer first = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (HttpServer server : List.of(first, second)) {
            server.createContext("/api/agent/external/network-snapshot", exchange -> {
                entered.countDown();
                try { if (entered.await(1, TimeUnit.SECONDS)) concurrentResponses.incrementAndGet(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                byte[] body = "{\"workers\":[],\"earnings\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
        }
        try {
            var discovery = mock(AgentNetworkDiscoveryService.class);
            when(discovery.peers()).thenReturn(List.of(
                    new AgentNetworkDiscoveryService.Peer("first", "First", "127.0.0.1", first.getAddress().getPort(), Instant.now()),
                    new AgentNetworkDiscoveryService.Peer("second", "Second", "127.0.0.1", second.getAddress().getPort(), Instant.now())));
            var mining = mock(MiningService.class);
            when(mining.getWorkerStats()).thenReturn(List.of());
            var energy = mock(EnergyJournalService.class);
            var overview = mock(EnergyJournalService.Overview.class);
            when(overview.activeSessions()).thenReturn(List.of()); when(energy.overview()).thenReturn(overview);
            var controls = mock(AgentControlSettingsService.class);
            when(controls.get()).thenReturn(mock(AgentControlSettingsService.Settings.class));
            var benchmarks = mock(BenchmarkSessionService.class);
            when(benchmarks.status()).thenReturn(mock(BenchmarkSessionService.Session.class));
            var sweeps = mock(EfficiencySweepService.class);
            when(sweeps.status()).thenReturn(mock(EfficiencySweepService.Session.class));
            var service = new NetworkDashboardService(discovery, mining, mock(MarketDataService.class), energy,
                    mock(WorkerAssignmentService.class), controls, mock(AgentIdentityService.class),
                    mock(AgentStatusService.class), benchmarks, sweeps, new ObjectMapper(), 8084);
            service.refreshSnapshot();
            assertThat(concurrentResponses.get()).isEqualTo(2);
            assertThat(service.cards()).hasSize(3);
            assertThat(service.cards()).allMatch(NetworkDashboardService.AgentCard::reachable);
        } finally { first.stop(0); second.stop(0); }
    }
}
