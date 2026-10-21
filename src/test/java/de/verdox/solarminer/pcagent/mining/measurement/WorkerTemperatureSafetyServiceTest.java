package de.verdox.solarminer.pcagent.mining.measurement;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkerTemperatureSafetyServiceTest {
    @TempDir Path directory;
    @Test void stopsAtOperatorLimitAndRespectsMeasurementOwnership() {
        var policies = new WorkerBenchmarkPolicyService(new ObjectMapper(), directory.resolve("p.json").toString());
        policies.save("gpu", new WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 72));
        var assignments = mock(WorkerAssignmentService.class);
        var worker = mock(WorkerAssignmentService.WorkerView.class);
        when(worker.deviceId()).thenReturn("gpu"); when(worker.hardwareType()).thenReturn("GPU");
        when(worker.status()).thenReturn(MinerStats.MinerStatus.MINING);
        when(assignments.workers()).thenReturn(List.of(worker));
        var power = mock(LocalGpuPowerService.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "gpu", "RTX", 80, 200, 80, 200, 150, 120d, "sensor", true, null);
        when(power.snapshot()).thenReturn(List.of(gpu)); when(power.readTemperatureC(gpu)).thenReturn(72d);
        var lock = new LocalRunLock();
        var safety = new WorkerTemperatureSafetyService(policies, assignments, power, lock);
        assertTrue(lock.tryBegin("sweep")); safety.evaluate(); verifyNoInteractions(assignments);
        lock.end(); safety.evaluate(); verify(assignments).pause("gpu"); assertFalse(lock.busy());
    }
}
