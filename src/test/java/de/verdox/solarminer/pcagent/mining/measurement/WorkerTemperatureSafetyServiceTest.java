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
        lock.end(); safety.evaluate(); verify(assignments).pause("gpu", "Temperaturstopp / Temperature stop: 72.0 °C >= 72.0 °C"); assertFalse(lock.busy());
    }

    @Test void sustainedThermalFailureRejectsOnlyTheAppliedPointAndStopsWithoutRestart() {
        var json = new ObjectMapper().findAndRegisterModules();
        var policies = new WorkerBenchmarkPolicyService(json, directory.resolve("thermal.json").toString());
        policies.save("gpu", new WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 84));
        var assignments = mock(WorkerAssignmentService.class);
        var worker = mock(WorkerAssignmentService.WorkerView.class);
        when(worker.deviceId()).thenReturn("gpu"); when(worker.hardwareType()).thenReturn("GPU");
        when(worker.coin()).thenReturn("pearl"); when(worker.minerSoftwareId()).thenReturn("peakminer");
        when(worker.status()).thenReturn(MinerStats.MinerStatus.MINING);
        when(assignments.workers()).thenReturn(List.of(worker));
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 2, "gpu", "TITAN RTX", 100, 280, 100, 280, 280, 272d, "sensor", true, null);
        var power = mock(LocalGpuPowerService.class);
        when(power.snapshot()).thenReturn(List.of(gpu)); when(power.readTemperatureC(gpu)).thenReturn(84d);
        var points = mock(de.verdox.solarminer.pcagent.mining.economics.MeasuredOperatingPointService.class);
        var store = new de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore(json, directory.resolve("rejected.json").toString());
        var point = new de.verdox.solarminer.pcagent.mining.economics.CoinHoppingService.Candidate("pearl", 280, 197, 1000, 1,
                java.time.Instant.now().minusSeconds(60), java.time.Instant.now(), "SWEEP", 1, 2, "peakminer");
        when(points.current("gpu")).thenReturn(point);
        var safety = new WorkerTemperatureSafetyService(policies, assignments, power, new LocalRunLock());
        safety.setPointSafety(store, points);
        safety.evaluate();
        assertFalse(store.permits("gpu", point));
        assertTrue(store.permits("sibling", point));
        verify(assignments).pause("gpu", "Temperaturstopp / Temperature stop: 84.0 °C >= 84.0 °C");
        verify(assignments, never()).start(anyString());

        // A sensor failure must stop safely, but is not evidence against a profile.
        var unavailable = new de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore(json, directory.resolve("unavailable.json").toString());
        safety.setPointSafety(unavailable, points);
        when(power.readTemperatureC(gpu)).thenReturn(null);
        safety.evaluate();
        assertTrue(unavailable.permits("gpu", point));
        verify(assignments).pause("gpu", "Temperaturstopp: GPU-Temperatur nicht lesbar / Temperature stop: GPU temperature unavailable");
    }

    @Test void persistenceFailureDoesNotPreventEmergencyStop() {
        var policies = mock(WorkerBenchmarkPolicyService.class);
        when(policies.get("gpu")).thenReturn(new WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 84));
        var assignments = mock(WorkerAssignmentService.class);
        var worker = mock(WorkerAssignmentService.WorkerView.class);
        when(worker.deviceId()).thenReturn("gpu"); when(worker.hardwareType()).thenReturn("GPU");
        when(worker.status()).thenReturn(MinerStats.MinerStatus.MINING);
        when(assignments.workers()).thenReturn(List.of(worker));
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 2, "gpu", "TITAN RTX", 100, 280, 100, 280, 280, 272d, "sensor", true, null);
        var power = mock(LocalGpuPowerService.class);
        when(power.snapshot()).thenReturn(List.of(gpu)); when(power.readTemperatureC(gpu)).thenReturn(84d);
        when(policies.ceiling("gpu", 90)).thenReturn(84d);
        var store = mock(de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore.class);
        doThrow(new IllegalStateException("power_mode_point_safety_unavailable")).when(store).reject(eq("gpu"), anyString());
        var safety = new WorkerTemperatureSafetyService(policies, assignments, power, new LocalRunLock());
        safety.setPointSafety(store, mock(de.verdox.solarminer.pcagent.mining.economics.MeasuredOperatingPointService.class));
        assertDoesNotThrow(safety::evaluate);
        verify(assignments).pause("gpu", "Temperaturstopp / Temperature stop: 84.0 °C >= 84.0 °C");
    }
}
