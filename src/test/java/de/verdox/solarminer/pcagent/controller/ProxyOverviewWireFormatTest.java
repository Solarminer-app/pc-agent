package de.verdox.solarminer.pcagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy overview dropped its hardcoded per-coin record fields. The historical flat wire
 * fields ({@code <coinId>Url}, {@code <coinId>FeeReady}) that Solar-Miner-Node and the
 * operator UI read must survive via the derived any-getter, next to the new coinRoutes list.
 */
class ProxyOverviewWireFormatTest {
    @Test
    void legacyFlatCoinFieldsStayOnTheWireAlongsideTheNewRouteList() throws Exception {
        var overview = new MiningController.ProxyOverview("proxy.lan", true, "external", "random",
                "external", "", "v1.2.3",
                List.of(new MiningController.ProxyOverview.CoinRoute("monero", "Monero", "stratum+tcp://proxy.lan:3335", true, false),
                        new MiningController.ProxyOverview.CoinRoute("quantus", "Quantus", "stratum+tcp://proxy.lan:3339", false, true)));

        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(overview));

        assertEquals("stratum+tcp://proxy.lan:3335", json.path("moneroUrl").asText());
        assertTrue(json.path("moneroFeeReady").asBoolean());
        assertEquals("stratum+tcp://proxy.lan:3339", json.path("quantusUrl").asText());
        assertEquals(false, json.path("quantusFeeReady").asBoolean());
        assertEquals("proxy.lan", json.path("host").asText());
        assertEquals(2, json.path("coinRoutes").size());
        assertEquals("monero", json.path("coinRoutes").get(0).path("coin").asText());
    }
}
