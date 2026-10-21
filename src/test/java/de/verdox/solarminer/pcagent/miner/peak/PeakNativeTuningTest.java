package de.verdox.solarminer.pcagent.miner.peak;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.engine.WorkerRunState;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore.Profile;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PeakNativeTuningTest {
    @TempDir Path directory;
    private final LocalGpuPowerService.Gpu gpu = new LocalGpuPowerService.Gpu("NVIDIA", 2,
            "GPU-peak-test", "TITAN RTX", 100, 350, 100, 350, 250, 200d, "measured", true, null);

    @Test void mapsAbsoluteClocksAndWattsToOnlyTheIsolatedDevice() {
        var command = command(profile(5000, null));
        assertOption(command, "--devices", "0");
        assertOption(command, "--gpu-lcore0", "1500");
        assertOption(command, "--gpu-lmem0", "5000");
        assertOption(command, "--gpu-power0", "180");
        assertFalse(command.contains("--gpu-mem0"));
        assertFalse(command.contains("--gpu-core0"));
        assertFalse(command.contains("--gpu-lcore2"));
        assertFalse(command.contains("--gpu-power"));
        assertOption(command, "--url", "stratum+tcp://127.0.0.1:3334");
    }

    @Test void negativeMemoryOffsetIsDistinctFromAnAbsoluteMemoryLock() {
        var command = command(profile(null, -250));
        assertOption(command, "--gpu-mem0", "-250");
        assertFalse(command.contains("--gpu-lmem0"));
        assertFalse(command.contains("--oc-profile"));
    }

    @Test void stoppedWorkerCachesAndClearsSettingsWithoutStartingMining() {
        var miner = miner();
        assertTrue(miner.applyNativeTuning(gpu, profile(5000, null)));
        assertOption(command(settings(miner).get(gpu.deviceId())), "--gpu-lmem0", "5000");
        assertTrue(miner.clearNativeTuning(gpu));
        assertTrue(settings(miner).isEmpty());
        verify(miner, never()).startGpu(anyString(), anyInt());
        verify(miner, never()).stopGpu(anyString(), anyInt());
    }

    @Test void changingCandidateRestartsOnlyTheRunningTargetAndClearPreservesPause() {
        var miner = miner();
        var target = live(miner, gpu);
        var sibling = live(miner, new LocalGpuPowerService.Gpu("NVIDIA", 1, "GPU-sibling", "TITAN RTX",
                100, 350, 100, 350, 250, 200d, "measured", true, null));
        doReturn(true).when(miner).stopGpu("NVIDIA", 2);
        doReturn(true).when(miner).startGpu("NVIDIA", 2);
        assertTrue(miner.applyNativeTuning(gpu, profile(5000, null)));
        var order = inOrder(miner);
        order.verify(miner).stopGpu("NVIDIA", 2);
        order.verify(miner).startGpu("NVIDIA", 2);
        assertTrue(sibling.running());
        verify(miner, never()).stopGpu("NVIDIA", 1);
        verify(miner, never()).startGpu("NVIDIA", 1);
        when(target.process.isAlive()).thenReturn(false);
        target.requestStop("paused");
        clearInvocations(miner);
        assertTrue(miner.clearNativeTuning(gpu));
        assertTrue(settings(miner).isEmpty());
        verify(miner, never()).startGpu(anyString(), anyInt());
    }

    @Test void stopFailureKeepsThePreviousSettingsAndNeverStartsAnotherProcess() {
        var miner = miner();
        var original = profile(5000, null);
        assertTrue(miner.applyNativeTuning(gpu, original));
        live(miner, gpu);
        doReturn(false).when(miner).stopGpu("NVIDIA", 2);
        assertFalse(miner.applyNativeTuning(gpu, profile(null, -250)));
        assertEquals(original, settings(miner).get(gpu.deviceId()));
        assertFalse(miner.clearNativeTuning(gpu));
        assertEquals(original, settings(miner).get(gpu.deviceId()));
        verify(miner, never()).startGpu(anyString(), anyInt());
    }

    @Test void failedRestartDiscardsCandidateAndInvalidProfilesCannotStopTheWorker() {
        var miner = miner();
        live(miner, gpu);
        assertFalse(miner.applyNativeTuning(gpu, profile(5000, -250)));
        verify(miner, never()).stopGpu(anyString(), anyInt());
        doReturn(true).when(miner).stopGpu("NVIDIA", 2);
        doReturn(false).when(miner).startGpu("NVIDIA", 2);
        assertFalse(miner.applyNativeTuning(gpu, profile(null, -250)));
        assertTrue(settings(miner).isEmpty());
    }

    private PeakMinerService miner() {
        return spy(new PeakMinerService(new ObjectMapper(), mock(ProxyConfigurationService.class),
                mock(PearlMinerService.class), mock(LocalGpuPowerService.class), mock(MinerConsoleService.class),
                mock(PayoutDefaultsService.class), new LocalRunLock(), mock(PeakDownloadService.class),
                directory.resolve("peak.json").toString()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Profile> settings(PeakMinerService miner) {
        return (Map<String, Profile>) ReflectionTestUtils.getField(miner, "nativeTuning");
    }

    @SuppressWarnings("unchecked")
    private WorkerRunState live(PeakMinerService miner, LocalGpuPowerService.Gpu card) {
        var state = new WorkerRunState("pearl", card, "test", "mining");
        state.process = mock(Process.class);
        when(state.process.isAlive()).thenReturn(true);
        ((Map<String, WorkerRunState>) ReflectionTestUtils.getField(miner, "runs"))
                .put(card.vendor() + ":" + card.index(), state);
        return state;
    }

    private Profile profile(Integer memory, Integer offset) {
        return new Profile(gpu.deviceId(), gpu.model(), gpu.vendor(), "pearl", "pearlhash", "peakminer", "2.19.1",
                1500, memory, 180, 100, 175, 32, 100d / 175, Instant.now(), offset, 0);
    }

    private List<String> command(Profile profile) {
        return PeakMinerService.buildCommand(Path.of("/opt/peakminer"), "stratum+tcp://127.0.0.1:3334",
                "stratum+ssl://prl.kryptex.network:8048", "prl1user", "rig-n2", 42111, profile);
    }

    private void assertOption(List<String> command, String option, String value) {
        assertTrue(command.contains(option), option);
        assertEquals(value, command.get(command.indexOf(option) + 1), option);
    }
}
