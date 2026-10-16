package de.verdox.solarminer.pcagent.mining.power;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerPowerModeServiceTest {
    @TempDir Path directory;

    @Test
    void choosesBestMeasuredHashesPerWattWithinGpuBoundsAndPersistsMode() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentControlSettingsService controls = new AgentControlSettingsService(json, directory.resolve("control.json").toString());
        assertTrue(controls.setWorkerAssignment("GPU-a", "pearl", "srbminer-multi"));
        assertTrue(controls.setWorkerPowerMode("GPU-a", "EFFICIENCY"));
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        LocalGpuPowerService.Gpu card = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test", 80, 250,
                100, 220, 200, 150.0, "measured", true, null);
        when(gpus.discover()).thenReturn(List.of(card));
        GpuEfficiencyStore sweeps = new GpuEfficiencyStore(json, directory.resolve("sweeps.json").toString());
        sweeps.save(new GpuEfficiencyStore.Profile("GPU-a", "Test", "pearl", "PearlHash", 150, 120.0,
                150.0, 0.8, Instant.now(), List.of(
                new GpuEfficiencyStore.StepResult(90, 120.0, 90.0, 60.0, true, null),
                new GpuEfficiencyStore.StepResult(150, 120.0, 150.0, 60.0, true, null),
                new GpuEfficiencyStore.StepResult(180, 180.0, 180.0, 90.0, false, null))));
        MiningPerformanceProfileStore benchmarks = new MiningPerformanceProfileStore(json, directory.resolve("bench.json").toString());
        benchmarks.save(new MiningPerformanceProfileStore.Profile("GPU-a", "GPU", "Test", "pearl", "PearlHash",
                160.0, 160, 12, Instant.now(), "BENCHMARK", 160));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, gpus, sweeps, benchmarks);

        assertEquals(160, modes.select("GPU-a", "pearl").watts());
        when(gpus.setTotalPowerTarget(160, List.of(card))).thenReturn(true);
        assertNull(modes.apply("GPU-a", "pearl").error());
        verify(gpus).setTotalPowerTarget(160, List.of(card));
        assertEquals("EFFICIENCY", new AgentControlSettingsService(json, directory.resolve("control.json").toString())
                .get().powerModeFor("GPU-a"));
    }

    @Test
    void rejectsAutomaticModeWithoutMeasurements() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentControlSettingsService controls = new AgentControlSettingsService(json, directory.resolve("empty-control.json").toString());
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        when(gpus.discover()).thenReturn(List.of(new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test",
                80, 250, 100, 220, 200, 150.0, "measured", true, null)));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, gpus,
                new GpuEfficiencyStore(json, directory.resolve("empty-sweeps.json").toString()),
                new MiningPerformanceProfileStore(json, directory.resolve("empty-bench.json").toString()));
        assertEquals("power_mode_no_measured_point", modes.selectForMode("GPU-a", "pearl", "EFFICIENCY").error());
        verify(gpus, never()).setTotalPowerTarget(anyLong(), anyList());
    }

    @Test
    void cpuUsesMeasuredThreadTargetAndCannotEnterCoinSwitchingMode() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentControlSettingsService controls = new AgentControlSettingsService(json, directory.resolve("cpu-control.json").toString());
        MiningPerformanceProfileStore benchmarks = new MiningPerformanceProfileStore(json, directory.resolve("cpu-bench.json").toString());
        benchmarks.save(new MiningPerformanceProfileStore.Profile("cpu", "CPU", "Test CPU", "monero", "RandomX",
                100.0, 100, 12, Instant.now(), "BENCHMARK", 100));
        benchmarks.save(new MiningPerformanceProfileStore.Profile("cpu", "CPU", "Test CPU", "monero", "RandomX",
                140.0, 200, 12, Instant.now(), "BENCHMARK", 200));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, mock(LocalGpuPowerService.class),
                new GpuEfficiencyStore(json, directory.resolve("cpu-sweeps.json").toString()), benchmarks);

        assertEquals(100, modes.selectForMode("cpu", "monero", "EFFICIENCY").watts());
        assertEquals("power_mode_requires_gpu_coin", modes.selectForMode("cpu", "monero", "COIN_REVENUE_PER_KWH").error());
    }
}
