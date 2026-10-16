package de.verdox.solarminer.pcagent.mining.economics;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyHealthService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomicPlanningServiceTest {
    @Test
    void workerPlanControlsOnlyLocallyAllowedWorkers() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        MiningService mining = mock(MiningService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        when(lock.tryBegin("economic-plan")).thenReturn(true);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, true,
                Map.of(), Map.of("gpu-a", "pearl", "gpu-b", "pearl"),
                Map.of("gpu-a", "AUTO", "gpu-b", "AUTO")));
        when(assignments.applyEconomicAssignment("gpu-a", "pearl")).thenReturn(true);
        when(assignments.start("gpu-a")).thenReturn(true);
        when(assignments.pause("gpu-b")).thenReturn(true);
        when(mining.getWorkerStats()).thenReturn(List.of(
                worker("gpu-a", MinerStats.MinerStatus.STOPPED),
                worker("gpu-b", MinerStats.MinerStatus.MINING)));
        EconomicPlanningService planning = spy(new EconomicPlanningService(controls, mining,
                mock(LocalGpuPowerService.class), mock(MinerFactory.class), mock(ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(MiningPerformanceProfileStore.class),
                mock(MarketDataService.class), assignments, mock(ProxyHealthService.class), lock));
        var coin = new EconomicPlanningService.CoinCapability("pearl", "pearl", true, true,
                new EconomicPlanningService.PerformanceProfile(1_000, 180, Instant.now(), "BENCHMARK"),
                null, 1.5, Instant.now());
        doReturn(new EconomicPlanningService.Capabilities(1, Instant.now(), List.of(
                new EconomicPlanningService.WorkerCapability("gpu-a", "GPU", "A", "pearl", true, "AUTO", List.of(coin)),
                new EconomicPlanningService.WorkerCapability("gpu-b", "GPU", "B", "pearl", true, "AUTO", List.of(coin)),
                new EconomicPlanningService.WorkerCapability("fixed", "GPU", "C", "pearl", true, "FIXED", List.of(coin))
        ), null)).when(planning).capabilities();

        var result = planning.apply(new EconomicPlanningService.PlanRequest("plan-1", 90, null,
                List.of(new EconomicPlanningService.Assignment("gpu-a", "pearl")), List.of("gpu-a")));

        assertTrue(result.accepted(), result.reason());
        verify(assignments).start("gpu-a");
        verify(assignments).pause("gpu-b");
        verify(assignments, never()).pause("fixed");
        verify(assignments, never()).start("fixed");
        verify(mining, never()).setExternalTarget(anyLong());

        var invalid = planning.apply(new EconomicPlanningService.PlanRequest("plan-2", 90, null,
                List.of(), List.of("gpu-a")));
        assertFalse(invalid.accepted());
        assertEquals("economic_plan_invalid_worker_set", invalid.reason());

        doReturn(new EconomicPlanningService.Capabilities(1, Instant.now(), List.of(
                new EconomicPlanningService.WorkerCapability("gpu-a", "GPU", "A", "pearl", true, "AUTO",
                        List.of(coin), "COIN_REVENUE_PER_KWH"),
                new EconomicPlanningService.WorkerCapability("gpu-b", "GPU", "B", "pearl", true, "AUTO",
                        List.of(coin), "MANUAL")
        ), null)).when(planning).capabilities();
        var localCoin = planning.apply(new EconomicPlanningService.PlanRequest("plan-3", 90, null,
                List.of(), List.of("gpu-a")));
        assertTrue(localCoin.accepted(), localCoin.reason());
    }

    private static MinerStats.Worker worker(String id, MinerStats.MinerStatus status) {
        return new MinerStats.Worker(status, id, "pearl", 0, 0, 180, 100, 180, 250,
                status == MinerStats.MinerStatus.MINING ? 180 : 0, List.of(), "GPU", "Test", id,
                null, null, MinerStats.PoolTelemetry.unavailable());
    }
}
