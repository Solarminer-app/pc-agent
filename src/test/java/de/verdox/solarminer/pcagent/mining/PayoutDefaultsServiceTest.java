package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayoutDefaultsServiceTest {
    @TempDir Path directory;

    private HttpServer server;
    private final AtomicReference<String> response = new AtomicReference<>("[]");

    @BeforeEach
    void startProxyStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopProxyStub() {
        server.stop(0);
    }

    private PayoutDefaultsService service(String fileName) {
        ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
        when(proxy.host()).thenReturn("127.0.0.1");
        ReferralConfigurationService referral = mock(ReferralConfigurationService.class);
        when(referral.get()).thenReturn("solarminer");
        return new PayoutDefaultsService(new ObjectMapper(), proxy, referral,
                directory.resolve(fileName).toString(), server.getAddress().getPort());
    }

    @Test
    void prefersTheTargetTheFeeBackendMarksAsTheHouseTarget() {
        response.set("""
                [{"targetId":"solarminer-referral-friend-xmr","poolAddress":"stratum+tcp://ref.example:7029",
                  "workerName":"4Referrer.worker","password":"","percentage":1.0,"house":false},
                 {"targetId":"solarminer-xmr-randomx","poolAddress":"stratum+tcp://xmr.kryptex.network:7029",
                  "workerName":"4House.solarminer","password":"","percentage":2.5,"house":true}]
                """);
        PayoutDefaultsService.DefaultPayout payout = service("defaults.json").resolve("monero").orElseThrow();
        assertEquals("solarminer-xmr-randomx", payout.targetId());
        assertEquals("stratum+tcp://xmr.kryptex.network:7029", payout.poolUrl());
        assertEquals("4House.solarminer", payout.login());
    }

    @Test
    void fallsBackToTheSingleTargetWhenTheBackendHasNoHouseFlag() {
        String wallet = "prl1pnctccpanlhgxjgyg2m7np2r4ccjzd78hn4r8gc3skkdywk8nxt0qdzg4l0";
        response.set("""
                [{"targetId":"solarminer-prl-pearlhash","poolAddress":"stratum+ssl://prl.kryptex.network:8048",
                  "workerName":"%s/solarminer","password":"","percentage":2.5}]
                """.formatted(wallet));
        PayoutDefaultsService.DefaultPayout payout = service("defaults.json").resolve("pearl").orElseThrow();
        assertEquals(wallet, payout.walletPart());
        assertEquals("solarminer", payout.workerPart());
        assertTrue(payout.maskedWallet().startsWith("prl1pn"));
        assertTrue(payout.maskedWallet().endsWith("g4l0"));
        assertFalse(payout.maskedWallet().contains(wallet));
    }

    @Test
    void ignoresTargetsThatCannotRouteMining() {
        response.set("""
                [{"targetId":"zero","poolAddress":"stratum+tcp://a.example:7029","workerName":"4a.b","password":"","percentage":0,"house":true},
                 {"targetId":"no-worker","poolAddress":"stratum+tcp://b.example:7029","workerName":"","password":"","percentage":2.5,"house":true},
                 {"targetId":"no-scheme","poolAddress":"b.example:7029","workerName":"4c.d","password":"","percentage":2.5,"house":true}]
                """);
        assertTrue(service("defaults.json").resolve("monero").isEmpty());
        assertFalse(service("defaults.json").available("monero"));
    }

    @Test
    void gpuCoinsRequireAnExplicitHouseTargetAndSplitDotWorker() {
        String wallet = "RHaGK3iARQdKgZ6VPDP4N5chP3aVgUUfz7";
        response.set("""
                [{"targetId":"referrer","poolAddress":"stratum+tcp://rvn.kryptex.network:7031",
                  "workerName":"%s.friend","password":"x","percentage":1,"house":false}]
                """.formatted(wallet));
        assertTrue(service("rvn.json").resolve("ravencoin").isEmpty());
        response.set("""
                [{"targetId":"house","poolAddress":"stratum+tcp://rvn.2miners.com:6060",
                  "workerName":"%s.solarminer","password":"x","percentage":2.5,"house":true}]
                """.formatted(wallet));
        PayoutDefaultsService.DefaultPayout payout = service("rvn.json").resolve("ravencoin").orElseThrow();
        assertEquals(wallet, payout.walletPart());
        assertEquals("solarminer", payout.workerPart());
        assertFalse(payout.maskedWallet().contains(wallet));
    }

    @Test
    void remembersTheDefaultChoicePerCoinAcrossRestarts() {
        PayoutDefaultsService first = service("chosen.json");
        first.markDefault("pearl", true);
        assertTrue(first.usesDefault("pearl"));
        assertFalse(first.usesDefault("monero"));

        PayoutDefaultsService restarted = service("chosen.json");
        assertTrue(restarted.usesDefault("pearl"));
        restarted.markDefault("pearl", false);
        assertFalse(service("chosen.json").usesDefault("pearl"));
    }

    @Test
    void rejectsCoinKeysThatWouldBreakTheProxyPath() {
        Optional<PayoutDefaultsService.DefaultPayout> payout = service("defaults.json").resolve("../fees?coin=pearl");
        assertTrue(payout.isEmpty());
    }
}
