package de.verdox.solarminer.pcagent.miner;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.engine.WorkerRunState;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.miner.srb.*;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SrbNativeCleanupTest {
    @TempDir Path directory;
    private final LocalGpuPowerService.Gpu gpu = new LocalGpuPowerService.Gpu("NVIDIA", 2, "GPU-cleanup", "TITAN RTX", 100, 300, 100, 300, 200, 150d, "test", true, null);
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void removingSettingsCannotRestartAStoppedWorkerEvenWhenAFormerRunExists(boolean shared) {
        var f = fixture(shared, false);
        assertTrue(f.clear());
        f.settings.put(f.key, new SrbTuningProfile(1200, null, -500, 180));
        assertTrue(f.clear()); assertTrue(f.settings.isEmpty()); f.verifyStarts(0); f.verifyStops(0);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedStopKeepsNativeSettingsAndNeverStartsAnotherProcess(boolean shared) {
        var f = fixture(shared, true); f.stubLifecycle(false);
        var settings = new SrbTuningProfile(1200, null, -500, 180); f.settings.put(f.key, settings);
        assertFalse(f.clear()); assertEquals(settings, f.settings.get(f.key)); f.verifyStarts(0);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void clearingALiveProfileRestartsOnlyItsOwnWorker(boolean shared) {
        var f = fixture(shared, true); f.stubLifecycle(true);
        f.settings.put(f.key, new SrbTuningProfile(1200, null, -500, 180));
        assertTrue(f.clear()); assertTrue(f.settings.isEmpty()); f.verifyStarts(1); f.verifyStops(1);
        if (f.miner instanceof PearlMinerService p) verify(p, never()).startGpu("NVIDIA", 0);
        else verify((GpuCoinMinerService) f.miner, never()).startGpu(anyString(), eq("NVIDIA"), eq(0));
    }
    @SuppressWarnings("unchecked")
    Fixture fixture(boolean shared, boolean running) {
        Object miner = shared ? spy(new GpuCoinMinerService(new ObjectMapper(), mock(ProxyConfigurationService.class), mock(PearlMinerService.class),
                mock(LocalGpuPowerService.class), mock(MinerConsoleService.class), mock(PayoutDefaultsService.class), new LocalRunLock(), directory.toString()))
                : spy(new PearlMinerService(new ObjectMapper(), mock(ProxyConfigurationService.class), mock(LocalGpuPowerService.class),
                mock(MinerConsoleService.class), mock(PayoutDefaultsService.class), new LocalRunLock(), directory.resolve("miner").toString(), directory.resolve("config.json").toString()));
        var run = new WorkerRunState(shared ? "ethereumclassic" : "pearl", gpu, "test", "test");
        run.process = mock(Process.class); when(run.process.isAlive()).thenReturn(running);
        ((Map<String, WorkerRunState>) ReflectionTestUtils.getField(miner, "runs")).put(shared ? "ethereumclassic:NVIDIA:2" : "NVIDIA:2", run);
        return new Fixture(miner, (Map<String, SrbTuningProfile>) ReflectionTestUtils.getField(miner, "nativeTuning"), shared ? "ethereumclassic:" + gpu.deviceId() : gpu.deviceId());
    }
    record Fixture(Object miner, Map<String, SrbTuningProfile> settings, String key) {
        boolean clear() { return miner instanceof PearlMinerService p ? p.clearNativeTuning(card()) : ((GpuCoinMinerService) miner).clearNativeTuning("ethereumclassic", card()); }
        LocalGpuPowerService.Gpu card() { return new LocalGpuPowerService.Gpu("NVIDIA", 2, "GPU-cleanup", "TITAN RTX", 100, 300, 100, 300, 200, 150d, "test", true, null); }
        void stubLifecycle(boolean stop) {
            if (miner instanceof PearlMinerService p) { doReturn(stop).when(p).stopGpu("NVIDIA", 2); doReturn(true).when(p).startGpu("NVIDIA", 2); }
            else { var s = (GpuCoinMinerService) miner; doReturn(stop).when(s).stopGpu("ethereumclassic", "NVIDIA", 2); doReturn(true).when(s).startGpu("ethereumclassic", "NVIDIA", 2); }
        }
        void verifyStarts(int count) { if (miner instanceof PearlMinerService p) verify(p, times(count)).startGpu("NVIDIA", 2); else verify((GpuCoinMinerService) miner, times(count)).startGpu("ethereumclassic", "NVIDIA", 2); }
        void verifyStops(int count) { if (miner instanceof PearlMinerService p) verify(p, times(count)).stopGpu("NVIDIA", 2); else verify((GpuCoinMinerService) miner, times(count)).stopGpu("ethereumclassic", "NVIDIA", 2); }
    }
}
