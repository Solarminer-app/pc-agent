package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrConfigService;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeeTransparencyServiceTest {
    @Test
    void slowCoinLookupsStartTogetherAndKeepCatalogOrder() throws Exception {
        CountDownLatch arrived = new CountDownLatch(6), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor();
             var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(requests);
            server.createContext("/api/v1/fees/", exchange -> {
                arrived.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) { exchange.sendResponseHeaders(503, -1); return; }
                    byte[] body = "[{\"percentage\":1,\"house\":true}]".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
            when(proxy.host()).thenReturn("127.0.0.1");
            ReferralConfigurationService referral = mock(ReferralConfigurationService.class);
            when(referral.get()).thenReturn("");
            FeeTransparencyService service = new FeeTransparencyService(new ObjectMapper(), proxy, referral,
                    mock(XmrConfigService.class), mock(PearlMinerService.class), mock(GpuCoinMinerService.class),
                    server.getAddress().getPort());
            var result = reader.submit(service::overview);
            try { assertTrue(arrived.await(2, TimeUnit.SECONDS), "All six requests must arrive before any completes"); }
            finally { release.countDown(); }
            var fees = result.get(5, TimeUnit.SECONDS);
            assertEquals(java.util.List.of("monero", "pearl", "ravencoin", "ethereumclassic", "decred", "quantus"),
                    fees.stream().map(FeeTransparencyService.FeeOverview::coin).toList());
            assertTrue(fees.stream().allMatch(FeeTransparencyService.FeeOverview::routeAvailable));
            assertEquals(6, fees.size());
        } finally { release.countDown(); server.stop(0); }
    }
}
