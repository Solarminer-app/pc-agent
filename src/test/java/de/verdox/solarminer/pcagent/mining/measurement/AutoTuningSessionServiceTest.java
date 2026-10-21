package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
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
    void perGpuThermalLimitStopsTunerEvenWhenHashrateAndPowerAreInvalid() throws Exception {
        var mining = mock(MiningService.class);
        var power = mock(LocalGpuPowerService.class);
        var tuning = mock(GpuTuningService.class);
        var miners = mock(MinerFactory.class);
        var miner = mock(GpuCoinMiner.class);
        var gpu = new LocalGpuPowerService.Gpu("AMD", 0, "GPU-hot", "Test", 80, 200,
                80, 200, 150, 120.0, "measured", true, null, 200);
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.snapshot()).thenReturn(List.of(gpu));
        when(power.readTemperatureC(gpu)).thenReturn(60d, 72d);
        when(power.configuredPowerLimitWatts(gpu)).thenReturn(150);
        when(power.setAutoTuningPowerTarget(anyInt(), eq(gpu))).thenReturn(true);
        when(tuning.refreshCapabilities(gpu.deviceId())).thenReturn(new GpuTuningService.Capabilities(
                gpu.deviceId(), true, true, false, 1000, 1400, 400, 800, null));
        when(tuning.apply(anyString(), any(), any(), any(), any())).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(tuning.restoreDefaults(gpu.deviceId())).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(miners.minerSoftwareForWorker(any(), eq(gpu.deviceId()))).thenReturn("srbminer-multi");
        when(miners.gpuMinerForWorker(anyString(), eq(gpu.deviceId()))).thenReturn(java.util.Optional.of(miner));
        when(mining.getWorkerStats()).thenReturn(List.of(new MinerStats.Worker(MinerStats.MinerStatus.MINING,
                "Test", "PearlHash", 0, 72, 150, 80, 150, 200, 0, List.of(), "GPU", "Test", gpu.deviceId(),
                null, null, MinerStats.PoolTelemetry.unavailable())));
        var policy = new WorkerBenchmarkPolicyService(new ObjectMapper(), directory.resolve("thermal-policy.json").toString());
        policy.save(gpu.deviceId(), new WorkerBenchmarkPolicyService.Policy(List.of(
                new WorkerBenchmarkPolicyService.Pair("pearl", "srbminer-multi")), false, false, 72));
        var store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(), directory.resolve("thermal-profiles.json").toString());
        var lock = new LocalRunLock();
        var service = new AutoTuningSessionService(mining, power, tuning, store, lock, miners, 85, 0);
        service.setWorkerPolicies(policy);
        try {
            service.start(gpu.deviceId(), "pearl");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (service.status().running() && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals("auto_tuning_temperature_limit", service.status().phase());
            assertFalse(lock.busy());
            assertTrue(store.all().isEmpty());
            verify(miner).pauseGpu("AMD", 0);
            verify(tuning, never()).resetUnresponsiveGpu(anyString());
        } finally { service.shutdown(); }
    }

    @Test
    void powerTiersIncludeManualStartAndBothSafeBounds() {
        assertEquals(List.of(80, 150, 200), AutoTuningSessionService.powerTiers(80, 150, 200));
        assertEquals(List.of(80, 140, 200), AutoTuningSessionService.powerTiers(80, 80, 200));
        assertEquals(List.of(80, 140, 200), AutoTuningSessionService.powerTiers(80, 200, 200));
    }

    @Test
    void extendsOnlyAProvenEfficientPowerTier() {
        var efficient = new BayesianGpuOptimizer.Candidate(840, 810, 100, .3, .1, 0);
        var nearby = new BayesianGpuOptimizer.Candidate(1005, 810, 100, .4, .1, 0);
        var poor = new BayesianGpuOptimizer.Candidate(2100, 7000, 100, 1, 1, 0);
        var otherTier = new BayesianGpuOptimizer.Candidate(840, 810, 190, .3, .1, 1);

        var best = AutoTuningSessionService.promisingEfficientRegion(List.of(
                new BayesianGpuOptimizer.Observation(efficient, .36, true, null),
                new BayesianGpuOptimizer.Observation(nearby, .31, true, null),
                new BayesianGpuOptimizer.Observation(poor, .13, true, null),
                new BayesianGpuOptimizer.Observation(otherTier, .40, true, null)));

        assertEquals(efficient, best.candidate());
        assertNull(AutoTuningSessionService.promisingEfficientRegion(List.of(
                new BayesianGpuOptimizer.Observation(efficient, .36, true, null),
                new BayesianGpuOptimizer.Observation(nearby, .35, true, null),
                new BayesianGpuOptimizer.Observation(poor, .34, true, null))));
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
    void rtxCandidatesCoverBothMemoryOffsetsAndFixedMemoryClocks() {
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

        assertEquals(117, rows.size());
        assertTrue(rows.stream().anyMatch(row -> row.memoryMhz() == 405 && row.memoryOffsetMhz() == null));
        assertTrue(rows.stream().anyMatch(row -> row.memoryMhz() == 6800 && row.memoryOffsetMhz() == null));
        assertTrue(rows.stream().filter(row -> row.memoryOffsetMhz() != null)
                .allMatch(row -> row.memoryMhz() == null && row.memoryOffsetPstate() == 2));
        var offsets = rows.stream().filter(row -> row.memoryOffsetMhz() != null)
                .map(BayesianGpuOptimizer.Candidate::memoryOffsetMhz).distinct().sorted().toList();
        assertEquals(18, offsets.size());
        assertEquals(-250, offsets.getFirst());
        assertEquals(100, offsets.getLast());
        assertTrue(offsets.contains(0));
    }

    @Test
    void wideDriverOffsetRangeDoesNotScheduleUnverifiedExtreme() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 4, "GPU-titan", "NVIDIA TITAN RTX",
                100, 280, 100, 280, 280, 200.0, "measured", true, null, 280);
        var caps = new GpuTuningService.Capabilities("GPU-titan", true, false, false,
                300, 2100, 405, 7001, null,
                List.of(new GpuTuningService.ClockPair(6500, 300)));

        var rows = AutoTuningSessionService.candidates(gpu, caps,
                new GpuTuningService.ClockTelemetry(300, 6500), 280,
                new GpuTuningService.MemoryOffsetRange(2, -2000, 6000, 0));

        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(row -> row.memoryOffsetMhz() >= -1500
                && row.memoryOffsetMhz() <= 100));
        assertTrue(rows.stream().anyMatch(row -> row.memoryOffsetMhz() == -1500));
        assertFalse(rows.stream().anyMatch(row -> row.memoryOffsetMhz() == 6000));
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
when(lock.tryBeginAutoTuning()).thenReturn(true);
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
            assertEquals(6, store.measurements().stream().filter(row -> "RESULT".equals(row.type())
                    && !"earlier-run".equals(row.runId())).count());
            assertEquals(18, store.measurements().stream().filter(row -> "SEARCH_RESULT".equals(row.type())).count());
            assertEquals(480, store.measurements().stream().filter(row -> "SAMPLE".equals(row.type())).count());
            assertTrue(store.measurements().stream().filter(row -> "RESULT".equals(row.type())
                    && !"earlier-run".equals(row.runId())).allMatch(row -> row.validSamples() == 32));
            ArgumentCaptor<Integer> firstCore = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> firstMemory = ArgumentCaptor.forClass(Integer.class);
            verify(tuning, times(24)).apply(eq("GPU-a"), firstCore.capture(), any(), firstMemory.capture(), any());
            assertEquals(1150, firstCore.getAllValues().getFirst());
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
        when(lock.tryBeginAutoTuning()).thenReturn(true);
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
                    .filter(row -> "SEARCH_RESULT".equals(row.type()) && "auto_tuning_clock_not_reached".equals(row.reason()))
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
        when(lock.tryBeginAutoTuning()).thenReturn(true);
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
    void minerNativeFixedClockProbeIncludesListedMemoryAnchorsWithoutDriverLock() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 1, "GPU-2080-1", "NVIDIA GeForce RTX 2080 Ti",
                100, 300, 100, 300, 260, 250.0, "measured", true, null, 300);
        var caps = new GpuTuningService.Capabilities("GPU-2080-1", true, false, false,
                300, 1600, 405, 7000, null,
                List.of(new GpuTuningService.ClockPair(405, 300),
                        new GpuTuningService.ClockPair(6800, 1400)));

        var rows = AutoTuningSessionService.candidates(gpu, caps,
                new GpuTuningService.ClockTelemetry(1400, 6800), 260, null,
                AutoTuningSelectionStore.Selection.DEFAULT, true);

        assertTrue(rows.stream().anyMatch(row -> row.memoryMhz() == 405));
        assertTrue(rows.stream().anyMatch(row -> row.memoryMhz() == 6800));
        assertTrue(rows.stream().anyMatch(row -> row.coreMhz() == 1400 && row.memoryMhz() == 405));
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
        when(lock.tryBeginAutoTuning()).thenReturn(true);
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
            var failure = store.measurements().stream().filter(row -> "SEARCH_RESULT".equals(row.type())
                    && "auto_tuning_memory_lock_ineffective".equals(row.reason())).findFirst().orElseThrow();
            assertEquals(-200, failure.memoryOffsetMhz());
            assertEquals(6800, failure.observedMemoryMhz());
            assertEquals(3, failure.validSamples());
            assertTrue(store.measurements().stream().filter(row -> "RESULT".equals(row.type())
                    && Boolean.TRUE.equals(row.stable())).allMatch(row -> row.memoryOffsetMhz() == null));
        } finally { service.shutdown(); }
    }

    @Test
    void failedOffsetWritePausesWorkerAndAttemptsGpuResetBeforeRestore() throws Exception {
        MiningService mining = mock(MiningService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        GpuTuningService tuning = mock(GpuTuningService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        MinerFactory miners = mock(MinerFactory.class);
        GpuCoinMiner miner = mock(GpuCoinMiner.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 4, "GPU-titan", "NVIDIA TITAN RTX",
                100, 280, 100, 280, 280, 200.0, "measured", true, null, 280);
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.snapshot()).thenReturn(List.of(gpu));
        when(power.readTemperatureC(gpu)).thenReturn(60.0);
        when(power.configuredPowerLimitWatts(gpu)).thenReturn(280);
        when(power.setAutoTuningPowerTarget(anyInt(), eq(gpu))).thenReturn(true);
        when(tuning.refreshCapabilities("GPU-titan")).thenReturn(new GpuTuningService.Capabilities(
                "GPU-titan", true, false, false, 300, 2100, 405, 7001, null,
                List.of(new GpuTuningService.ClockPair(6500, 300))));
        when(tuning.memoryOffsetRange("GPU-titan")).thenReturn(
                new GpuTuningService.MemoryOffsetRange(2, -2000, 6000, 0));
        AtomicInteger offset = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        when(tuning.apply(eq("GPU-titan"), any(), any(), isNull(), isNull(), anyInt(), eq(2)))
                .thenAnswer(invocation -> {
                    if (writes.incrementAndGet() == 2)
                        return new GpuTuningService.ApplyResult(false, "gpu_clock_restore_failed");
                    offset.set(invocation.getArgument(5));
                    return new GpuTuningService.ApplyResult(true, null);
                });
        when(tuning.readMemoryOffset("GPU-titan", 2)).thenAnswer(invocation -> offset.get());
        when(tuning.readEffectiveClocks("GPU-titan")).thenAnswer(invocation ->
                new GpuTuningService.ClockTelemetry(300, 6500 + offset.get()));
        when(tuning.restoreDefaults("GPU-titan")).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(tuning.resetUnresponsiveGpu("GPU-titan"))
                .thenReturn(new GpuTuningService.ApplyResult(false, "auto_tuning_gpu_reset_failed"));
        when(lock.tryBeginAutoTuning()).thenReturn(true);
        when(miners.minerSoftwareForWorker(any(), eq("GPU-titan"))).thenReturn("srbminer-multi");
        when(miners.minerVersionForWorker(any(), eq("GPU-titan"))).thenReturn("3.7.3");
        when(miners.gpuMinerForWorker(anyString(), eq("GPU-titan"))).thenReturn(java.util.Optional.of(miner));
        when(mining.getWorkerStats()).thenReturn(List.of(new MinerStats.Worker(
                MinerStats.MinerStatus.MINING, "Test", "PearlHash", 100d / 1e12, 60,
                280, 100, 280, 280, 200, List.of(), "GPU", "Test", "GPU-titan",
                null, null, MinerStats.PoolTelemetry.unavailable())));
        AutoTuningProfileStore store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("hung-gpu-profiles.json").toString());
        AutoTuningSessionService service = new AutoTuningSessionService(mining, power, tuning, store, lock,
                miners, 85, 0);
        try {
            service.start("GPU-titan", "pearl");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (service.status().running() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("auto_tuning_gpu_reset_failed", service.status().phase());
            assertEquals(2, writes.get());
            verify(miner).pauseGpu("NVIDIA", 4);
            verify(tuning).resetUnresponsiveGpu("GPU-titan");
            verify(tuning).restoreDefaults("GPU-titan");
            verify(power, atLeastOnce()).setAutoTuningPowerTarget(280, gpu);
            assertThrows(IllegalStateException.class, () -> service.start("GPU-titan", "pearl"));
        } finally { service.shutdown(); }
    }
    @Test
    void longerConfirmationRejectsLuckySearchWinner() throws Exception {
        verifyConfirmation(false);
    }

    @Test
    void failedConfirmationsNeverPublishSearchPointsAsProfiles() throws Exception {
        verifyConfirmation(true);
    }

    @Test
    void curveGuidedRunProbesNeighborCapsAndConfirmsAllAnchorTiersWithinTheSameLock() throws Exception {
        verifyConfirmation(false, true);
    }

    private void verifyConfirmation(boolean failAll) throws Exception { verifyConfirmation(failAll, false); }

    private void verifyConfirmation(boolean failAll, boolean curveGuided) throws Exception {
        MiningService mining = mock(MiningService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        GpuTuningService tuning = mock(GpuTuningService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        MinerFactory miners = mock(MinerFactory.class);
        var gpu = new LocalGpuPowerService.Gpu("AMD", 0, "GPU-confirm", "Test", 80, 200,
                80, 200, 150, 120.0, "measured", true, null, 200);
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.snapshot()).thenReturn(List.of(gpu));
        when(power.readTemperatureC(gpu)).thenReturn(60d);
        when(power.configuredPowerLimitWatts(gpu)).thenReturn(150);
        when(power.setAutoTuningPowerTarget(anyInt(), eq(gpu))).thenReturn(true);
        when(tuning.refreshCapabilities(gpu.deviceId())).thenReturn(new GpuTuningService.Capabilities(
                gpu.deviceId(), true, true, false, 1000, 1400, 400, 800, null));
        when(tuning.apply(anyString(), any(), any(), any(), any()))
                .thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(tuning.restoreDefaults(gpu.deviceId())).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(lock.tryBeginAutoTuning()).thenReturn(true);
        when(lock.holder()).thenReturn("efficiency-sweep");
        when(power.setSweepPowerTarget(anyLong(), any())).thenReturn(true);
        when(miners.minerSoftwareForWorker(any(), eq(gpu.deviceId()))).thenReturn("srbminer-multi");
        when(miners.minerVersionForWorker(any(), eq(gpu.deviceId()))).thenReturn("3.7.3");
        Path file = directory.resolve("confirmation-" + failAll + ".json");
        var store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(), file.toString());
        var service = new AutoTuningSessionService(mining, power, tuning, store, lock, miners, 85, 0);
        when(mining.getWorkerStats()).thenAnswer(invocation -> {
            var session = service.status();
            boolean confirming = "auto_tuning_phase_confirming".equals(session.phase());
            boolean lucky = Integer.valueOf(1150).equals(session.currentCoreMhz())
                    && Integer.valueOf(400).equals(session.currentMemoryMhz());
            int cap = session.currentPowerWatts() == null ? 150 : session.currentPowerWatts();
            double rate = curveGuided ? cap == 100 ? 100 : cap == 110 ? 130 : cap == 150 ? 140 : cap == 200 ? 180 : cap - 20
                    : confirming && failAll ? 0 : lucky ? confirming ? 50 : 200 : 100;
            return List.of(new MinerStats.Worker(MinerStats.MinerStatus.MINING, "Test", "PearlHash", rate / 1e12, 60,
                    150, 80, 150, 200, curveGuided ? cap - 10 : 120, List.of(), "GPU", "Test", gpu.deviceId(),
                    null, null, MinerStats.PoolTelemetry.unavailable()));
        });
        try {
            if (curveGuided) {
                var curve = new GpuEfficiencyStore.Profile(gpu.deviceId(), gpu.model(), "pearl", "PearlHash", 150,
                        1000d, 120d, 1000d / 120, java.time.Instant.now(), List.of(
                        new GpuEfficiencyStore.StepResult(100, 900d, 90d, 60d, true, null),
                        new GpuEfficiencyStore.StepResult(150, 1100d, 140d, 60d, true, null),
                        new GpuEfficiencyStore.StepResult(200, 1300d, 190d, 60d, true, null)), "srbminer-multi");
                var result = service.refineWithinSweep(gpu.deviceId(), "pearl", curve, () -> false);
                assertFalse(result.running());
                assertEquals("efficiency-sweep", lock.holder());
                verify(lock, never()).endAutoTuning();
            } else service.start(gpu.deviceId(), "pearl");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (service.status().running() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(service.status().running());
            var reloaded = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(), file.toString());
            assertTrue(reloaded.measurements().stream().anyMatch(row -> "SEARCH_RESULT".equals(row.type())
                    && Boolean.TRUE.equals(row.stable()) && row.hashrateHs() == (curveGuided ? 130 : 200)));
            if (failAll) {
                assertEquals("auto_tuning_no_confirmed_tier", service.status().phase());
                assertTrue(reloaded.all().isEmpty());
            } else {
                assertEquals("auto_tuning_complete", service.status().phase());
                assertEquals(curveGuided ? 130d : 100d, service.status().result().hashrateHs());
                if (curveGuided) assertEquals(110, service.status().result().powerLimitWatts());
                assertTrue(reloaded.all().stream().allMatch(row -> row.stableSamples() == 32 && (curveGuided || row.hashrateHs() <= 100)));
                assertTrue(reloaded.matrix(gpu.deviceId(), "pearl", "srbminer-multi", "3.7.3", "Test", "AMD").size() >= 3);
                if (curveGuided) {
                    var searched = reloaded.measurements().stream().filter(row -> "SEARCH_RESULT".equals(row.type())).toList();
                    assertEquals(100, searched.getFirst().powerLimitWatts());
                    assertTrue(searched.stream().anyMatch(row -> !List.of(100, 150, 200).contains(row.powerLimitWatts())));
                    assertTrue(searched.stream().allMatch(row -> row.powerLimitWatts() >= 100 && row.powerLimitWatts() <= 200));
                    assertTrue(reloaded.measurements().stream().filter(row -> "RESULT".equals(row.type())).count() <= 9);
                }
            }
        } finally { service.shutdown(); }
    }

    @Test
    void finalistsIncludeTwoDistinctStableCandidatesPerTierAndExcludeRemovedControls() {
        var a = new BayesianGpuOptimizer.Candidate(900, 5000, 100, .1, 0, 0);
        var b = new BayesianGpuOptimizer.Candidate(1100, 5000, 100, .5, 0, 0);
        var c = new BayesianGpuOptimizer.Candidate(1200, 5000, 100, .7, 0, 0);
        var removed = new BayesianGpuOptimizer.Candidate(1400, 7000, 200, 1, 1, 1);
        var otherTier = new BayesianGpuOptimizer.Candidate(900, 5000, 200, .1, 0, 1);
        var observations = List.of(new BayesianGpuOptimizer.Observation(a, 10, true, null),
                new BayesianGpuOptimizer.Observation(b, 9, true, null),
                new BayesianGpuOptimizer.Observation(c, 8, true, null),
                new BayesianGpuOptimizer.Observation(removed, 20, true, null),
                new BayesianGpuOptimizer.Observation(otherTier, 7, true, null));
        assertEquals(List.of(a, b, otherTier), AutoTuningSessionService.confirmationCandidates(
                observations, List.of(a, b, c, otherTier)));
    }

    @Test
    void fastestMeasuredPointIsConfirmedAlongsideEfficiencyFinalists() {
        var a = new BayesianGpuOptimizer.Candidate(900, 5000, 100, .1, 0, 0);
        var b = new BayesianGpuOptimizer.Candidate(1100, 5000, 100, .5, 0, 0);
        var fast = new BayesianGpuOptimizer.Candidate(1200, 5000, 100, .7, 0, 0);
        var sample = new AutoTuningSessionService.SampleSummary(true, 150, 99, 16, 60d, 1200, 5000, null, false);
        assertEquals(List.of(a, b, fast), AutoTuningSessionService.confirmationCandidates(List.of(
                new BayesianGpuOptimizer.Observation(a, 10, true, null),
                new BayesianGpuOptimizer.Observation(b, 9, true, null),
                new BayesianGpuOptimizer.Observation(fast, 8, true, null, sample)), List.of(a, b, fast)));
    }

    @Test
    void sweepRefinementKeepsExclusiveLockAndHonorsParentCancellation() {
        var mining = mock(MiningService.class);
        var power = mock(LocalGpuPowerService.class);
        var tuning = mock(GpuTuningService.class);
        var miners = mock(MinerFactory.class);
        var lock = new LocalRunLock();
        var gpu = new LocalGpuPowerService.Gpu("AMD", 0, "GPU-owned", "Test", 80, 250,
                80, 180, 150, 120d, "measured", true, null, 250);
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.snapshot()).thenReturn(List.of(gpu));
        when(power.readTemperatureC(any())).thenReturn(60d);
        when(power.setAutoTuningPowerTarget(anyInt(), any())).thenReturn(true);
        when(tuning.refreshCapabilities(gpu.deviceId())).thenReturn(new GpuTuningService.Capabilities(
                gpu.deviceId(), true, true, false, 1000, 1400, 400, 800, null));
        when(tuning.restoreDefaults(anyString())).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(miners.minerSoftwareForWorker(any(), anyString())).thenReturn("srbminer-multi");
        when(mining.getWorkerStats()).thenReturn(List.of(new MinerStats.Worker(MinerStats.MinerStatus.MINING,
                "Test", "PearlHash", 100d / 1e12, 60, 150, 80, 150, 180, 120, List.of(), "GPU", "Test", gpu.deviceId(),
                null, null, MinerStats.PoolTelemetry.unavailable())));
        var store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(), directory.resolve("owned.json").toString());
        var service = new AutoTuningSessionService(mining, power, tuning, store, lock, miners, 85, 0);
        try {
            assertThrows(IllegalStateException.class, () -> service.refineWithinSweep(gpu.deviceId(), "pearl", 150, () -> true));
            assertTrue(lock.tryBegin("efficiency-sweep"));
            var result = service.refineWithinSweep(gpu.deviceId(), "pearl", 150, () -> true);
            assertFalse(result.running());
            assertEquals("auto_tuning_cancelled", result.phase());
            assertEquals("efficiency-sweep", lock.holder());
            assertFalse(lock.tryBegin("benchmark"));
            verify(tuning, never()).apply(anyString(), any(), any(), any(), any());
            when(tuning.restoreDefaults(anyString())).thenThrow(new IllegalStateException("driver recovery failed"));
            var failed = service.refineWithinSweep(gpu.deviceId(), "pearl", 150, () -> true);
            assertFalse(failed.running());
            assertEquals("gpu_clock_restore_failed", failed.phase());
            assertEquals("efficiency-sweep", lock.holder());
        } finally { service.shutdown(); lock.end(); }
    }

    @Test
    void nativeCandidateWaitsThroughZeroHashrateAndChecksTemperatureAndCancellation() throws Exception {
        var mining = mock(MiningService.class);
        var power = mock(LocalGpuPowerService.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "gpu", "Test", 80, 200, 80, 200, 150, 120d, "measured", true, null, 200);
        var starting = mock(MinerStats.Worker.class);
        when(starting.deviceId()).thenReturn("gpu");
        when(starting.miningStatus()).thenReturn(MinerStats.MinerStatus.MINING);
        when(starting.currentAlgorithm()).thenReturn("PearlHash");
        when(starting.terahashPerSecond()).thenReturn(0d);
        var ready = mock(MinerStats.Worker.class);
        when(ready.deviceId()).thenReturn("gpu");
        when(ready.miningStatus()).thenReturn(MinerStats.MinerStatus.MINING);
        when(ready.currentAlgorithm()).thenReturn("PearlHash");
        when(ready.terahashPerSecond()).thenReturn(1e-9);
        when(mining.getWorkerStats()).thenReturn(List.of(starting), List.of(starting), List.of(ready));
        when(power.readTemperatureC(gpu)).thenReturn(60d);
        var service = new AutoTuningSessionService(mining, power, mock(GpuTuningService.class), mock(AutoTuningProfileStore.class), new LocalRunLock(), mock(MinerFactory.class), 85, 0);
        try {
            service.awaitNativeCandidate(gpu, de.verdox.solarminer.pcagent.coin.Coin.PEARL, () -> false, System.nanoTime() + 1_000_000_000L);
            verify(mining, times(3)).getWorkerStats();
            assertEquals("auto_tuning_cancelled", assertThrows(IllegalStateException.class, () -> service.awaitNativeCandidate(gpu,
                    de.verdox.solarminer.pcagent.coin.Coin.PEARL, () -> true, Long.MAX_VALUE)).getMessage());
            when(power.readTemperatureC(gpu)).thenReturn(86d);
            assertEquals("auto_tuning_temperature_limit", assertThrows(IllegalStateException.class, () -> service.awaitNativeCandidate(gpu,
                    de.verdox.solarminer.pcagent.coin.Coin.PEARL, () -> false, Long.MAX_VALUE)).getMessage());
        } finally { service.shutdown(); }
    }

}
