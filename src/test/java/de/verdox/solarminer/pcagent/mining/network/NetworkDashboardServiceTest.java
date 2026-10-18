package de.verdox.solarminer.pcagent.mining.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.EfficiencySweepService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.coin.SweepMode;
import de.verdox.solarminer.pcagent.coin.SweepRunState;
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
        assertNull(snapshot.activeSweeps());
        assertNull(snapshot.activeBenchmarks());
    }

    @Test
    void sharedSweepProgressCarriesMeasurementsButNotFreeFormDiagnostics() throws Exception {
        EfficiencySweepService.RunStatus running = new EfficiencySweepService.RunStatus("run-1", "gpu-0",
                "RTX 3070", "pearl", "PearlHash", SweepMode.FULL, SweepRunState.RUNNING, 170,
                List.of(170, 160), List.of(new GpuEfficiencyStore.StepResult(170, 1000d, 168d,
                64d, true, "private step diagnostic")), 3, 12, 0, "private run diagnostic");
        EfficiencySweepService.RunStatus queued = new EfficiencySweepService.RunStatus("run-2", "gpu-1",
                "RTX 3080", "pearl", "PearlHash", SweepMode.FULL, SweepRunState.QUEUED, null,
                List.of(180), List.of(), 0, 12, 0, null);
        EfficiencySweepService.Session session = new EfficiencySweepService.Session(true, "measuring", null,
                0, 2, List.of(), List.of(running, queued), 30L);

        List<NetworkDashboardService.SweepProgress> shared = NetworkDashboardService.activeSweepRuns(session);
        String json = new ObjectMapper().writeValueAsString(shared);

        assertEquals(1, shared.size());
        assertEquals(170, shared.getFirst().limitWatts());
        assertEquals(3, shared.getFirst().samples());
        assertEquals(168d, shared.getFirst().steps().getFirst().avgPowerWatts());
        assertTrue(!json.contains("private"));
        assertTrue(NetworkDashboardService.activeSweepRuns(new EfficiencySweepService.Session(false, "done", null,
                2, 2, List.of(), List.of(running), 0L)).isEmpty());
    }

    @Test
    void sharedBenchmarkProgressIncludesSelectedGpuBeforeFirstSample() {
        WorkerAssignmentService.WorkerView gpu = new WorkerAssignmentService.WorkerView("gpu-0", "GPU",
                "RTX 3070", "NVIDIA", 0, "pearl", "Pearl", "pearlhash", "srbminer", "SRBMiner",
                true, true, "pool.example", true, MinerStats.MinerStatus.MINING, null);
        BenchmarkSessionService.Session waiting = new BenchmarkSessionService.Session(true, "LIVE", "measuring",
                null, null, null, 0, 1, List.of(), null, List.of("gpu-0"), List.of(), false, List.of());
        List<NetworkDashboardService.BenchmarkProgress> pending =
                NetworkDashboardService.activeBenchmarkRuns(waiting, List.of(gpu));
        assertEquals(1, pending.size());
        assertEquals(0, pending.getFirst().samples());

        BenchmarkSessionService.LiveSeries series = new BenchmarkSessionService.LiveSeries("gpu-0", "GPU",
                "RTX 3070", "PearlHash", "srbminer", List.of(new BenchmarkSessionService.SamplePoint(1, 1000d, 165)));
        BenchmarkSessionService.Session measuring = new BenchmarkSessionService.Session(true, "LIVE", "measuring",
                null, null, null, 0, 1, List.of(), null, List.of("gpu-0"), List.of(series), false, List.of());
        NetworkDashboardService.BenchmarkProgress current = NetworkDashboardService.activeBenchmarkRuns(measuring,
                List.of(gpu)).getFirst();
        assertEquals(1, current.samples());
        assertEquals(1000d, current.hashrateHs());
        assertEquals(165L, current.powerWatts());
    }

    @Test
    void newPeerProgressFieldsRoundTripThroughTheLanSnapshot() throws Exception {
        NetworkDashboardService.SweepProgress run = new NetworkDashboardService.SweepProgress("run-1", "gpu-0",
                "RTX 3070", "pearl", "PearlHash", "FULL", 170, List.of(170, 160), List.of(), 2, 12, 0);
        NetworkDashboardService.PeerSnapshot outbound = new NetworkDashboardService.PeerSnapshot(List.of(),
                List.of(), new NetworkDashboardService.Totals(0, 0, 0, 0), null, false,
                List.of(run), List.of());

        ObjectMapper mapper = new ObjectMapper();
        NetworkDashboardService.PeerSnapshot inbound = mapper.readValue(mapper.writeValueAsString(outbound),
                NetworkDashboardService.PeerSnapshot.class);

        assertEquals("gpu-0", inbound.activeSweeps().getFirst().deviceId());
        assertEquals(List.of(170, 160), inbound.activeSweeps().getFirst().plannedLimits());
        assertTrue(inbound.activeBenchmarks().isEmpty());
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
        assertEquals(false, NetworkDashboardService.assignedWorkerRows(List.of(stopped), true, java.util.Set.of("gpu-0"))
                .getFirst().benchmarking());
        assertEquals(false, NetworkDashboardService.assignedWorkerRows(List.of(stopped), false, java.util.Set.of("gpu-0"))
                .getFirst().sweeping());
    }

    @Test
    void activeRemoteWorkerCarriesBenchmarkState() {
        WorkerAssignmentService.WorkerView mining = new WorkerAssignmentService.WorkerView(
                "gpu-0", "GPU", "RTX 3070", "NVIDIA", 0, "ravencoin", "Ravencoin",
                "kawpow", "srbminer", "SRBMiner", true, true, "pool.example", true,
                MinerStats.MinerStatus.MINING, null);

        assertEquals(true, NetworkDashboardService.assignedWorkerRows(List.of(mining), true, java.util.Set.of())
                .getFirst().benchmarking());
        assertEquals(false, NetworkDashboardService.assignedWorkerRows(List.of(mining), false, java.util.Set.of())
                .getFirst().benchmarking());
        assertEquals(true, NetworkDashboardService.assignedWorkerRows(List.of(mining), false, java.util.Set.of("gpu-0"))
                .getFirst().sweeping());
        assertEquals(false, NetworkDashboardService.assignedWorkerRows(List.of(mining), false, java.util.Set.of("gpu-1"))
                .getFirst().sweeping());
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
