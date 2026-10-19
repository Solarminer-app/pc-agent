package de.verdox.solarminer.pcagent.lowlevel;

import de.verdox.gputoolkit.GpuPrerequisiteProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GpuPrerequisiteServiceTest {
    @TempDir Path temp;

    @Test void verifiedPowerWriteOverridesContainerPrivilegeHeuristic() throws Exception {
        Path status = temp.resolve("status");
        Files.writeString(status, "Uid:\t0\t0\t0\t0\n");
        GpuPrerequisiteProbe probe = new GpuPrerequisiteProbe("Linux", true, command ->
                command.stream().anyMatch(arg -> arg.contains("persistence_mode")) ? "Enabled" : "0", status);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        GpuTuningService tuning = mock(GpuTuningService.class);
        when(power.snapshot()).thenReturn(List.of(new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-one",
                "RTX", 100, 300, 100, 300, 250, 150.0, "measured", true, null)));
        when(tuning.capabilities("GPU-one")).thenReturn(new GpuTuningService.Capabilities("GPU-one", true,
                false, false, 1000, 2000, null, null, null));
        GpuPrerequisiteService service = new GpuPrerequisiteService(power, tuning, probe);

        service.refresh();

        GpuPrerequisiteService.DeviceStatus device = service.snapshot().getFirst();
        assertEquals(GpuPrerequisiteProbe.State.READY, device.state());
        assertEquals(GpuPrerequisiteProbe.State.READY, device.checks().stream()
                .filter(check -> check.code().equals("privilege")).findFirst().orElseThrow().state());
        assertEquals(GpuPrerequisiteProbe.State.MISSING, device.checks().stream()
                .filter(check -> check.code().equals("memory_clock")).findFirst().orElseThrow().state());
    }
}
