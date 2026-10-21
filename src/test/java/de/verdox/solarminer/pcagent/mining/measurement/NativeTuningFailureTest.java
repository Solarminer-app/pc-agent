package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.*;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.mining.MiningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs the calibration coordinator with a native Peak-like adapter; no miner/GPU I/O. */
class NativeTuningFailureTest {
    @TempDir Path directory;
    @Test void nativeRestartFailureRetainsDeviceDiagnosisAndDoesNotCacheAnotherCandidateOnAStoppedWorker() throws Exception {
        run(false);
    }
    @Test void failedRecoveryPersistencePreventsAnyNativeClockWrite() throws Exception {
        run(true);
    }
    void run(boolean markerFails) throws Exception {
        var card = new LocalGpuPowerService.Gpu("NVIDIA", 1, "GPU-native", "TITAN RTX", 100, 250, 100, 250, 180, 170d, "test", true, null, 250);
        var power = mock(LocalGpuPowerService.class); var clocks = mock(GpuTuningService.class);
        var mining = mock(MiningService.class); var miners = mock(MinerFactory.class);
        var miner = mock(GpuCoinMiner.class, withSettings().extraInterfaces(MinerNativeTuning.class));
        when(power.refresh()).thenReturn(List.of(card)); when(power.snapshot()).thenReturn(List.of(card));
        when(power.readTemperatureC(card)).thenReturn(60d); when(power.configuredPowerLimitWatts(card)).thenReturn(180);
        when(power.setAutoTuningPowerTarget(anyInt(), eq(card))).thenReturn(true);
        when(clocks.refreshCapabilities(card.deviceId())).thenReturn(new GpuTuningService.Capabilities(card.deviceId(), true, false, false, 900, 1500, null, null, null));
        when(clocks.readEffectiveClocks(card.deviceId())).thenReturn(new GpuTuningService.ClockTelemetry(1200, 6500));
        when(clocks.prepareNativeTuning(eq(card.deviceId()), any(), any(), any())).thenReturn(new GpuTuningService.ApplyResult(!markerFails,
                markerFails ? "gpu_tuning_recovery_save_failed" : null));
        when(clocks.restoreDefaults(card.deviceId())).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(miners.minerSoftwareForWorker(any(), eq(card.deviceId()))).thenReturn("peakminer");
        when(miners.gpuMinerForWorker("pearl", card.deviceId())).thenReturn(Optional.of(miner));
        when(miner.lastError()).thenReturn("Different GPU's unrelated error");
        when(miner.gpuStates(anyList())).thenReturn(List.of(new GpuState("NVIDIA", 1, "TITAN RTX", true,
                MinerStats.MinerStatus.ERROR, false, false, false, null, "Another miner already controls NVIDIA:1")));
        when(((MinerNativeTuning) miner).applyNativeTuning(eq(card), any())).thenReturn(false);
        when(mining.getWorkerStats()).thenReturn(List.of(new MinerStats.Worker(MinerStats.MinerStatus.MINING,
                "TITAN RTX", "PearlHash", 1e-9, 60, 180, 100, 180, 250, 170, List.of(), "GPU", "TITAN RTX", card.deviceId(),
                null, null, MinerStats.PoolTelemetry.unavailable())));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new AutoTuningProfileStore(mapper, directory.resolve("native-profiles.json").toString());
        var selections = new AutoTuningSelectionStore(); selections.set(card.deviceId(), new AutoTuningSelectionStore.Selection(true, false, true));
        var service = new AutoTuningSessionService(mining, power, clocks, store, selections, new LocalRunLock(), miners, 85, 0);
        try {
            service.start(card.deviceId(), "pearl");
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> { while (service.status().running()) Thread.sleep(5); });
            assertEquals(markerFails ? "gpu_tuning_recovery_save_failed" : "Another miner already controls NVIDIA:1", service.status().phase());
            verify((MinerNativeTuning) miner, times(markerFails ? 0 : 1)).applyNativeTuning(eq(card), any());
            assertTrue(store.all().isEmpty());
            if (!markerFails) {
                assertEquals("Another miner already controls NVIDIA:1", store.measurements().getFirst().reason());
                var order = inOrder(clocks, miner);
                order.verify(clocks).prepareNativeTuning(eq(card.deviceId()), any(), any(), any());
                order.verify((MinerNativeTuning) miner).applyNativeTuning(eq(card), any());
            }
        } finally { service.shutdown(); }
    }
}
