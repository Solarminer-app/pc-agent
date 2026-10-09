package de.verdox.solarminer.pcagent.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedProxyHealthTest {
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
