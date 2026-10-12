package de.verdox.solarminer.pcagent.mining.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkDashboardServiceTest {

    @Test
    void publishedWorkerRowsCarryTheCoinResolvedFromCanonicalAlgorithmsAndAliases() {
        MinerStats.Worker worker = new MinerStats.Worker(MinerStats.MinerStatus.MINING,
                "SRBMiner GPU 0", "blake3", 0.000_000_001, 60, 120, 60, 120,
                180, 100, List.of(), "GPU", "RTX 3070", "gpu-0", 1L, 0L, null);

        NetworkDashboardService.WorkerRow row = NetworkDashboardService.workerRows(List.of(worker)).getFirst();

        assertEquals("decred", row.coin());
        assertEquals(1_000, row.hashrateHps(), 0.0001);
        assertEquals("gpu-0", row.deviceId());
    }

    @Test
    void publishedEarningsRowsExplicitlyRemainAvailable() {
        MarketDataService.Forecast available = new MarketDataService.Forecast("monero", "XMR", true,
                null, 1_000, 1_000_000, 1, 120, 0.6, 100, 0.02, 2,
                Instant.parse("2026-10-12T00:00:00Z"), false, List.of("test"));
        MarketDataService.Forecast unavailable = new MarketDataService.Forecast("pearl", "PRL", false,
                "missing", 0, 0, 0, 0, 0, 0, 0, 0, null, false, List.of());

        List<NetworkDashboardService.EarningsRow> rows =
                NetworkDashboardService.earningsRows(List.of(available, unavailable));

        assertEquals(1, rows.size());
        assertTrue(rows.getFirst().available());
        assertEquals("monero", rows.getFirst().coin());
    }

    @Test
    void olderPeerSnapshotsWithoutTheAdditiveFieldsStillDeserialize() throws Exception {
        String json = """
                {"workers":[{"name":"CPU","algorithm":"RandomX","hardwareType":"CPU",
                "hardwareModel":"Ryzen","hashrateHps":1000,"watts":50,"status":"MINING"}],
                "earnings":[{"coin":"monero","ticker":"XMR","hashrateHps":1000,"usdPerDay":2}],
                "totals":{"workerCount":1,"hashrateHps":1000,"watts":50,"usdPerDay":2}}
                """;

        NetworkDashboardService.PeerSnapshot snapshot = new ObjectMapper()
                .readValue(json, NetworkDashboardService.PeerSnapshot.class);

        assertNull(snapshot.workers().getFirst().coin());
        assertNull(snapshot.earnings().getFirst().available());
        assertNull(snapshot.energy());
        assertNull(snapshot.controlEnabled());
        assertNull(snapshot.workers().getFirst().controlAllowed());
    }

    @Test
    void assignedWorkerRowsIncludeStoppedWorkersForRemoteStart() {
        WorkerAssignmentService.WorkerView stopped = new WorkerAssignmentService.WorkerView(
                "gpu-0", "GPU", "RTX 3070", "NVIDIA", 0, "ravencoin", "Ravencoin",
                "kawpow", "srbminer", "SRBMiner", true, true, "pool.example", true,
                MinerStats.MinerStatus.STOPPED, null);

        NetworkDashboardService.WorkerRow row = NetworkDashboardService.assignedWorkerRows(List.of(stopped)).getFirst();

        assertEquals("STOPPED", row.status());
        assertEquals("gpu-0", row.deviceId());
        assertTrue(row.controlAllowed());
        assertEquals(0, row.hashrateHps());
    }

    @Test
    void sharedEnergyContainsOnlyAggregatesAndActiveTotals() {
        EnergyJournalService.Aggregate today = new EnergyJournalService.Aggregate(
                new BigDecimal("1.25000"), new BigDecimal("0.38"), 7200, 5400, 0.75);
        EnergyJournalService.Session first = session("one", 250, 3600);
        EnergyJournalService.Session second = session("two", 500, 1800);
        EnergyJournalService.Overview overview = new EnergyJournalService.Overview(
                new EnergyJournalService.Settings(new BigDecimal("0.3000"), "EUR"), today,
                today, today, List.of(first, second), List.of());

        NetworkDashboardService.SharedEnergy shared = NetworkDashboardService.sharedEnergy(overview);

        assertEquals(750, shared.activeWattHours(), 0.0001);
        assertEquals(3600, shared.activeRuntimeSeconds());
        assertEquals(2, shared.activeSessionCount());
        assertEquals(new BigDecimal("1.25000"), shared.today().kilowattHours());
    }

    private static EnergyJournalService.Session session(String id, double wattHours, long runtimeSeconds) {
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        return new EnergyJournalService.Session(id, id, "GPU", "Test GPU", "pearl", "srbminer",
                "pearlhash", now.minusSeconds(runtimeSeconds), now, runtimeSeconds, runtimeSeconds,
                wattHours, 100, 120, 1_000, 1L, 0L, "GPU_BOARD", "RUNNING");
    }
}
