package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.mining.MiningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoTuningSessionServiceTest {
    @TempDir Path directory;

    @Test
    void powerTiersIncludeManualStartAndBothSafeBounds() {
        assertEquals(List.of(80, 150, 200), AutoTuningSessionService.powerTiers(80, 150, 200));
        assertEquals(List.of(80, 140, 200), AutoTuningSessionService.powerTiers(80, 80, 200));
        assertEquals(List.of(80, 140, 200), AutoTuningSessionService.powerTiers(80, 200, 200));
    }

    @Test
    void buildsCoreOnlyCandidatesFromHighestSupportedMemoryClock() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-n", "Test", 80, 200,
                80, 200, 150, 120.0, "measured", true, null, 200);
        var caps = new GpuTuningService.Capabilities("GPU-n", true, false, false,
                900, 1400, null, null, null,
                List.of(new GpuTuningService.ClockPair(4000, 900),
                        new GpuTuningService.ClockPair(7000, 1100),
                        new GpuTuningService.ClockPair(7000, 1400)));

        var candidates = AutoTuningSessionService.candidates(gpu, caps, null, 150);

        assertEquals(6, candidates.size());
        assertEquals(3, candidates.stream().map(BayesianGpuOptimizer.Candidate::powerWatts).distinct().count());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.memoryMhz() == null));
    }

    @Test
    void calibratesThreeShareConfirmedTiersAndRestoresPreRunPower() throws Exception {
        MiningService mining = mock(MiningService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        GpuTuningService tuning = mock(GpuTuningService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        MinerFactory miners = mock(MinerFactory.class);
        var gpu = new LocalGpuPowerService.Gpu("AMD", 0, "GPU-a", "Test", 80, 200,
                80, 200, 150, 120.0, "measured", true, null, 200);
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.snapshot()).thenReturn(List.of(gpu));
        when(power.readTemperatureC(gpu)).thenReturn(60.0);
        when(power.setTotalPowerTarget(anyLong(), anyList())).thenReturn(true);
        when(power.configuredPowerLimitWatts(gpu)).thenReturn(150);
        when(power.setAutoTuningPowerTarget(anyInt(), eq(gpu))).thenReturn(true);
        when(tuning.refreshCapabilities("GPU-a")).thenReturn(new GpuTuningService.Capabilities(
                "GPU-a", true, true, false, 1000, 1400, 400, 800, null));
        when(tuning.apply(anyString(), any(), any(), any(), any()))
                .thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(tuning.restoreDefaults("GPU-a")).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(lock.tryBegin("auto-tuning")).thenReturn(true);
        when(miners.minerSoftwareForWorker(any(), eq("GPU-a"))).thenReturn("srbminer-multi");
        when(miners.minerVersionForWorker(any(), eq("GPU-a"))).thenReturn("3.7.3");
        AtomicLong accepted = new AtomicLong();
        when(mining.getWorkerStats()).thenAnswer(invocation -> List.of(new MinerStats.Worker(
                MinerStats.MinerStatus.MINING, "Test", "PearlHash", 100d / 1e12, 60,
                150, 80, 150, 200, 120, List.of(), "GPU", "Test", "GPU-a",
                accepted.incrementAndGet(), 0L, MinerStats.PoolTelemetry.unavailable())));
        AutoTuningProfileStore store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("profiles.json").toString());
        AutoTuningSessionService service = new AutoTuningSessionService(mining, power, tuning, store, lock,
                miners, 85, 0);
        try {
            assertTrue(service.start("GPU-a", "pearl").running());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (service.status().running() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("auto_tuning_complete", service.status().phase());
            assertEquals(3, store.matrix("GPU-a", "pearl", "srbminer-multi", "3.7.3", "Test", "AMD").size());
            verify(tuning).restoreDefaults("GPU-a");
            verify(power, atLeastOnce()).setAutoTuningPowerTarget(150, gpu);
        } finally { service.shutdown(); }
    }
}
