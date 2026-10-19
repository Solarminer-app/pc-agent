package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.mining.MiningService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
    void writableSupportedClocksAboveTheCurrentlyObservedClocksRemainCandidates() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 1, "GPU-2080-1", "NVIDIA GeForce RTX 2080 Ti",
                100, 300, 100, 300, 260, 250.0, "measured", true, null, 300);
        var caps = new GpuTuningService.Capabilities("GPU-2080-1", true, true, false,
                300, 1600, 405, 7000, null,
                List.of(new GpuTuningService.ClockPair(405, 300),
                        new GpuTuningService.ClockPair(6800, 1400),
                        new GpuTuningService.ClockPair(7000, 1600)));

        var candidates = AutoTuningSessionService.candidates(gpu, caps,
                new GpuTuningService.ClockTelemetry(1400, 6800), 260);

        assertEquals(9, candidates.size());
        assertEquals(3, candidates.stream().map(BayesianGpuOptimizer.Candidate::memoryMhz).distinct().count());
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.coreMhz() == 1600
                && candidate.memoryMhz() == 7000 && candidate.powerWatts() == 260));
    }

    @Test
    void rtxOffsetCandidatesKeepRequestedOffsetSeparateFromObservedMemoryClock() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 1, "GPU-2080-1", "NVIDIA GeForce RTX 2080 Ti",
                100, 300, 100, 300, 260, 250.0, "measured", true, null, 300);
        var caps = new GpuTuningService.Capabilities("GPU-2080-1", true, true, false,
                300, 1600, 405, 7000, null,
                List.of(new GpuTuningService.ClockPair(6800, 1400),
                        new GpuTuningService.ClockPair(6800, 1600),
                        new GpuTuningService.ClockPair(405, 300)));

        var rows = AutoTuningSessionService.candidates(gpu, caps,
                new GpuTuningService.ClockTelemetry(1400, 6800), 260,
                new GpuTuningService.MemoryOffsetRange(2, -250, 150, 0));

        assertEquals(36, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.memoryMhz() == null && row.memoryOffsetPstate() == 2));
        assertEquals(java.util.Set.of(-250, -150, -50, 0, 50, 150), rows.stream()
                .map(BayesianGpuOptimizer.Candidate::memoryOffsetMhz).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void calibratesThreeTiersWithoutShareCountersDespiteTwoHashrateOutliersAndRestoresPower() throws Exception {
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
        AtomicLong samples = new AtomicLong();
        when(mining.getWorkerStats()).thenAnswer(invocation -> {
            long sample = samples.getAndIncrement();
            double hashrate = sample % 16 < 2 ? 150d : 100d;
            return List.of(new MinerStats.Worker(
                    MinerStats.MinerStatus.MINING, "Test", "PearlHash", hashrate / 1e12, 60,
                    150, 80, 150, 200, 120, List.of(), "GPU", "Test", "GPU-a",
                    null, null, MinerStats.PoolTelemetry.unavailable()));
        });
        AutoTuningProfileStore store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("profiles.json").toString());
        assertTrue(store.appendMeasurement(new AutoTuningProfileStore.Measurement("earlier-run",
                java.time.Instant.now(), "GPU-a", "Test", "AMD", "pearl", "PearlHash", "srbminer-multi",
                "3.7.3", 1000, 400, 150, "RESULT", 100d, 120d, 60d,
                null, null, 16, null, null, true, null)));
        AutoTuningSessionService service = new AutoTuningSessionService(mining, power, tuning, store, lock,
                miners, 85, 0);
        try {
            assertTrue(service.start("GPU-a", "pearl").running());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (service.status().running() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("auto_tuning_complete", service.status().phase());
            assertEquals(3, store.matrix("GPU-a", "pearl", "srbminer-multi", "3.7.3", "Test", "AMD").size());
            assertEquals(12, store.measurements().stream().filter(row -> "RESULT".equals(row.type())
                    && !"earlier-run".equals(row.runId())).count());
            assertEquals(192, store.measurements().stream().filter(row -> "SAMPLE".equals(row.type())).count());
            ArgumentCaptor<Integer> firstCore = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> firstMemory = ArgumentCaptor.forClass(Integer.class);
            verify(tuning, times(12)).apply(eq("GPU-a"), firstCore.capture(), any(), firstMemory.capture(), any());
            assertEquals(1400, firstCore.getAllValues().getFirst());
            assertEquals(400, firstMemory.getAllValues().getFirst());
            verify(tuning).restoreDefaults("GPU-a");
            verify(power, atLeastOnce()).setAutoTuningPowerTarget(150, gpu);
        } finally { service.shutdown(); }
    }

    @Test
    void rejectsClockAboveTargetButAcceptsPowerLimitedCoreBelowTarget() throws Exception {
        MiningService mining = mock(MiningService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        GpuTuningService tuning = mock(GpuTuningService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        MinerFactory miners = mock(MinerFactory.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-n", "Test", 80, 200,
                80, 200, 150, 120.0, "measured", true, null, 200);
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.snapshot()).thenReturn(List.of(gpu));
        when(power.readTemperatureC(gpu)).thenReturn(60.0);
        when(power.configuredPowerLimitWatts(gpu)).thenReturn(150);
        when(power.setAutoTuningPowerTarget(anyInt(), eq(gpu))).thenReturn(true);
        when(tuning.refreshCapabilities("GPU-n")).thenReturn(new GpuTuningService.Capabilities(
                "GPU-n", true, true, false, 500, 1000, 5000, 5000, null,
                List.of(new GpuTuningService.ClockPair(5000, 500),
                        new GpuTuningService.ClockPair(5000, 1000))));
        when(tuning.readEffectiveClocks("GPU-n")).thenReturn(new GpuTuningService.ClockTelemetry(800, 5000));
        when(tuning.apply(anyString(), any(), any(), any(), any()))
                .thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(tuning.restoreDefaults("GPU-n")).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(lock.tryBegin("auto-tuning")).thenReturn(true);
        when(miners.minerSoftwareForWorker(any(), eq("GPU-n"))).thenReturn("srbminer-multi");
        when(miners.minerVersionForWorker(any(), eq("GPU-n"))).thenReturn("3.7.3");
        when(mining.getWorkerStats()).thenReturn(List.of(new MinerStats.Worker(
                MinerStats.MinerStatus.MINING, "Test", "PearlHash", 100d / 1e12, 60,
                150, 80, 150, 200, 120, List.of(), "GPU", "Test", "GPU-n",
                null, null, MinerStats.PoolTelemetry.unavailable())));
        AutoTuningProfileStore store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("nvidia-profiles.json").toString());
        AutoTuningSessionService service = new AutoTuningSessionService(mining, power, tuning, store, lock,
                miners, 85, 0);
        try {
            assertTrue(service.start("GPU-n", "pearl").running());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (service.status().running() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("auto_tuning_complete", service.status().phase());
            var mismatch = store.measurements().stream()
                    .filter(row -> "RESULT".equals(row.type()) && "auto_tuning_clock_not_reached".equals(row.reason()))
                    .findFirst().orElseThrow();
            assertEquals(16, mismatch.validSamples());
            assertEquals(500, mismatch.coreMhz());
            assertEquals(800, mismatch.observedCoreMhz());
            assertEquals(100d, mismatch.hashrateHs());
            assertEquals(120d, mismatch.measuredWatts());
            assertFalse(mismatch.stable());
            assertTrue(service.status().observations().stream().anyMatch(row ->
                    "auto_tuning_clock_not_reached".equals(row.detail()) && row.validSamples() == 16
                            && row.observedCoreMhz() == 800));
            assertTrue(store.measurements().stream().anyMatch(row -> "RESULT".equals(row.type())
                    && row.coreMhz() == 1000 && row.observedCoreMhz() == 800
                    && Boolean.TRUE.equals(row.stable())));
        } finally { service.shutdown(); }
    }

    @Test
    void rtx2080WithoutVerifiedOffsetAvoidsAbsoluteMemoryLock() throws Exception {
        MiningService mining = mock(MiningService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        GpuTuningService tuning = mock(GpuTuningService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        MinerFactory miners = mock(MinerFactory.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 1, "GPU-2080", "NVIDIA GeForce RTX 2080 Ti",
                100, 260, 100, 260, 180, 120.0, "measured", true, null, 260);
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.snapshot()).thenReturn(List.of(gpu));
        when(power.readTemperatureC(gpu)).thenReturn(60.0);
        when(power.configuredPowerLimitWatts(gpu)).thenReturn(180);
        when(power.setAutoTuningPowerTarget(anyInt(), eq(gpu))).thenReturn(true);
        when(tuning.refreshCapabilities("GPU-2080")).thenReturn(new GpuTuningService.Capabilities(
                "GPU-2080", true, true, false, 300, 1710, 405, 6800, null,
                List.of(new GpuTuningService.ClockPair(405, 300),
                        new GpuTuningService.ClockPair(6800, 360),
                        new GpuTuningService.ClockPair(6800, 1710))));
        AtomicInteger core = new AtomicInteger(1710);
        when(tuning.readEffectiveClocks("GPU-2080")).thenAnswer(invocation ->
                new GpuTuningService.ClockTelemetry(core.get(), 6800));
        when(tuning.apply(anyString(), any(), any(), any(), any())).thenAnswer(invocation -> {
            core.set(invocation.getArgument(1));
            return new GpuTuningService.ApplyResult(true, null);
        });
        when(tuning.restoreDefaults("GPU-2080")).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(lock.tryBegin("auto-tuning")).thenReturn(true);
        when(miners.minerSoftwareForWorker(any(), eq("GPU-2080"))).thenReturn("srbminer-multi");
        when(miners.minerVersionForWorker(any(), eq("GPU-2080"))).thenReturn("3.7.3");
        when(mining.getWorkerStats()).thenReturn(List.of(new MinerStats.Worker(
                MinerStats.MinerStatus.MINING, "Test", "PearlHash", 100d / 1e12, 60,
                180, 100, 180, 260, 120, List.of(), "GPU", "Test", "GPU-2080",
                null, null, MinerStats.PoolTelemetry.unavailable())));
        AutoTuningProfileStore store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("memory-fallback-profiles.json").toString());
        AutoTuningSessionService service = new AutoTuningSessionService(mining, power, tuning, store, lock,
                miners, 85, 0);
        try {
            assertTrue(service.start("GPU-2080", "pearl").running());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (service.status().running() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("auto_tuning_complete", service.status().phase());
            assertTrue(store.measurements().stream().noneMatch(row -> row.memoryMhz() != null));
            assertEquals(3, store.matrix("GPU-2080", "pearl", "srbminer-multi", "3.7.3",
                    "NVIDIA GeForce RTX 2080 Ti", "NVIDIA").size());
            assertTrue(store.measurements().stream().filter(row -> "RESULT".equals(row.type())
                    && Boolean.TRUE.equals(row.stable())).allMatch(row -> row.memoryMhz() == null));
        } finally { service.shutdown(); }
    }

    @Test
    void offsetReadbackWithoutEffectiveMemoryChangeFallsBackToCoreOnly() throws Exception {
        MiningService mining = mock(MiningService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        GpuTuningService tuning = mock(GpuTuningService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        MinerFactory miners = mock(MinerFactory.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 1, "GPU-offset", "NVIDIA GeForce RTX 2080 Ti",
                100, 260, 100, 260, 180, 120.0, "measured", true, null, 260);
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.snapshot()).thenReturn(List.of(gpu));
        when(power.readTemperatureC(gpu)).thenReturn(60.0);
        when(power.configuredPowerLimitWatts(gpu)).thenReturn(180);
        when(power.setAutoTuningPowerTarget(anyInt(), eq(gpu))).thenReturn(true);
        when(tuning.refreshCapabilities("GPU-offset")).thenReturn(new GpuTuningService.Capabilities(
                "GPU-offset", true, false, false, 1200, 1710, 6800, 6800, null,
                List.of(new GpuTuningService.ClockPair(6800, 1200),
                        new GpuTuningService.ClockPair(6800, 1710))));
        when(tuning.memoryOffsetRange("GPU-offset")).thenReturn(
                new GpuTuningService.MemoryOffsetRange(2, -200, 100, 0));
        AtomicInteger core = new AtomicInteger(1710);
        AtomicInteger offset = new AtomicInteger();
        when(tuning.apply(anyString(), any(), any(), isNull(), isNull(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    core.set(invocation.getArgument(1));
                    offset.set(invocation.getArgument(5));
                    return new GpuTuningService.ApplyResult(true, null);
                });
        when(tuning.apply(anyString(), any(), any(), isNull(), isNull())).thenAnswer(invocation -> {
            core.set(invocation.getArgument(1));
            return new GpuTuningService.ApplyResult(true, null);
        });
        when(tuning.readMemoryOffset("GPU-offset", 2)).thenAnswer(invocation -> offset.get());
        when(tuning.readEffectiveClocks("GPU-offset")).thenAnswer(invocation ->
                new GpuTuningService.ClockTelemetry(core.get(), 6800));
        when(tuning.restoreDefaults("GPU-offset")).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(lock.tryBegin("auto-tuning")).thenReturn(true);
        when(miners.minerSoftwareForWorker(any(), eq("GPU-offset"))).thenReturn("srbminer-multi");
        when(miners.minerVersionForWorker(any(), eq("GPU-offset"))).thenReturn("3.7.3");
        when(mining.getWorkerStats()).thenReturn(List.of(new MinerStats.Worker(
                MinerStats.MinerStatus.MINING, "Test", "PearlHash", 100d / 1e12, 60,
                180, 100, 180, 260, 120, List.of(), "GPU", "Test", "GPU-offset",
                null, null, MinerStats.PoolTelemetry.unavailable())));
        AutoTuningProfileStore store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("offset-ineffective-profiles.json").toString());
        AutoTuningSessionService service = new AutoTuningSessionService(mining, power, tuning, store, lock,
                miners, 85, 0);
        try {
            assertTrue(service.start("GPU-offset", "pearl").running());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (service.status().running() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("auto_tuning_complete", service.status().phase());
            var failure = store.measurements().stream().filter(row -> "RESULT".equals(row.type())
                    && "auto_tuning_memory_lock_ineffective".equals(row.reason())).findFirst().orElseThrow();
            assertEquals(-200, failure.memoryOffsetMhz());
            assertEquals(6800, failure.observedMemoryMhz());
            assertEquals(3, failure.validSamples());
            assertTrue(store.measurements().stream().filter(row -> "RESULT".equals(row.type())
                    && Boolean.TRUE.equals(row.stable())).allMatch(row -> row.memoryOffsetMhz() == null));
        } finally { service.shutdown(); }
    }
}
