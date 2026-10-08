package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.xmr.XmrConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;

class ProxyConfigurationServiceTest {
    @TempDir Path directory;

    @Test
    void etcReadinessUsesReportedListenerStateWithoutOpeningStratumSocket() throws Exception {
        AtomicBoolean listenerOnline = new AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/network/ip", exchange -> respond(exchange, "127.0.0.1"));
        server.createContext("/api/dashboard", exchange -> respond(exchange,
                "{\"coins\":[{\"coin\":\"ethereumclassic\",\"port\":3337,\"listenerStatus\":\""
                        + (listenerOnline.get() ? "online" : "offline") + "\"}]}"));
        server.createContext("/api/v1/fees/ethereumclassic/targets", exchange -> respond(exchange,
                "[{\"targetId\":\"house\",\"poolAddress\":\"stratum+tcp://pool.example:1010\","
                        + "\"workerName\":\"house.rig\",\"percentage\":2.5,\"house\":true}]"));
        server.start();
        try {
            Path file = directory.resolve("probe-host.txt");
            ProxyConfigurationService proxy = new ProxyConfigurationService(new ObjectMapper(),
                    new ManagedProxyService(false, "./lib/proxy.jar"),
                    new ReferralConfigurationService(file.resolveSibling("probe-referral.txt").toString()),
                    file.toString(), 3335, 3334, 3336, 3337, 3338, 3339, server.getAddress().getPort(),
                    file.resolveSibling("probe-mode.txt").toString(), false);
            assertTrue(proxy.configure("127.0.0.1"));
            assertTrue(proxy.miningReady("ethereumclassic"));
            listenerOnline.set(false);
            assertFalse(proxy.miningReady("ethereumclassic"));
        } finally { server.stop(0); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, data.length);
        try (var output = exchange.getResponseBody()) { output.write(data); }
    }

    @Test
    void acceptsOnlyTheConfiguredSolarMinerProxyPortForEachCoin() {
        Path file = directory.resolve("proxy-host.txt");
        ProxyConfigurationService proxy = proxy(file);

        assertFalse(proxy.matches("stratum+tcp://node.lan:3335", "monero"));
        assertFalse(proxy.configure("stratum+tcp://node.lan:3335"));
        assertTrue(proxy.configure("node.lan"));
        assertTrue(proxy.matches("stratum+tcp://node.lan:3335", "monero"));
        assertTrue(proxy.matches("stratum+tcp://node.lan:3334", "pearl"));
        assertTrue(proxy.matches("stratum+tcp://node.lan:3336", "ravencoin"));
        assertTrue(proxy.matches("stratum+tcp://node.lan:3337", "ethereumclassic"));
        assertTrue(proxy.matches("stratum+tcp://node.lan:3338", "decred"));
        assertTrue(proxy.matches("stratum+tcp://node.lan:3339", "quantus"));
        assertFalse(proxy.matches("stratum+tcp://node.lan:3334", "ravencoin"));
        assertFalse(proxy.matches("stratum+tcp://pool.example:3335", "monero"));
        assertFalse(proxy.matches("stratum+tcp://node.lan:3334", "monero"));
        assertFalse(proxy.matches("stratum+ssl://node.lan:3335", "monero"));
        assertFalse(proxy.matches("stratum+tcp://node.lan:3335/other", "monero"));

        ProxyConfigurationService reloaded = proxy(file);
        assertEquals("node.lan", reloaded.host());
        assertTrue(reloaded.matches("stratum+tcp://node.lan:3335", "monero"));
    }

    @Test
    void refusesAnXmrigConfigurationThatTargetsAPoolDirectly() {
        ProxyConfigurationService proxy = proxy(directory.resolve("proxy-host.txt"));
        assertTrue(proxy.configure("node.lan"));
        XmrConfigService config = new XmrConfigService(new ObjectMapper(), proxy);
        assertThrows(IllegalArgumentException.class, () -> config.configureXmrig(directory.resolve("config.json"),
                "stratum+tcp://pool.example:9200", "pool.example:9200;wallet;x", false));
    }

    @Test
    void externalModeOverridesAndPersistsTheLegacyStandaloneDefault() {
        Path file = directory.resolve("proxy-host.txt");
        ProxyConfigurationService proxy = proxy(file, true);
        assertTrue(proxy.standalone());

        assertTrue(proxy.setMode("external"));
        assertFalse(proxy.standalone());

        ProxyConfigurationService reloaded = proxy(file, true);
        assertFalse(reloaded.standalone());
    }

    @Test
    void feeRollModeDefaultsToRandomAndPersistsTheOperatorChoice() {
        Path file = directory.resolve("proxy-host.txt");
        ProxyConfigurationService proxy = proxy(file);
        assertEquals("random", proxy.rollMode());

        assertTrue(proxy.setRollMode("stateful"));
        assertEquals("stateful", proxy.rollMode());

        ProxyConfigurationService reloaded = proxy(file);
        assertEquals("stateful", reloaded.rollMode());

        assertFalse(proxy.setRollMode("nonsense"));
        assertEquals("stateful", proxy.rollMode());
    }

    private static ProxyConfigurationService proxy(Path file) {
        return proxy(file, false);
    }

    private static ProxyConfigurationService proxy(Path file, boolean standalone) {
        return new ProxyConfigurationService(new ObjectMapper(), new ManagedProxyService(false, "./lib/proxy.jar"),
                new ReferralConfigurationService(file.resolveSibling("referral-key.txt").toString()),
                file.toString(), 3335, 3334, 3336, 3337, 3338, 3339, 8090,
                file.resolveSibling("proxy-mode.txt").toString(), standalone);
    }
}
