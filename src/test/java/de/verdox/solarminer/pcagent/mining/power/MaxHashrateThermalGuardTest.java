package de.verdox.solarminer.pcagent.mining.power;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class MaxHashrateThermalGuardTest {
    @TempDir Path directory;

    @Test
    void pausesHotOrUnreadableGpuButLeavesCoolGpuRunning() {
        AgentControlSettingsService controls = new AgentControlSettingsService(
                new ObjectMapper().findAndRegisterModules(), directory.resolve("control.json").toString());
        assertTrue(controls.setWorkerAssignment("GPU-a", "pearl", "srbminer-multi"));
        assertTrue(controls.setWorkerPowerMode("GPU-a", "MAX_HASHRATE"));
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        LocalGpuPowerService.Gpu card = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test", 80, 250,
                100, 220, 200, 150.0, "measured", true, null);
        when(gpus.snapshot()).thenReturn(List.of(card));
        MiningService mining = mock(MiningService.class);
        when(mining.getWorkerStats()).thenReturn(List.of(new MinerStats.Worker(MinerStats.MinerStatus.MINING,
                "GPU-a", "PearlHash", 0.001, 79, 200, 80, 200, 250, 190, List.of(), "GPU", "Test", "GPU-a",
                null, null, null)));
        WorkerAssignmentService workers = mock(WorkerAssignmentService.class);
        MaxHashrateThermalGuard guard = new MaxHashrateThermalGuard(controls, gpus, mining, workers,
                new LocalRunLock(), 80);

        when(gpus.readTemperatureC(card)).thenReturn(79.0, 80.0, null);
        guard.check();
        verify(workers, never()).pause("GPU-a");
        guard.check();
        guard.check();
        verify(workers, times(2)).pause("GPU-a");

        when(mining.getWorkerStats()).thenThrow(new IllegalStateException("telemetry unavailable"));
        guard.check();
        verify(workers, times(3)).pause("GPU-a");
        var thermal = mock(de.verdox.solarminer.pcagent.mining.measurement.WorkerTemperatureSafetyService.class);
        guard.setThermalSafety(thermal);
        guard.check();
        verify(thermal).stopForTemperature("GPU-a", null, 80);
        verify(workers, times(3)).pause("GPU-a");
    }
}
