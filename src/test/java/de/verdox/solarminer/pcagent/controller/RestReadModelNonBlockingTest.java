package de.verdox.solarminer.pcagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyHealthService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST audit Phase 5 (runtime behavior): query endpoints must answer purely from in-memory
 * state. These tests pin the audit's core promise — with an unreachable or dead external
 * source, the REST read models still answer immediately and never trigger collection work
 * themselves.
 */
class RestReadModelNonBlockingTest {

    @Test
    void proxyEndpointAnswersFromSnapshotWithoutTouchingTheProxy() {
        ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
        when(proxy.host()).thenReturn("proxy.invalid");
        when(proxy.standalone()).thenReturn(false);
        when(proxy.rollMode()).thenReturn("random");
        when(proxy.managedStatus()).thenReturn("RUNNING");
        when(proxy.managedDetail()).thenReturn("");
        when(proxy.managedVersion()).thenReturn("1.0.0");
        // A fresh health store has never collected: the endpoint must report not-reachable
        // from the snapshot instead of probing the proxy on the request thread.
        ProxyHealthService health = new ProxyHealthService(proxy);
        MiningController controller = newController(proxy, health);

        MiningController.ProxyOverview overview = controller.proxy();

        assertThat(overview.reachable()).isFalse();
        assertThat(overview.host()).isEqualTo("proxy.invalid");
        assertThat(overview.coinRoutes()).isNotEmpty();
        overview.coinRoutes().forEach(route -> assertThat(route.feeReady()).isFalse());
        // The decisive assertion: the request path never invoked a live proxy check.
        verify(proxy, never()).isReachable();
        verify(proxy, never()).feeReady(any(de.verdox.solarminer.pcagent.coin.Coin.class));
    }

    @Test
    void healthCollectorPublishesConsistentSnapshotAgainstADeadProxy() {
        ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
        when(proxy.host()).thenReturn("127.0.0.1");
        when(proxy.isReachable()).thenReturn(false);
        when(proxy.feeReady(any(de.verdox.solarminer.pcagent.coin.Coin.class))).thenReturn(false);
        ProxyHealthService health = new ProxyHealthService(proxy);

        health.refresh();

        ProxyHealthService.Snapshot snapshot = health.snapshot();
        assertThat(snapshot.initialized()).isTrue();
        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.coins()).isNotEmpty();
        snapshot.coins().values().forEach(coin -> assertThat(coin.feeReady()).isFalse());
        // Reads never block: repeated snapshot reads return the same immutable instance.
        assertThat(health.snapshot()).isSameAs(snapshot);
    }

    @Test
    void forecastsReadPurelyFromCacheAndNeverTriggerUpstreamCollection() {
        AtomicInteger upstreamHits = new AtomicInteger();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                upstreamHits.incrementAndGet();
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            });
            server.start();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/public/mining-networks");
            MarketDataService service = new MarketDataService(new ObjectMapper(), endpoint, endpoint, endpoint);

            List<MarketDataService.Forecast> forecasts = service.forecasts(List.<MinerStats.Worker>of());

            assertThat(forecasts).isNotEmpty();
            assertThat(forecasts).allMatch(forecast -> !forecast.available());
            assertThat(service.prices()).isEmpty();
            assertThat(upstreamHits.get()).isZero();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void payoutDefaultsReadCacheOnlyUntilThePrewarmRuns() {
        AtomicInteger upstreamHits = new AtomicInteger();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                upstreamHits.incrementAndGet();
                byte[] body = ("[{\"targetId\":\"house\",\"poolAddress\":\"stratum+tcp://pool.example:1010\","
                        + "\"workerName\":\"house.rig\",\"percentage\":2.5,\"house\":true}]").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
            when(proxy.host()).thenReturn("127.0.0.1");
            PayoutDefaultsService payouts = new PayoutDefaultsService(new ObjectMapper(), proxy,
                    mock(de.verdox.solarminer.pcagent.mining.fee.ReferralConfigurationService.class),
                    mock(de.verdox.solarminer.pcagent.mining.fee.FeeTierService.class),
                    java.nio.file.Files.createTempFile("payout", ".json").toString(),
                    server.getAddress().getPort());

            PayoutDefaultsService.DefaultView view = payouts.view("monero");

            assertThat(view.available()).isFalse();
            assertThat(upstreamHits.get()).isZero();

            payouts.prewarm();
            assertThat(payouts.view("monero").available()).isTrue();
            int afterPrewarm = upstreamHits.get();
            assertThat(afterPrewarm).isPositive();
            // Reads after the pre-warm reuse the cache: no additional upstream calls.
            payouts.view("monero");
            payouts.view("pearl");
            assertThat(upstreamHits.get()).isEqualTo(afterPrewarm);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static MiningController newController(ProxyConfigurationService proxy, ProxyHealthService health) {
        MiningService mining = mock(MiningService.class);
        return new MiningController(mining,
                mock(de.verdox.solarminer.pcagent.xmr.XmrConfigService.class),
                mock(de.verdox.solarminer.pcagent.pearl.PearlMinerService.class),
                mock(de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService.class),
                mock(de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.class),
                mock(de.verdox.solarminer.pcagent.xmr.XmrMinerService.class),
                proxy,
                mock(de.verdox.solarminer.pcagent.miner.srb.SrbDownloadService.class),
                mock(de.verdox.solarminer.pcagent.xmr.download.XmrDownloadService.class),
                mock(de.verdox.solarminer.pcagent.lowlevel.sensor.WindowsLhmBootstrapService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyDiscoveryService.class),
                mock(MarketDataService.class),
                mock(PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.fee.ReferralConfigurationService.class),
                mock(de.verdox.solarminer.pcagent.mining.fee.FeeTransparencyService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.WalletBalanceService.class),
                mock(de.verdox.solarminer.pcagent.mining.WindowsDefenderExclusionService.class),
                mock(de.verdox.solarminer.pcagent.mining.MinerCatalogService.class),
                new de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock(),
                mock(de.verdox.solarminer.pcagent.miner.MinerFactory.class),
                health, null);
    }
}
