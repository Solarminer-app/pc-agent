package de.verdox.solarminer.pcagent.mining.economics;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerCoinRevenueModeServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test
    void independentSelectionPermissionsConstrainBothGoals() {
        var hopping = mock(CoinHoppingService.class);
        var assignments = mock(WorkerAssignmentService.class);
        var worker = mock(WorkerAssignmentService.WorkerView.class);
        when(worker.deviceId()).thenReturn("gpu");
        when(worker.coin()).thenReturn("pearl");
        when(worker.minerSoftwareId()).thenReturn("srbminer-multi");
        when(assignments.workers()).thenReturn(List.of(worker));
        var service = new WorkerCoinRevenueModeService(mock(AgentControlSettingsService.class), hopping,
                assignments, mock(LocalGpuPowerService.class), new LocalRunLock(), mock(EconomicPlanningService.class));
        var policies = new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService(
                new com.fasterxml.jackson.databind.ObjectMapper(), directory.resolve("selection.json").toString());
        service.setWorkerPolicies(policies);
        var now = Instant.now();
        var fixed = new CoinHoppingService.Candidate("pearl", 100, 90, 100, 1, now, now, "SWEEP", 1, 1, "srbminer-multi", null, .5);
        var miner = new CoinHoppingService.Candidate("pearl", 100, 90, 200, 2, now, now, "SWEEP", 1, 2, "peakminer", null, 1d);
        var coin = new CoinHoppingService.Candidate("quantus", 100, 90, 300, 3, now, now, "SWEEP", 1, 3, "srbminer-multi", null, 3d);
        var both = new CoinHoppingService.Candidate("quantus", 100, 90, 400, 4, now, now, "SWEEP", 1, 4, "wildrig-ng", null, 2d);
        var choices = List.of(fixed, miner, coin, both);
        when(hopping.candidates("gpu", 0)).thenReturn(choices);
        var pairs = choices.stream().map(c -> new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService.Pair(c.coin(), c.minerSoftwareId())).toList();
        for (boolean allowCoin : List.of(false, true)) for (boolean allowMiner : List.of(false, true)) {
            policies.save("gpu", new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService.Policy(pairs, allowCoin, allowMiner, 75));
            assertEquals(allowCoin ? allowMiner ? both : coin : allowMiner ? miner : fixed,
                    service.selectBestStartPoint("gpu", "pearl", "COIN_REVENUE_PER_KWH").candidate());
            assertEquals(allowCoin ? coin : allowMiner ? miner : fixed,
                    service.selectBestStartPoint("gpu", "pearl", "COIN_PROFIT_PER_DAY").candidate());
        }
    }

    @Test
    void dailyProfitAndHashesPerWattCanSelectDifferentSweepTargets() {
        String id = "GPU-test";
        CoinHoppingService hopping = mock(CoinHoppingService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        WorkerCoinRevenueModeService service = new WorkerCoinRevenueModeService(
                mock(AgentControlSettingsService.class), hopping, assignments,
                mock(LocalGpuPowerService.class), new LocalRunLock(), mock(EconomicPlanningService.class));
        when(assignments.workers()).thenReturn(List.of(worker(id, "quantus", MinerStats.MinerStatus.PAUSED)));

        // Both points use the same coin and miner. At 0.31 EUR/kWh and a 1:1
        // USD conversion, the 100 W point matches the reported comparison:
        // 1.32 - 0.744 = 0.576 per day versus 1.69 - 1.48056 = 0.20944.
        Instant now = Instant.now();
        var efficient = new CoinHoppingService.Candidate("quantus", 100, 100, 229_000_000,
                1.32 / 2.4, now, now, "SWEEP", 1, 1.32, "srbminer-multi", null, 0.576);
        var manual = new CoinHoppingService.Candidate("quantus", 200, 199, 293_000_000,
                1.69 / 4.776, now, now, "SWEEP", 1, 1.69, "srbminer-multi", null, 0.20944);
        assertTrue(efficient.hashrateHps() / efficient.measuredWatts()
                > manual.hashrateHps() / manual.measuredWatts());
        when(hopping.candidates(id, 0)).thenReturn(List.of(efficient, manual));
        assertEquals(100, service.selectBestStartPoint(id, "quantus", "PROFIT_PER_DAY").candidate().limitWatts());

        // Lower electricity prices make the extra hashrate worth its power.
        // Hashes/Watt stays highest at 100 W, but daily profit moves to 200 W.
        var efficientCheap = new CoinHoppingService.Candidate("quantus", 100, 100, 229_000_000,
                1.32 / 2.4, now, now, "SWEEP", 1, 1.32, "srbminer-multi", null, 1.272);
        var manualCheap = new CoinHoppingService.Candidate("quantus", 200, 199, 293_000_000,
                1.69 / 4.776, now, now, "SWEEP", 1, 1.69, "srbminer-multi", null, 1.59448);
        when(hopping.candidates(id, 0)).thenReturn(List.of(efficientCheap, manualCheap));
        assertEquals(200, service.selectBestStartPoint(id, "quantus", "PROFIT_PER_DAY").candidate().limitWatts());
    }

    @Test
    void fixedCoinRevenueStartSelectsMeasuredAlternativeMiner() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(false, false));
        CoinHoppingService hopping = mock(CoinHoppingService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        String id = "GPU-test";
        WorkerCoinRevenueModeService service = new WorkerCoinRevenueModeService(controls, hopping, assignments,
                gpus, new LocalRunLock(), mock(EconomicPlanningService.class));
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, id, "RTX", 100, 300,
                100, 300, 180, 170d, "measured", true, null);
        when(gpus.snapshot()).thenReturn(List.of(gpu));
        when(gpus.setTotalPowerTarget(eq(150L), anyList())).thenReturn(true);
        when(assignments.workers()).thenReturn(List.of(new WorkerAssignmentService.WorkerView(id, "GPU", "RTX",
                "NVIDIA", 0, "quantus", "Quantus", "quantus", "srbminer-multi", "SRBMiner", true,
                true, null, true, MinerStats.MinerStatus.MINING, null, "REVENUE_PER_KWH")));
        when(hopping.candidates(id, 0)).thenReturn(List.of(new CoinHoppingService.Candidate("quantus", 150,
                150, 106, 2, Instant.now(), Instant.now(), "BENCHMARK", 1, 2, "quanpool-miner")));
        when(assignments.applyEconomicAssignment(id, "quantus", "quanpool-miner")).thenReturn(true);
        when(assignments.startAtCurrentPowerTarget(id)).thenReturn(true);

        assertTrue(service.applyBestStartPoint(id, "quantus", "REVENUE_PER_KWH", true).success());
        verify(assignments).applyEconomicAssignment(id, "quantus", "quanpool-miner");
        verify(assignments).startAtCurrentPowerTarget(id);
    }

    @Test
    void modeChangeCanStartWithHeldLockButLeavesCrashRecoveryToMinerEngine() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(false, false));
        CoinHoppingService hopping = mock(CoinHoppingService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        EconomicPlanningService planning = mock(EconomicPlanningService.class);
        LocalRunLock lock = new LocalRunLock();
        WorkerCoinRevenueModeService service = new WorkerCoinRevenueModeService(
                controls, hopping, assignments, gpus, lock, planning);
        String id = "GPU-test";
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, true, Map.of(),
                Map.of(id, "ravencoin"), Map.of(), true, false, Map.of(),
                Map.of(id, "COIN_REVENUE_PER_KWH")));
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, Instant.now(), List.of(), null));
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, id, "Test GPU", 100, 300,
                100, 300, 180, 170d, "measured", true, null);
        when(gpus.snapshot()).thenReturn(List.of(gpu));
        when(gpus.setTotalPowerTarget(eq(150L), anyList())).thenReturn(true);
        when(assignments.applyEconomicAssignment(id, "pearl", null)).thenReturn(true);
        when(assignments.startAtCurrentPowerTarget(id)).thenReturn(true);
        when(hopping.candidates(id, 0)).thenReturn(List.of(new CoinHoppingService.Candidate(
                "pearl", 150, 140, 1000, 2, Instant.now(), Instant.now(), "SWEEP", 1, 2)));
        var mining = worker(id, "ravencoin", MinerStats.MinerStatus.MINING);
        var paused = worker(id, "pearl", MinerStats.MinerStatus.PAUSED);
        when(assignments.workers()).thenReturn(List.of(mining), List.of(paused), List.of(paused));

        assertTrue(lock.tryBegin("worker-power-mode"));
        assertTrue(service.applyBestStartPoint(id, "ravencoin", "COIN_REVENUE_PER_KWH", false, true).success());
        assertTrue(lock.busy());
        lock.end();

        service.evaluate();
        verify(assignments, times(1)).startAtCurrentPowerTarget(id);
        assertFalse(lock.busy());

        service.resetCooldown(id);
        service.evaluate();
        verify(assignments, times(1)).startAtCurrentPowerTarget(id);
    }

    @Test
    void fixedDailyProfitRetunesWhenCurrentMeasurementHasExpired() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(false, false));
        CoinHoppingService hopping = mock(CoinHoppingService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        EconomicPlanningService planning = mock(EconomicPlanningService.class);
        String id = "GPU-test";
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, true, Map.of(),
                Map.of(id, "pearl"), Map.of(), true, false, Map.of(), Map.of(id, "PROFIT_PER_DAY")));
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, Instant.now(), List.of(), null));
        when(assignments.workers()).thenReturn(List.of(worker(id, "pearl", MinerStats.MinerStatus.MINING)));
        when(gpus.snapshot()).thenReturn(List.of(new LocalGpuPowerService.Gpu("NVIDIA", 0, id, "RTX", 100, 300,
                100, 300, 180, 170d, "measured", true, null)));
        when(hopping.candidates(id, 0)).thenReturn(List.of(candidate("pearl", 150, "srbminer-multi", 3.0)));
        when(gpus.setTotalPowerTarget(eq(150L), anyList())).thenReturn(true);

        new WorkerCoinRevenueModeService(controls, hopping, assignments, gpus, new LocalRunLock(), planning).evaluate();

        verify(gpus).setTotalPowerTarget(eq(150L), anyList());
        verify(assignments, never()).applyEconomicAssignment(anyString(), anyString(), any());
    }

    @Test
    void coinDailyProfitSwitchesToOnlyRemainingCandidateWhenCurrentMeasurementHasExpired() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(false, false));
        CoinHoppingService hopping = mock(CoinHoppingService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        EconomicPlanningService planning = mock(EconomicPlanningService.class);
        String id = "GPU-test";
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, true, Map.of(),
                Map.of(id, "ravencoin"), Map.of(), true, false, Map.of(), Map.of(id, "COIN_PROFIT_PER_DAY")));
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, Instant.now(), List.of(), null));
        when(assignments.workers()).thenReturn(List.of(worker(id, "ravencoin", MinerStats.MinerStatus.MINING)));
        when(gpus.snapshot()).thenReturn(List.of(new LocalGpuPowerService.Gpu("NVIDIA", 0, id, "RTX", 100, 300,
                100, 300, 180, 170d, "measured", true, null)));
        when(hopping.candidates(id, 0)).thenReturn(List.of(candidate("pearl", 150, "srbminer-multi", 3.0)));
        when(assignments.applyEconomicAssignment(id, "pearl", "srbminer-multi")).thenReturn(true);
        when(gpus.setTotalPowerTarget(eq(150L), anyList())).thenReturn(true);
        when(assignments.startAtCurrentPowerTarget(id)).thenReturn(true);

        new WorkerCoinRevenueModeService(controls, hopping, assignments, gpus, new LocalRunLock(), planning).evaluate();

        verify(assignments).applyEconomicAssignment(id, "pearl", "srbminer-multi");
        verify(gpus).setTotalPowerTarget(eq(150L), anyList());
        verify(assignments).startAtCurrentPowerTarget(id);
    }

    @Test
    void expiredCurrentPointRestoresOriginalLimitAndMinerWhenSwitchFails() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(false, false));
        CoinHoppingService hopping = mock(CoinHoppingService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        EconomicPlanningService planning = mock(EconomicPlanningService.class);
        String id = "GPU-test";
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, true, Map.of(),
                Map.of(id, "ravencoin"), Map.of(), true, false, Map.of(), Map.of(id, "COIN_PROFIT_PER_DAY")));
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, Instant.now(), List.of(), null));
        when(assignments.workers()).thenReturn(List.of(worker(id, "ravencoin", MinerStats.MinerStatus.MINING)));
        when(gpus.snapshot()).thenReturn(List.of(new LocalGpuPowerService.Gpu("NVIDIA", 0, id, "RTX", 100, 300,
                100, 300, 180, 170d, "measured", true, null)));
        when(hopping.candidates(id, 0)).thenReturn(List.of(candidate("pearl", 150, "srbminer-multi", 3.0)));
        when(assignments.applyEconomicAssignment(id, "pearl", "srbminer-multi")).thenReturn(true);
        when(assignments.applyEconomicAssignment(id, "ravencoin", "srbminer-multi")).thenReturn(true);
        when(gpus.setTotalPowerTarget(eq(150L), anyList())).thenReturn(false);
        when(gpus.setTotalPowerTarget(eq(180L), anyList())).thenReturn(true);
        when(assignments.startAtCurrentPowerTarget(id)).thenReturn(true);

        new WorkerCoinRevenueModeService(controls, hopping, assignments, gpus, new LocalRunLock(), planning).evaluate();

        verify(assignments).applyEconomicAssignment(id, "ravencoin", "srbminer-multi");
        verify(gpus).setTotalPowerTarget(eq(180L), anyList());
        verify(assignments).startAtCurrentPowerTarget(id);
    }

    private static CoinHoppingService.Candidate candidate(String coin, int watts, String miner, double profit) {
        return new CoinHoppingService.Candidate(coin, watts, watts - 10, 1000, 2,
                Instant.now(), Instant.now(), "SWEEP", 1, 2, miner, 1.0, profit);
    }

    private static WorkerAssignmentService.WorkerView worker(String id, String coin, MinerStats.MinerStatus status) {
        return new WorkerAssignmentService.WorkerView(id, "GPU", "Test GPU", "NVIDIA", 0,
                coin, coin, "", "srbminer-multi", "SRBMiner", true, true, null,
                true, status, null, "COIN_REVENUE_PER_KWH");
    }
}
