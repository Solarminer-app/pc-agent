package de.verdox.solarminer.pcagent.mining;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedProxyHealthTest {
    @TempDir Path directory;

    @Test
    void externalModeIsReadyWithoutDownloadingOrStartingALocalProxy() {
        ManagedProxyService proxy = new ManagedProxyService(
                new ProxyReleaseService(new com.fasterxml.jackson.databind.ObjectMapper(), "owner/repo", directory.toString()),
                8090, 3333, 3335, 3334, 3336, 3337, 3338, 3339, "random", false);

        assertTrue(proxy.gate().ready());
    }

    @Test
    void switchingToExternalModeClosesTheLocalProxyGateImmediately() {
        ManagedProxyService proxy = new ManagedProxyService(
                new ProxyReleaseService(new com.fasterxml.jackson.databind.ObjectMapper(), "owner/repo", directory.toString()),
                8090, 3333, 3335, 3334, 3336, 3337, 3338, 3339, "random", true);

        assertTrue(proxy.setStandalone(false));
        assertTrue(proxy.gate().ready());
    }

    @Test
    void releaseRefreshUsesABackoffAfterNetworkFailure() {
        assertTrue(ManagedProxyService.releaseRefreshDue(0, 1_000));
        assertFalse(ManagedProxyService.releaseRefreshDue(1_000, 60_999));
        assertTrue(ManagedProxyService.releaseRefreshDue(1_000, 61_000));
    }

    @Test
    void onlyCurrentProxyInstanceOpensTheGate() throws Exception {
        String health = "{\"service\":\"solarminer-stratum-proxy\",\"instanceId\":\"current\"}";
        assertTrue(ManagedProxyService.matchesHealth(health, "current"));
        assertFalse(ManagedProxyService.matchesHealth(health, "previous"));
        assertFalse(ManagedProxyService.matchesHealth("{\"service\":\"other\",\"instanceId\":\"current\"}", "current"));
    }

    @Test
    void identifiesAnOlderManagedProxyWithoutAcceptingAnUnrelatedHealthEndpoint() throws Exception {
        assertTrue(ManagedProxyService.isSolarMinerProxyHealth(
                "{\"service\":\"solarminer-stratum-proxy\",\"instanceId\":\"previous-run\"}"));
        assertFalse(ManagedProxyService.isSolarMinerProxyHealth("{\"service\":\"other-service\"}"));
    }
}
