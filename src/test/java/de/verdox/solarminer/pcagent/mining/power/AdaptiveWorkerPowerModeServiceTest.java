package de.verdox.solarminer.pcagent.mining.power;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdaptiveWorkerPowerModeServiceTest {
    @Test
    void measuredImprovementRetunesOneRunningGpuAndRespectsCooldown() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        WorkerPowerModeService modes = mock(WorkerPowerModeService.class);
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        MiningPerformanceProfileStore profiles = mock(MiningPerformanceProfileStore.class);
        GpuEfficiencyStore sweeps = mock(GpuEfficiencyStore.class);
        LocalRunLock lock = new LocalRunLock();
        var service = new AdaptiveWorkerPowerModeService(controls, assignments, modes, gpus, profiles, sweeps, lock);
        var settings = new AgentControlSettingsService.Settings(true, true, Map.of(),
                Map.of("gpu-1", "ravencoin"), Map.of(), true, false, Map.of(),
                Map.of("gpu-1", "EFFICIENCY"));
        when(controls.get()).thenReturn(settings);
        MinerStats.Worker telemetry = mock(MinerStats.Worker.class);
        when(telemetry.currentAlgorithm()).thenReturn("kawpow");
        var worker = new WorkerAssignmentService.WorkerView("gpu-1", "GPU", "RTX Test", "NVIDIA", 0,
                "ravencoin", "Ravencoin", "kawpow", "srbminer", "SRBMiner", true, true, null,
                true, MinerStats.MinerStatus.MINING, telemetry);
        when(assignments.workers()).thenReturn(List.of(worker));
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "gpu-1", "RTX Test", 100, 300,
                100, 300, 180, 170d, "measured", true, null);
        when(gpus.snapshot()).thenReturn(List.of(gpu));
        when(gpus.refresh()).thenReturn(List.of(gpu));
        when(modes.selectForMode("gpu-1", "ravencoin", "EFFICIENCY"))
                .thenReturn(new WorkerPowerModeService.Selection("EFFICIENCY", 150, null));
        when(profiles.all()).thenReturn(List.of(
                profile(180, 1000, 200), profile(150, 1200, 180)));
        when(gpus.setTotalPowerTarget(eq(150L), anyList())).thenReturn(true);

        Instant now = Instant.parse("2026-10-17T10:00:00Z");
        service.evaluateWorker(worker, now);
        service.evaluateWorker(worker, now.plusSeconds(60));

        verify(gpus, times(1)).setTotalPowerTarget(eq(150L), eq(List.of(gpu)));
        assertFalse(lock.busy());
    }

    @Test
    void rejectsNoiseAndMissingComparisonEvidence() {
        assertFalse(AdaptiveWorkerPowerModeService.worthChanging(100d, 104d));
        assertFalse(AdaptiveWorkerPowerModeService.worthChanging(null, 120d));
        assertTrue(AdaptiveWorkerPowerModeService.worthChanging(100d, 106d));
    }

    private static MiningPerformanceProfileStore.Profile profile(int target, double hashrate, long watts) {
        return new MiningPerformanceProfileStore.Profile("gpu-1", "GPU", "RTX Test", "ravencoin",
                "kawpow", hashrate, watts, 12, Instant.parse("2026-10-17T09:00:00Z"), "LIVE", target);
    }
}
