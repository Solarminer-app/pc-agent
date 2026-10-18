package de.verdox.solarminer.pcagent.lowlevel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GpuTuningServiceTest {
    @TempDir Path directory;

    @Test
    void discoversNvidiaCoreAndMemoryBoundsSeparately() throws Exception {
        LocalGpuPowerService power = nvidia();
        when(power.runTuningCommand(any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            return command.contains("--lock-memory-clocks-info") ? "runtime modifiable" : "405, 210\n405, 300\n810, 450\n";
        });
        GpuTuningService tuning = new GpuTuningService(power, new ObjectMapper(), directory.resolve("recovery.json").toString());

        var result = tuning.refreshCapabilities("GPU-test");

        assertTrue(result.coreClockControl());
        assertTrue(result.memoryClockControl());
        assertFalse(result.voltageControl());
        assertEquals(210, result.coreMinMhz());
        assertEquals(450, result.coreMaxMhz());
        assertEquals(405, result.memoryMinMhz());
        assertEquals(810, result.memoryMaxMhz());
        assertEquals(List.of(new GpuTuningService.ClockPair(405, 210),
                new GpuTuningService.ClockPair(405, 300), new GpuTuningService.ClockPair(810, 450)),
                result.supportedPairs());
    }

    @Test
    void rejectsOutOfRangeClockWithoutWritingAndRestoresDriverDefaults() throws Exception {
        LocalGpuPowerService power = nvidia();
        when(power.runTuningCommand(any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            return command.contains("--lock-memory-clocks-info") ? "runtime modifiable" : "405, 300\n810, 900\n";
        });
        GpuTuningService tuning = new GpuTuningService(power, new ObjectMapper(), directory.resolve("recovery.json").toString());

        var rejected = tuning.apply("GPU-test", 100, 300, null, null);
        assertFalse(rejected.applied());
        assertEquals("gpu_clock_target_out_of_range", rejected.reason());
        verify(power, times(2)).runTuningCommand(any()); // clock table and lock capability query only

        assertTrue(tuning.restoreDefaults("GPU-test").applied());
        verify(power).runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test", "--reset-gpu-clocks"));
        verify(power, never()).runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test", "--reset-memory-clocks"));
    }

    @Test
    void detectsTuringMemoryRangeWhenOlderCliHasNoLockPolicyQuery() throws Exception {
        LocalGpuPowerService power = nvidia();
        when(power.runTuningCommand(any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("--lock-memory-clocks-info"))
                throw new java.io.IOException("ERROR: Option --lock-memory-clocks-info is not recognized.");
            if (command.contains("--help")) return "-lmc --lock-memory-clocks=\n-rmc --reset-memory-clocks\n";
            if (command.contains("--query-gpu=compute_cap")) return "7.5\n";
            return "7001, 2100\n7001, 2085\n405, 210\n";
        });
        GpuTuningService tuning = new GpuTuningService(power, new ObjectMapper(), directory.resolve("legacy-recovery.json").toString());

        var result = tuning.refreshCapabilities("GPU-test");

        assertTrue(result.memoryClockControl());
        assertEquals(405, result.memoryMinMhz());
        assertEquals(7001, result.memoryMaxMhz());
        assertEquals(210, result.coreMinMhz());
        assertEquals(2100, result.coreMaxMhz());
        assertEquals("gpu_memory_clock_lock_requires_write_test", result.reason());
    }

    @Test
    void keepsReportedMemoryBoundsButRejectsDeferredOnlyAndUnknownLockPolicy() throws Exception {
        LocalGpuPowerService power = nvidia();
        when(power.runTuningCommand(any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            return command.contains("--lock-memory-clocks-info") ? "deferred" : "405, 300\n810, 900\n";
        });
        GpuTuningService tuning = new GpuTuningService(power, new ObjectMapper(), directory.resolve("deferred-recovery.json").toString());

        var result = tuning.refreshCapabilities("GPU-test");

        assertFalse(result.memoryClockControl());
        assertEquals(405, result.memoryMinMhz());
        assertEquals(810, result.memoryMaxMhz());
        verify(power, never()).runTuningCommand(List.of("nvidia-smi", "--help"));
    }

    @Test
    void memoryWriteIsResetOnlyAfterThisAgentTouchesMemory() throws Exception {
        LocalGpuPowerService power = nvidia();
        when(power.runTuningCommand(any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            return command.contains("--lock-memory-clocks-info") ? "runtime modifiable" : "405, 300\n810, 900\n";
        });
        GpuTuningService tuning = new GpuTuningService(power, new ObjectMapper(), directory.resolve("memory-recovery.json").toString());

        assertTrue(tuning.apply("GPU-test", null, null, 405, 810).applied());
        assertTrue(tuning.restoreDefaults("GPU-test").applied());

        verify(power).runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test", "--lock-memory-clocks=405,810"));
        verify(power).runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test", "--reset-memory-clocks"));
    }

    @Test
    void explicitOperatorRestoreIncludesSupportedMemoryLock() throws Exception {
        LocalGpuPowerService power = nvidia();
        when(power.runTuningCommand(any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            return command.contains("--lock-memory-clocks-info") ? "runtime modifiable" : "405, 300\n810, 900\n";
        });
        GpuTuningService tuning = new GpuTuningService(power, new ObjectMapper(), directory.resolve("operator-recovery.json").toString());

        assertTrue(tuning.restoreDriverDefaults("GPU-test").applied());

        verify(power).runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test", "--reset-gpu-clocks"));
        verify(power).runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test", "--reset-memory-clocks"));
    }

    @Test
    void readsAmdSelectableClockLevelsAsDriverBounds() throws Exception {
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        var gpu = new LocalGpuPowerService.Gpu("AMD", 0, "0000:03:00.0", "Test AMD", 50, 300,
                50, 300, 180, 100.0, "test", true, null);
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.runTuningCommand(any())).thenReturn("{\n  \"SYS\": [\n    {\"level\": 0, \"frequency\": \"500 MHz\"},\n    {\"level\": 1, \"frequency\": \"1800 MHz\"}\n  ],\n  \"MEM\": [\n    {\"level\": 0, \"frequency\": \"400 MHz\"},\n    {\"level\": 1, \"frequency\": \"1000 MHz\"}\n  ]\n}");
        GpuTuningService tuning = new GpuTuningService(power, new ObjectMapper(), directory.resolve("amd-recovery.json").toString());

        var result = tuning.refreshCapabilities(gpu.deviceId());

        assertTrue(result.coreClockControl());
        assertTrue(result.memoryClockControl());
        assertEquals(500, result.coreMinMhz());
        assertEquals(1800, result.coreMaxMhz());
        assertEquals(400, result.memoryMinMhz());
        assertEquals(1000, result.memoryMaxMhz());
    }

    @Test
    void readsEffectiveNvidiaClocksWithoutChangingDriverState() throws Exception {
        LocalGpuPowerService power = nvidia();
        when(power.runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test",
                "--query-gpu=clocks.current.graphics,clocks.current.memory", "--format=csv,noheader,nounits")))
                .thenReturn("1200, 7001\n");
        GpuTuningService tuning = new GpuTuningService(power, new ObjectMapper(), directory.resolve("readback.json").toString());

        assertEquals(new GpuTuningService.ClockTelemetry(1200, 7001), tuning.readEffectiveClocks("GPU-test"));
        verify(power).runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test",
                "--query-gpu=clocks.current.graphics,clocks.current.memory", "--format=csv,noheader,nounits"));
    }

    @Test
    void recoversCoreOnlyLockWithoutUnsupportedMemoryReset() throws Exception {
        LocalGpuPowerService power = nvidia();
        when(power.runTuningCommand(any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("--lock-memory-clocks-info")) throw new IllegalStateException("unsupported");
            return "405, 300\n810, 900\n";
        });
        Path marker = directory.resolve("core-only-recovery.json");
        GpuTuningService tuning = new GpuTuningService(power, new ObjectMapper(), marker.toString());

        assertTrue(tuning.apply("GPU-test", 300, 900, null, null).applied());
        assertEquals(false, new ObjectMapper().readTree(Files.readString(marker)).get("GPU-test").asBoolean());

        new GpuTuningService(power, new ObjectMapper(), marker.toString()).recoverInterruptedTuning();

        verify(power).runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test", "--reset-gpu-clocks"));
        verify(power, never()).runTuningCommand(List.of("nvidia-smi", "-i", "GPU-test", "--reset-memory-clocks"));
        assertFalse(Files.exists(marker));
    }

    private LocalGpuPowerService nvidia() {
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-test", "Test NVIDIA", 60, 400,
                60, 400, 200, 100.0, "test", true, null);
        when(power.refresh()).thenReturn(List.of(gpu));
        return power;
    }
}
