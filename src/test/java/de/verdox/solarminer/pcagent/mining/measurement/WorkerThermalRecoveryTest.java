package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.economics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerThermalRecoveryTest {
    @TempDir Path directory;
    class Rig {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final WorkerBenchmarkPolicyService policies = new WorkerBenchmarkPolicyService(json, directory.resolve("policy.json").toString());
        final OperatingPointSafetyStore store = new OperatingPointSafetyStore(json, directory.resolve("safety.json").toString());
        final WorkerAssignmentService workers = mock(WorkerAssignmentService.class);
        final WorkerAssignmentService.WorkerView worker = mock(WorkerAssignmentService.WorkerView.class);
        final LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        final LocalGpuPowerService.Gpu gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "gpu", "RTX", 100, 220, 100, 220, 180, 178d, "sensor", true, null);
        final LocalRunLock lock = new LocalRunLock();
        final WorkerTemperatureSafetyService safety = new WorkerTemperatureSafetyService(policies, workers, power, lock);
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final java.util.function.BooleanSupplier permit = allowed::get;
        final AtomicLong clock = new AtomicLong();
        Rig() {
            policies.save("gpu", new WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 84));
            when(worker.deviceId()).thenReturn("gpu"); when(worker.hardwareType()).thenReturn("GPU");
            when(worker.coin()).thenReturn("pearl"); when(worker.minerSoftwareId()).thenReturn("peakminer");
            when(worker.status()).thenReturn(MinerStats.MinerStatus.MINING);
            when(workers.workers()).thenReturn(List.of(worker));
            when(power.snapshot()).thenReturn(List.of(gpu)); when(power.readTemperatureC(gpu)).thenReturn(84d);
            when(workers.pause(eq("gpu"), anyString())).thenAnswer(call -> {
                when(worker.status()).thenReturn(MinerStats.MinerStatus.PAUSED); return true;
            });
            when(workers.thermalRestartPermit("gpu")).thenReturn(permit);
            when(workers.restartAfterTemperatureStop("gpu", permit)).thenReturn(true);
            safety.setPointSafety(store, mock(MeasuredOperatingPointService.class));
            safety.setClock(clock::get);
        }
        void seconds(long seconds) { clock.addAndGet(java.time.Duration.ofSeconds(seconds).toNanos()); safety.evaluate(); }
        void cool() { when(power.readTemperatureC(gpu)).thenReturn(78d); safety.evaluate(); }
    }

    @Test void cooldownReselectsOnceOnlyAfterOneMinuteAndFiveDegreesHeadroom() {
        var r = new Rig(); r.safety.evaluate();
        assertFalse(r.store.permits("gpu", "pearl|peakminer|180|BASELINE", null, 84));
        r.seconds(120); // Time alone cannot restart a still hot GPU.
        verify(r.workers, never()).restartAfterTemperatureStop(anyString(), any());
        r.cool(); r.seconds(59);
        verify(r.workers, never()).restartAfterTemperatureStop(anyString(), any());
        r.seconds(1); r.seconds(60);
        verify(r.workers, times(1)).restartAfterTemperatureStop("gpu", r.permit);
    }

    @Test void unreadableSensorResetsCooldownAndDoesNotQuarantineAnUnprovenPoint() {
        var r = new Rig(); when(r.power.readTemperatureC(r.gpu)).thenReturn(null); r.safety.evaluate();
        assertTrue(r.store.permits("gpu", "pearl|peakminer|180|BASELINE", null, 84));
        r.cool(); r.seconds(50);
        when(r.power.readTemperatureC(r.gpu)).thenReturn(Double.NaN); r.seconds(10);
        r.cool(); r.seconds(59);
        verify(r.workers, never()).restartAfterTemperatureStop(anyString(), any());
        r.seconds(1);
        verify(r.workers).restartAfterTemperatureStop("gpu", r.permit);
    }

    @Test void laterStopRevokesPendingRestartEvenIfManualStopWasAlreadyCleared() {
        var r = new Rig(); r.safety.evaluate(); r.cool(); r.allowed.set(false); r.seconds(60);
        verify(r.workers, never()).restartAfterTemperatureStop(anyString(), any());
    }

    @Test void activeNodePlanTakesOwnershipOfThePausedWorker() {
        var r = new Rig(); r.safety.evaluate(); r.cool();
        var planning = mock(EconomicPlanningService.class);
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, java.time.Instant.now(), List.of(), java.time.Instant.now().plusSeconds(120)));
        r.safety.setPlanning(planning); r.seconds(60);
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, java.time.Instant.now(), List.of(), null));
        r.seconds(180);
        verify(r.workers, never()).restartAfterTemperatureStop(anyString(), any());
    }

    @Test void noEligibleTargetWaitsAndRetriesWithoutBypassingMeasuredSelection() {
        var r = new Rig();
        when(r.workers.restartAfterTemperatureStop("gpu", r.permit))
                .thenThrow(new IllegalStateException("power_mode_no_validated_point")).thenReturn(true);
        r.safety.evaluate(); r.cool(); r.seconds(60); r.seconds(59);
        verify(r.workers, times(1)).restartAfterTemperatureStop("gpu", r.permit);
        r.seconds(1);
        verify(r.workers, times(2)).restartAfterTemperatureStop("gpu", r.permit);
    }

    @Test void failedEmergencyStopAndMeasurementLockCannotScheduleRestart() {
        var r = new Rig();
        when(r.workers.pause(eq("gpu"), anyString())).thenReturn(false);
        r.safety.evaluate(); when(r.worker.status()).thenReturn(MinerStats.MinerStatus.PAUSED);
        r.cool(); r.seconds(120);
        verify(r.workers, never()).restartAfterTemperatureStop(anyString(), any());
        when(r.worker.status()).thenReturn(MinerStats.MinerStatus.MINING);
        when(r.power.readTemperatureC(r.gpu)).thenReturn(84d);
        assertTrue(r.lock.tryBegin("benchmark")); r.safety.evaluate(); r.lock.end();
        verify(r.workers, times(1)).pause(eq("gpu"), anyString());
    }

    @Test void raisedCeilingReleasesTargetWithoutLosingCooldownProtection() {
        var r = new Rig(); r.safety.evaluate();
        r.policies.save("gpu", new WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 86));
        assertTrue(r.store.permits("gpu", "pearl|peakminer|180|BASELINE", null, r.policies.ceiling("gpu", 90)));
        when(r.power.readTemperatureC(r.gpu)).thenReturn(81d); r.safety.evaluate(); r.seconds(60);
        verify(r.workers).restartAfterTemperatureStop("gpu", r.permit);
    }

    @Test void thermalStopQuarantinesTheActuallyAppliedAutoTuningClocks() {
        var r = new Rig();
        when(r.worker.powerMode()).thenReturn("AUTO_TUNING");
        var modes = mock(de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService.class);
        var profile = ThermalTestEvidence.profile(new AutoTuningProfileStore.Profile("gpu", "RTX", "NVIDIA", "pearl", "PearlHash",
                "peakminer", 1200, 6500, 180, 100, 178, 32, 100d/178, java.time.Instant.now().minusSeconds(60)));
        when(modes.appliedProfile("gpu")).thenReturn(profile); r.safety.setPowerModes(modes);
        r.safety.evaluate();
        assertFalse(r.store.permits("gpu", OperatingPointSafetyStore.profileKey(profile), profile.calibratedAt(), 84));
        assertTrue(r.store.permits("gpu", "pearl|peakminer|180|BASELINE", null, 84));
    }

    @Test void maximumHashrateStopAlsoUsesCooldownAtItsStricterCeiling() {
        var r = new Rig(); when(r.worker.powerMode()).thenReturn("MAX_HASHRATE");
        r.safety.stopForTemperature("gpu", 80d, 80);
        when(r.power.readTemperatureC(r.gpu)).thenReturn(76d); r.seconds(120);
        verify(r.workers, never()).restartAfterTemperatureStop(anyString(), any());
        when(r.power.readTemperatureC(r.gpu)).thenReturn(74d); r.safety.evaluate(); r.seconds(60);
        verify(r.workers).restartAfterTemperatureStop("gpu", r.permit);
    }

    @Test void missingWorkerTelemetryStillStopsWithoutBlindRestart() {
        var r = new Rig();
        when(r.workers.workers()).thenThrow(new IllegalStateException("telemetry unavailable"));
        assertDoesNotThrow(() -> r.safety.stopForTemperature("gpu", null, 80));
        verify(r.workers).pause("gpu", "Temperaturstopp: GPU-Daten nicht lesbar / Temperature stop: GPU data unavailable");
        verify(r.workers, never()).thermalRestartPermit(anyString());
    }
}
