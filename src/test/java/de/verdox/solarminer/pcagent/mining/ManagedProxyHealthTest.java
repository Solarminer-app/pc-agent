package de.verdox.solarminer.pcagent.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedProxyHealthTest {
    @Test
    void onlyCurrentProxyInstanceOpensTheGate() throws Exception {
        String health = "{\"service\":\"solarminer-stratum-proxy\",\"instanceId\":\"current\"}";
        assertTrue(ManagedProxyService.matchesHealth(health, "current"));
        assertFalse(ManagedProxyService.matchesHealth(health, "previous"));
        assertFalse(ManagedProxyService.matchesHealth("{\"service\":\"other\",\"instanceId\":\"current\"}", "current"));
    }
}
