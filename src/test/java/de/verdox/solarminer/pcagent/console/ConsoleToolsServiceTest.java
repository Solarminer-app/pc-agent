package de.verdox.solarminer.pcagent.console;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleToolsServiceTest {
    @Test void benchmarkSelectionAndConcurrencyAreForwardedExactly() throws Exception {
        var f=new ConsoleToolsFixture();
        f.service().execute("benchmark_start",f.json.readTree("{\"mode\":\"INSTALLED\",\"workers\":[\"gpu-1\"],\"coins\":[\"pearl\"],\"concurrency\":2,\"startAfter\":false}"));
        verify(f.benchmarks).start("INSTALLED",List.of("pearl"),List.of("gpu-1"),false,2);
        assertThatThrownBy(()->f.service().execute("benchmark_start",f.json.readTree("{\"mode\":\"ALL\"}"))).hasMessage("tool_invalid_request");
        assertThatThrownBy(()->f.service().execute("benchmark_start",f.json.readTree("{\"mode\":\"LIVE\",\"workers\":[],\"coins\":[\"pearl\"],\"concurrency\":2}"))).hasMessage("tool_select_workers_coins");
    }
    @Test void decimalPowerValuesNeverReachDriverAndMeasurementLockBlocksWrites() throws Exception {
        var f=new ConsoleToolsFixture();
        var gpu=mock(LocalGpuPowerService.Gpu.class); when(gpu.deviceId()).thenReturn("gpu-1"); when(f.power.snapshot()).thenReturn(List.of(gpu));
        assertThatThrownBy(()->f.service().execute("power_limit",f.json.readTree("{\"device\":\"gpu-1\",\"watts\":123.5}"))).hasMessage("tool_invalid_request");
        verify(f.power,never()).updateUserPowerLimit(anyString(),anyInt());
        assertThat(f.lock.tryBegin("benchmark")).isTrue();
        assertThatThrownBy(()->f.service().execute("clock_restore",f.json.readTree("{\"device\":\"gpu-1\"}"))).hasMessage("power_mode_busy");
        verifyNoInteractions(f.tuning);
        f.lock.end();
    }
    @Test void runningModeChangesRequirePauseAndRetainExistingPreference() throws Exception {
        var f=new ConsoleToolsFixture(); when(f.workers.workers()).thenReturn(List.of(ConsoleWorkspaceServiceTest.worker(0,null)));
        assertThatThrownBy(()->f.service().execute("power_mode",f.json.readTree("{\"device\":\"GPU-0\",\"mode\":\"EFFICIENCY\"}"))).hasMessage("tool_pause_before_mode");
        verify(f.controls,never()).setWorkerPowerMode(anyString(),anyString());
    }
    @Test void invalidClockOrConsentRequestsCannotClearSettingsSilently() throws Exception {
        var f=new ConsoleToolsFixture();
        var gpu=mock(LocalGpuPowerService.Gpu.class); when(gpu.deviceId()).thenReturn("gpu-1"); when(f.power.snapshot()).thenReturn(List.of(gpu));
        assertThatThrownBy(()->f.service().execute("clock_apply",f.json.readTree("{\"device\":\"gpu-1\"}"))).hasMessage("tool_invalid_request");
        assertThatThrownBy(()->f.service().execute("benchmark_consent",f.json.readTree("{}"))).hasMessage("tool_invalid_request");
        verifyNoInteractions(f.tuning,f.sharing);
    }
    @Test void updateAdmissionFailureIsNotReportedAsSuccessfulUpdate() throws Exception {
        var f=new ConsoleToolsFixture(); when(f.updates.start()).thenReturn(false);
        assertThatThrownBy(()->f.service().execute("update_start",f.json.readTree("{}"))).hasMessage("update_unavailable");
        verify(f.updates,times(1)).start();
    }
    @Test void stoppedCpuTargetUsesThreadBudgetWithoutStartingMiner() throws Exception {
        var f=new ConsoleToolsFixture();
        var worker=mock(de.verdox.solarminer.pcagent.mining.WorkerAssignmentService.WorkerView.class);
        when(worker.deviceId()).thenReturn("cpu"); when(worker.hardwareType()).thenReturn("CPU");
        when(worker.coin()).thenReturn("monero");when(worker.status()).thenReturn(MinerStats.MinerStatus.STOPPED);when(worker.powerMode()).thenReturn("MANUAL");
        when(f.workers.workers()).thenReturn(List.of(worker));
        var cpu=mock(de.verdox.solarminer.pcagent.miner.CpuMiner.class);
        when(f.miners.cpuMiner("monero")).thenReturn(java.util.Optional.of(cpu));
        when(cpu.getMinimumControllablePowerWatts()).thenReturn(5L);when(cpu.getEstimatedMaxCpuWattage()).thenReturn(150L);when(cpu.setPowerCap(50)).thenReturn(true);
        f.service().execute("cpu_target",f.json.readTree("{\"device\":\"cpu\",\"watts\":50}"));
        verify(cpu).setPowerCap(50);verify(cpu,never()).startAll();
        assertThatThrownBy(()->f.service().execute("cpu_target",f.json.readTree("{\"device\":\"cpu\",\"watts\":200}"))).hasMessage("tool_cpu_target_outside_range");
        assertThat(f.lock.busy()).isFalse();
    }
    @Test void tuningReadsAreScopedToDeviceBeforeSerialization() throws Exception {
        var f=new ConsoleToolsFixture(); when(f.power.snapshot()).thenReturn(List.of()); when(f.sessions.sessions()).thenReturn(List.of());
        when(f.tuningProfiles.measurements()).thenReturn(List.of()); when(f.tuningProfiles.all()).thenReturn(List.of());
        var result=f.json.valueToTree(f.service().collect("tuning","nonexistent"));
        assertThat(result.path("measurements").size()).isZero(); assertThat(result.path("gpus").size()).isZero();
        verify(f.tuning,never()).readEffectiveClocks(anyString());
    }
}
