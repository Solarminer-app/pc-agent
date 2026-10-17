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
        when(assignments.applyEconomicAssignment(id, "pearl")).thenReturn(true);
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

    private static WorkerAssignmentService.WorkerView worker(String id, String coin, MinerStats.MinerStatus status) {
        return new WorkerAssignmentService.WorkerView(id, "GPU", "Test GPU", "NVIDIA", 0,
                coin, coin, "", "srbminer-multi", "SRBMiner", true, true, null,
                true, status, null, "COIN_REVENUE_PER_KWH");
    }
}
