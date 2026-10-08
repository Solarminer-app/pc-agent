package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EarningsForecastServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPublicCurrencySnapshotAndPreservesFreshnessAndSources() throws Exception {
        var payload = mapper.readTree("""
                {"coin":"pearl","ticker":"PRL","algorithm":"PearlHash","available":true,"stale":true,
                 "networkHashrateHps":3.781356619201569E19,"difficulty":32912109.754,
                 "targetBlockSeconds":194,"blockReward":2295.8255488576,"priceUsd":1.32,
                 "updatedAt":"2026-10-04T12:00:00Z","networkSource":"pearlchain.live","priceSource":"coinpaprika"}
                """);

        var result = EarningsForecastService.parsePublicSnapshot(payload);

        assertThat(result.networkHashrateHps()).isEqualTo(3.781356619201569E19);
        assertThat(result.blockReward()).isEqualTo(2295.8255488576d);
        assertThat(result.priceUsd()).isEqualTo(1.32d);
        assertThat(result.stale()).isTrue();
        assertThat(result.sources()).containsExactly("currency.solarminer.app", "pearlchain.live", "coinpaprika");
        assertThat(EarningsForecastService.estimateDailyCoins(60_000_000_000_000d, result))
                .isCloseTo(1.623, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void fetchesAllMetricsFromTheConfiguredCurrencyServiceEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/public/mining-networks", exchange -> {
            byte[] body = """
                    [{"coin":"monero","ticker":"XMR","algorithm":"RandomX","available":true,"stale":false,
                      "networkHashrateHps":6651124960,"difficulty":798134995218,"targetBlockSeconds":120,
                      "blockReward":0.60676846,"priceUsd":542.46,"updatedAt":"2026-10-04T12:00:00Z",
                      "networkSource":"xmrchain.net","priceSource":"CoinGecko"},
                     {"coin":"ravencoin","ticker":"RVN","algorithm":"KAWPOW","available":true,"stale":false,
                      "networkHashrateHps":771818804685,"difficulty":11091.6893,"targetBlockSeconds":57.57,
                      "blockReward":1250,"priceUsd":0.05,"updatedAt":"2026-10-04T12:00:00Z",
                      "networkSource":"rvn.2miners.com","priceSource":"CoinGecko"}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/public/coin-prices", exchange -> {
            byte[] body = "{\"btc\":84035,\"dcr\":18.04,\"qtc\":101.97}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/public/mining-networks");
            var service = new EarningsForecastService(mapper, endpoint, HttpClient.newHttpClient());

            var result = service.fetchSnapshots();

            assertThat(result).containsOnlyKeys("monero", "ravencoin");
            assertThat(result.get("monero").priceUsd()).isEqualTo(542.46d);
            assertThat(result.get("ravencoin").blockReward()).isEqualTo(1250d);
            assertThat(result.get("ravencoin").collectedAt()).isEqualTo(Instant.parse("2026-10-04T12:00:00Z"));
            assertThat(service.prices()).containsEntry("BTC", 84035d).containsEntry("DCR", 18.04d)
                    .containsEntry("QTC", 101.97d);
        } finally {
            server.stop(0);
        }
    }
}
