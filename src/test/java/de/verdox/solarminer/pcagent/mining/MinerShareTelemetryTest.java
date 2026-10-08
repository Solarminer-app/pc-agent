package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinerShareTelemetryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsXmrigGoodAndTotalCounters() throws Exception {
        MinerShareTelemetry.Counters counters = MinerShareTelemetry.xmrig(
                json.readTree("{\"shares_good\":41,\"shares_total\":44}"));

        assertEquals(41L, counters.accepted());
        assertEquals(3L, counters.rejected());
    }

    @Test
    void readsBothObservedSrbMinerShapes() throws Exception {
        MinerShareTelemetry.Counters direct = MinerShareTelemetry.srbMiner(
                json.readTree("{\"accepted\":12,\"rejected\":2}"));
        MinerShareTelemetry.Counters nested = MinerShareTelemetry.srbMiner(
                json.readTree("{\"shares\":{\"accepted\":7,\"rejected\":1}}"));

        assertEquals(new MinerShareTelemetry.Counters(12L, 2L, null, null, null, null), direct);
        assertEquals(new MinerShareTelemetry.Counters(7L, 1L, null, null, null, null), nested);
    }

    @Test
    void missingCountersRemainUnavailableInsteadOfLookingLikeZeroShares() throws Exception {
        MinerShareTelemetry.Counters counters = MinerShareTelemetry.srbMiner(json.readTree("{\"uptime\":20}"));

        assertNull(counters.accepted());
        assertNull(counters.rejected());
        assertNull(counters.stale());
        assertNull(counters.difficulty());
        assertNull(counters.bestShare());
        assertNull(counters.latencyMs());
    }

    @Test
    void readsPoolQualityFromSrbMiner() throws Exception {
        MinerShareTelemetry.Counters counters = MinerShareTelemetry.srbMiner(json.readTree("""
                {"accepted":120,"rejected":3,"stale":2,"difficulty":4.2E9,"best_ever_share":9.1E9,"latency":41}"""));

        assertEquals(120L, counters.accepted());
        assertEquals(3L, counters.rejected());
        assertEquals(2L, counters.stale());
        assertEquals(4.2E9, counters.difficulty());
        assertEquals(9.1E9, counters.bestShare());
        assertEquals(41L, counters.latencyMs());
    }

    @Test
    void readsXmrigQualityFromResultsAndConnectionEntries() throws Exception {
        MinerShareTelemetry.Counters counters = MinerShareTelemetry.xmrig(
                json.readTree("[{\"accepted\":80,\"rejected\":4,\"stale\":1,\"diff\":2.5E9,\"best_diff\":7.5E9}]"),
                json.readTree("[{\"latency\":38,\"diff\":2.5E9}]"));

        assertEquals(80L, counters.accepted());
        assertEquals(4L, counters.rejected());
        assertEquals(1L, counters.stale());
        assertEquals(2.5E9, counters.difficulty());
        assertEquals(7.5E9, counters.bestShare());
        assertEquals(38L, counters.latencyMs());
    }

    @Test
    void anArrayWrappedNodeIsNotMistakenForMissingData() throws Exception {
        assertEquals(new MinerShareTelemetry.Counters(5L, 1L, null, null, null, null),
                MinerShareTelemetry.srbMiner(json.readTree("[{\"accepted\":5,\"rejected\":1}]")));
        assertEquals(MinerShareTelemetry.Counters.unavailable(), MinerShareTelemetry.srbMiner(json.readTree("[]")));
    }

    @Test
    void aNonNumericDifficultyStaysUnavailable() throws Exception {
        MinerShareTelemetry.Counters counters = MinerShareTelemetry.srbMiner(
                json.readTree("{\"accepted\":1,\"difficulty\":\"n/a\"}"));

        assertEquals(1L, counters.accepted());
        assertNull(counters.difficulty());
    }
}
