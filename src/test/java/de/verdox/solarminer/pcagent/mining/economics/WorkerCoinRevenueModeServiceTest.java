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
    @Test
    void fixedCoinRevenueStartSelectsMeasuredAlternativeMiner() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
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
    void modeChangeCanStartWhileControllerHoldsLockAndRecoversAfterUnexpectedStop() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
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
        verify(assignments, times(2)).startAtCurrentPowerTarget(id);
        assertFalse(lock.busy());

        service.cancelLocalStart(id);
        service.evaluate();
        verify(assignments, times(2)).startAtCurrentPowerTarget(id);
    }

    @Test
    void fixedDailyProfitRetunesWhenCurrentMeasurementHasExpired() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
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
