package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.SweepMode;
import de.verdox.solarminer.pcagent.coin.SweepRunState;
import de.verdox.solarminer.pcagent.coin.Coin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.util.Set;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.MinerFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class EfficiencySweepServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test
    void retriesPeakStartOnceAfterStoppingThePreviousManagedMiner() {
        var gpu = gpu("rtx-2080", "NVIDIA", 0);
        MinerFactory miners = mock(MinerFactory.class);
        GpuCoinMiner previous = mock(GpuCoinMiner.class);
        GpuCoinMiner peak = mock(GpuCoinMiner.class);
        when(miners.gpuMiner(Coin.PEARL, "peakminer")).thenReturn(peak);
        when(miners.gpuMinerAdapters(any(Coin.class)))
                .thenReturn(List.of(new MinerFactory.GpuMinerAdapter("srbminer-multi", previous)));
        when(peak.resumeGpu("NVIDIA", 0)).thenReturn(false, true);
        when(peak.lastError()).thenReturn("Another miner already controls NVIDIA:0");
        when(previous.stopGpu("NVIDIA", 0)).thenReturn(true);
        var service = new EfficiencySweepService(miners, mock(LocalGpuPowerService.class),
                mock(de.verdox.solarminer.pcagent.miner.MinerConsoleService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), new LocalRunLock(),
                15, 85, 0.1, 240);

        assertTrue(service.startMiner(new EfficiencySweepService.Target("pearl", "PearlHash", gpu, null, "peakminer")));
        verify(peak, times(2)).resumeGpu("NVIDIA", 0);
        verify(previous, org.mockito.Mockito.atLeastOnce()).stopGpu("NVIDIA", 0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void combinedSweepAttributesAlternateMinerAndBlocksUnsafeRestoration(boolean restorationFails, boolean refine) throws Exception {
        var gpu = gpu("rtx-2080", "NVIDIA", 0);
        MinerFactory miners = mock(MinerFactory.class);
        GpuCoinMiner pearl = mock(GpuCoinMiner.class);
        GpuCoinMiner srb = mock(GpuCoinMiner.class);
        GpuCoinMiner quanPool = mock(GpuCoinMiner.class);
        GpuCoinMiner other = mock(GpuCoinMiner.class);
        when(miners.gpuMiner(any(Coin.class))).thenReturn(other);
        when(miners.gpuMiner(Coin.PEARL)).thenReturn(pearl);
        when(miners.gpuMiner(Coin.QUANTUS)).thenReturn(quanPool);
        when(miners.gpuMiner(Coin.QUANTUS, "srbminer-multi")).thenReturn(srb);
        when(miners.minerSoftwareForMeasurement(Coin.QUANTUS, "rtx-2080")).thenReturn("srbminer-multi");
        when(miners.gpuMinerAdapters(Coin.QUANTUS)).thenReturn(List.of(
                new MinerFactory.GpuMinerAdapter("srbminer-multi", srb),
                new MinerFactory.GpuMinerAdapter("quanpool-miner", quanPool)));
        when(srb.binaryAvailable()).thenReturn(true);
        when(srb.configuration()).thenReturn(new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:1"));
        when(srb.selectedGpus()).thenReturn(List.of());
        when(srb.reportedAlgorithm()).thenReturn("QPoW (Poseidon2)");
        when(srb.stopGpu("NVIDIA", 0)).thenReturn(true);
        when(quanPool.stopGpu("NVIDIA", 0)).thenReturn(true);
        when(pearl.stopGpu("NVIDIA", 0)).thenReturn(true);
        when(other.stopGpu("NVIDIA", 0)).thenReturn(true);
        when(srb.gpuStates(anyList())).thenReturn(List.of(new de.verdox.solarminer.pcagent.miner.GpuState(
                "NVIDIA", 0, "GPU", true, de.verdox.solarminer.pcagent.dto.MinerStats.MinerStatus.MINING, true, true, false, null, null)));
        when(pearl.captureState()).thenReturn(new GpuCoinMiner.WorkerPauseSnapshot(Set.of(), Set.of("rtx-2080")));
        when(pearl.restoreState(any())).thenReturn(true);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(gpu));
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.setSweepPowerTarget(eq(200L), eq(gpu))).thenReturn(true);
        when(power.restorePowerLimits(any())).thenReturn(!restorationFails);
        var lock = new LocalRunLock();
        var refinement = mock(SweepRefinementService.class);
        when(refinement.refine(any(), any(), any(), eq(BenchmarkPreset.SLOW))).thenAnswer(call -> {
            assertEquals("efficiency-sweep", lock.holder());
            return false;
        });
        var service = new EfficiencySweepService(miners, power,
                mock(de.verdox.solarminer.pcagent.miner.MinerConsoleService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), lock,
                15, 85, 0.1, 240);

        var controls = new de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService(
                new com.fasterxml.jackson.databind.ObjectMapper(), directory.resolve("controls.json").toString());
        assertTrue(controls.setWorkerManuallyStopped(gpu.deviceId(), true));
        service.setControls(controls);
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        when(srb.resumeGpu("NVIDIA", 0)).thenAnswer(call -> {
            assertFalse(controls.mayStart(gpu.deviceId()));
            assertEquals(starts.getAndIncrement() == 0,
                    de.verdox.solarminer.pcagent.miner.MinerStartContext.measurementAuthorized(gpu.deviceId()));
            return false; // Exercise all start attempts without waiting for hardware samples.
        });
        service.setRefinement(refinement);
        assertEquals(1, service.start(List.of("quantus"), List.of("rtx-2080"), 1, refine).runs().size());
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (service.status().running()) Thread.sleep(10);
        });
        verify(srb).setSweepOverride(any(MinerConfig.class));
        verify(srb, times(restorationFails ? 1 : 2)).resumeGpu("NVIDIA", 0);
        verify(quanPool, org.mockito.Mockito.never()).resumeGpu("NVIDIA", 0);
        verify(quanPool, org.mockito.Mockito.atLeastOnce()).stopGpu("NVIDIA", 0);
        verify(srb).clearSweepOverride();
        verify(refinement).begin(eq(refine), anyList(), eq(false));
        verify(refinement).resetBeforeCurve(any());
        verify(refinement, times(refine ? 1 : 0)).refine(any(), any(), any(), eq(BenchmarkPreset.SLOW));
        verify(refinement).finishPending();
        // Whole-adapter restoration could stop/restart a GPU already released earlier.
        verify(pearl, org.mockito.Mockito.never()).restoreState(any());
        assertEquals(!restorationFails, service.status().devices().getFirst().released());
        assertEquals(restorationFails ? "BLOCKED" : "FAILED", service.status().devices().getFirst().miningState());
        verify(miners).setMeasurementContext("rtx-2080", "quantus", "srbminer-multi", "POWER_SWEEP");
        verify(miners, org.mockito.Mockito.atLeastOnce()).clearBenchmarkSoftware("rtx-2080");
    }

    @Test
    void explicitQuantusSweepAcceptsSelectedNvidiaCardsOutsideSavedDeviceList() throws Exception {
        var cards = List.of(
                new LocalGpuPowerService.Gpu("NVIDIA", 0, "rtx-2080", "RTX 2080", 100, 320,
                        200, 200, 200, 25.0, "measured", true, null, 300),
                new LocalGpuPowerService.Gpu("NVIDIA", 1, "rtx-3080", "RTX 3080", 100, 320,
                        200, 200, 200, 25.0, "measured", true, null, 300),
                new LocalGpuPowerService.Gpu("NVIDIA", 2, "titan-rtx", "Titan RTX", 100, 320,
                        200, 200, 200, 25.0, "measured", true, null, 300));
        MinerFactory miners = mock(MinerFactory.class);
        when(miners.srbBinaryAvailable()).thenReturn(true);
        GpuCoinMiner quantus = mock(GpuCoinMiner.class);
        GpuCoinMiner other = mock(GpuCoinMiner.class);
        when(miners.gpuMiner(any(Coin.class))).thenReturn(other);
        when(miners.gpuMiner(Coin.QUANTUS)).thenReturn(quantus);
        when(miners.gpuMiner(any(Coin.class), any(String.class))).thenAnswer(call -> miners.gpuMiner(call.getArgument(0, Coin.class)));
        when(quantus.binaryAvailable()).thenReturn(true);
        var saved = new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:3");
        when(quantus.configuration()).thenReturn(saved);
        when(quantus.selectedGpus()).thenReturn(List.of());
        when(quantus.reportedAlgorithm()).thenReturn("QPoW (Poseidon2)");
        when(quantus.stopGpu(eq("NVIDIA"), anyInt())).thenReturn(true);
        when(other.stopGpu(eq("NVIDIA"), anyInt())).thenReturn(true);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(cards);
        when(power.refresh()).thenReturn(cards);
        when(power.restorePowerLimits(any())).thenReturn(true);
        var service = new EfficiencySweepService(miners, power,
                mock(de.verdox.solarminer.pcagent.miner.MinerConsoleService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), new LocalRunLock(),
                15, 85, 0.1, 240);

        var started = service.start(List.of("quantus"),
                List.of("rtx-2080", "rtx-3080", "titan-rtx"), 1);
        assertEquals(Set.of("rtx-2080", "rtx-3080", "titan-rtx"), started.runs().stream()
                .map(EfficiencySweepService.RunStatus::deviceId).collect(java.util.stream.Collectors.toSet()));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (service.status().running()) Thread.sleep(10);
        });
        var override = org.mockito.ArgumentCaptor.forClass(MinerConfig.class);
        verify(quantus, times(3)).setSweepOverride(override.capture());
        assertTrue(override.getAllValues().stream().allMatch(value ->
                value.devices().equals("NVIDIA:3,NVIDIA:0,NVIDIA:1,NVIDIA:2")));
        verify(quantus, times(3)).clearSweepOverride();
        assertEquals("NVIDIA:3", saved.devices());
    }

    @Test
    void explicitPearlWorkerUsesTemporaryDeviceSelectionAndRestoresSavedRoute() throws Exception {
        var savedGpu = gpu("saved", "NVIDIA", 0);
        var measuredGpu = gpu("measured", "NVIDIA", 1);
        MinerConfig saved = new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:0");
        MinerFactory miners = mock(MinerFactory.class);
        when(miners.srbBinaryAvailable()).thenReturn(true);
        GpuCoinMiner pearl = mock(GpuCoinMiner.class);
        GpuCoinMiner other = mock(GpuCoinMiner.class);
        when(miners.gpuMiner(any(Coin.class))).thenReturn(other);
        when(miners.gpuMiner(Coin.PEARL)).thenReturn(pearl);
        when(miners.gpuMiner(any(Coin.class), any(String.class))).thenAnswer(call -> miners.gpuMiner(call.getArgument(0, Coin.class)));
        when(pearl.binaryAvailable()).thenReturn(true);
        when(pearl.configuration()).thenReturn(saved);
        when(pearl.selectedGpus()).thenReturn(List.of(savedGpu));
        when(pearl.reportedAlgorithm()).thenReturn("PearlHash");
        when(pearl.stopGpu("NVIDIA", 1)).thenReturn(true);
        when(other.stopGpu("NVIDIA", 1)).thenReturn(true);
        when(pearl.restoreState(isNull())).thenReturn(true);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(savedGpu, measuredGpu));
        when(power.refresh()).thenReturn(List.of(savedGpu, measuredGpu));
        when(power.restorePowerLimits(any())).thenReturn(true);
        var service = new EfficiencySweepService(miners, power,
                mock(de.verdox.solarminer.pcagent.miner.MinerConsoleService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), new LocalRunLock(),
                15, 85, 0.1, 240);

        var started = service.start(List.of("pearl"), List.of("measured"), 1);
        assertEquals(List.of("measured"), started.runs().stream().map(EfficiencySweepService.RunStatus::deviceId).toList());
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (service.status().running()) Thread.sleep(10);
        });
        var override = org.mockito.ArgumentCaptor.forClass(MinerConfig.class);
        verify(pearl).setSweepOverride(override.capture());
        assertEquals("NVIDIA:0,NVIDIA:1", override.getValue().devices());
        assertEquals(saved.poolUrl(), override.getValue().poolUrl());
        assertEquals(saved.wallet(), override.getValue().wallet());
        verify(pearl).clearSweepOverride();
        assertEquals("NVIDIA:0", saved.devices());
    }

    @Test
    void recoverableFailureOnOneGpuDoesNotDiscardOtherGpuOrLaterCoins() throws Exception {
        var first = gpu("one", "NVIDIA", 0);
        var second = gpu("two", "NVIDIA", 1);
        MinerFactory miners = mock(MinerFactory.class);
        when(miners.srbBinaryAvailable()).thenReturn(true);
        GpuCoinMiner inactive = mock(GpuCoinMiner.class);
        GpuCoinMiner pearl = mock(GpuCoinMiner.class);
        GpuCoinMiner raven = mock(GpuCoinMiner.class);
        when(miners.gpuMiner(any(Coin.class))).thenReturn(inactive);
        when(miners.gpuMiner(Coin.PEARL)).thenReturn(pearl);
        when(miners.gpuMiner(Coin.RAVENCOIN)).thenReturn(raven);
        when(miners.gpuMiner(any(Coin.class), any(String.class))).thenAnswer(call -> miners.gpuMiner(call.getArgument(0, Coin.class)));
        when(pearl.binaryAvailable()).thenReturn(true);
        when(raven.binaryAvailable()).thenReturn(true);
        for (GpuCoinMiner miner : List.of(pearl, raven)) {
            when(miner.configuration()).thenReturn(new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:0,NVIDIA:1"));
            when(miner.selectedGpus()).thenReturn(List.of(first, second));
            when(miner.stopGpu(any(String.class), anyInt())).thenReturn(true);
        }
        when(inactive.stopGpu(any(String.class), anyInt())).thenReturn(true);
        when(pearl.restoreState(isNull())).thenReturn(true);
        when(pearl.reportedAlgorithm()).thenReturn("PearlHash");
        when(raven.reportedAlgorithm()).thenReturn("KAWPOW");
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(first, second));
        when(power.refresh()).thenReturn(List.of(first, second));
        when(power.restorePowerLimits(any())).thenReturn(true);
        var failedOnce = new java.util.concurrent.atomic.AtomicBoolean();
        when(power.setSweepPowerTarget(eq(200L), any(LocalGpuPowerService.Gpu.class))).thenAnswer(call -> {
            LocalGpuPowerService.Gpu card = call.getArgument(1);
            if (card.deviceId().equals("one") && failedOnce.compareAndSet(false, true))
                throw new IllegalStateException("driver readback failed");
            return false;
        });
        var service = new EfficiencySweepService(miners, power,
                mock(de.verdox.solarminer.pcagent.miner.MinerConsoleService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), new LocalRunLock(),
                15, 85, 0.1, 240);

        service.start(List.of("pearl", "ravencoin"), List.of("one", "two"), 2);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (service.status().running()) Thread.sleep(10);
        });

        var runs = service.status().runs();
        assertEquals(4, runs.size());
        assertEquals("lab_run_partial", service.status().phase(), "Failed measurements must not be labelled a complete run");
        assertTrue(runs.stream().allMatch(run -> run.status() == SweepRunState.FAILED));
        assertEquals(1, runs.stream().filter(run -> run.detail().contains("driver readback failed")).count());
        assertEquals(3, runs.stream().filter(run -> run.steps().size() == 1).count());
        verify(power, times(4)).setSweepPowerTarget(eq(200L), any(LocalGpuPowerService.Gpu.class));
    }

    private static LocalGpuPowerService.Gpu gpu(String id, String vendor, int index) {
        return new LocalGpuPowerService.Gpu(vendor, index, id, "GPU", 100, 320,
                200, 200, 200, 25.0, "measured", true, null, 300);
    }

    @Test
    void referenceStartRespectsUserMaximumAndUsesCurrentCapWithoutDriverDefault() {
        var capped = new LocalGpuPowerService.Gpu("NVIDIA", 0, "one", "GPU", 100, 320,
                100, 200, 180, 25.0, "measured", true, null, 300);
        var noDefault = new LocalGpuPowerService.Gpu("NVIDIA", 0, "one", "GPU", 100, 320,
                100, 200, 180, 25.0, "measured", true, null);

        assertEquals(200, EfficiencySweepService.referenceStartLimit(capped));
        assertEquals(180, EfficiencySweepService.referenceStartLimit(noDefault));
    }

    @Test
    void firstCoinStartupFailureStillRunsSecondCoinOnSameGpu() throws Exception {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "one", "GPU", 100, 320,
                200, 200, 200, 25.0, "measured", true, null, 300);
        MinerFactory miners = mock(MinerFactory.class);
        when(miners.srbBinaryAvailable()).thenReturn(true);
        GpuCoinMiner inactive = mock(GpuCoinMiner.class);
        GpuCoinMiner pearl = mock(GpuCoinMiner.class);
        GpuCoinMiner raven = mock(GpuCoinMiner.class);
        when(miners.gpuMiner(any(Coin.class))).thenReturn(inactive);
        when(miners.gpuMiner(Coin.PEARL)).thenReturn(pearl);
        when(miners.gpuMiner(Coin.RAVENCOIN)).thenReturn(raven);
        when(miners.gpuMiner(any(Coin.class), any(String.class))).thenAnswer(call -> miners.gpuMiner(call.getArgument(0, Coin.class)));
        when(pearl.binaryAvailable()).thenReturn(true);
        when(raven.binaryAvailable()).thenReturn(true);
        for (GpuCoinMiner miner : List.of(pearl, raven)) {
            when(miner.configuration()).thenReturn(new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:0"));
            when(miner.selectedGpus()).thenReturn(List.of(gpu));
            when(miner.stopGpu("NVIDIA", 0)).thenReturn(true);
        }
        when(inactive.stopGpu("NVIDIA", 0)).thenReturn(true);
        when(pearl.restoreState(isNull())).thenReturn(true);
        when(pearl.reportedAlgorithm()).thenReturn("PearlHash");
        when(raven.reportedAlgorithm()).thenReturn("KAWPOW");
        when(pearl.resumeGpu("NVIDIA", 0)).thenReturn(false);
        when(pearl.lastError()).thenReturn("Pearl SolarMiner proxy is unreachable");
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(gpu));
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.restorePowerLimits(any())).thenReturn(true);
        when(power.setSweepPowerTarget(eq(200L), eq(gpu))).thenReturn(true, false);
        var service = new EfficiencySweepService(miners, power, mock(de.verdox.solarminer.pcagent.miner.MinerConsoleService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), new LocalRunLock(),
                15, 85, 0.1, 240);

        service.start(List.of("pearl", "ravencoin"), List.of("one"), 1);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (service.status().running()) Thread.sleep(10);
        });

        var runs = service.status().runs();
        assertEquals("lab_run_partial", service.status().phase(), "Failed measurements must not be labelled a complete run");
        assertEquals(List.of("pearl", "ravencoin"), runs.stream().map(EfficiencySweepService.RunStatus::coin).toList());
        assertTrue(runs.stream().allMatch(run -> run.status() == SweepRunState.FAILED && run.steps().size() == 1));
        assertEquals("Pearl SolarMiner proxy is unreachable", runs.getFirst().steps().getFirst().note());
        verify(pearl).resumeGpu("NVIDIA", 0);
        verify(power, times(2)).setSweepPowerTarget(eq(200L), eq(gpu));
    }

    @Test
    void candidateLimitsSweepDownwardFromCurrentAndAlwaysEndAtTheEffectiveMinimum() {
        List<Integer> limits = EfficiencySweepService.candidateLimits(65, 120, 15);

        assertEquals(List.of(120, 105, 90, 75, 65), limits);
    }

    @Test
    void candidateLimitsNeverUndercutTheEffectiveMinimum() {
        List<Integer> limits = EfficiencySweepService.candidateLimits(100, 120, 15);

        assertEquals(List.of(120, 105, 100), limits);
        assertTrue(limits.stream().allMatch(limit -> limit >= 100));
    }

    @Test
    void candidateLimitsNeverRaiseAThrottledCardToItsConfiguredMaximum() {
        List<Integer> limits = EfficiencySweepService.candidateLimits(80, 170, 15);

        assertEquals(List.of(170, 155, 140, 125, 110, 95, 80), limits);
        assertTrue(limits.stream().allMatch(limit -> limit <= 170));
    }

    @Test
    void validationStartsAtTheReferenceAndMovesUpToTheCardsOriginalLimit() {
        assertEquals(List.of(155, 170, 185, 200),
                EfficiencySweepService.validationLimits(155, 100, 200, 15));
        assertEquals(List.of(200),
                EfficiencySweepService.validationLimits(220, 100, 200, 15));
    }

    @Test
    void runningCombinedStatusPublishesNumericEtaInsteadOfNull() {
        var refinement = mock(SweepRefinementService.class);
        var optimization = new SweepRefinementService.Status(true, List.of());
        when(refinement.status()).thenReturn(optimization);
        when(refinement.remainingSeconds(optimization)).thenReturn(java.util.Map.of("one", 1000L));
        var service = new EfficiencySweepService(mock(MinerFactory.class), mock(LocalGpuPowerService.class),
                mock(de.verdox.solarminer.pcagent.miner.MinerConsoleService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), new LocalRunLock(),
                15, 85, 0.1, 240);
        service.setRefinement(refinement);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "refineAfterSweep", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "session",
                new EfficiencySweepService.Session(true, "benchmark_optimization_tuning", Instant.now(), 1, 1,
                        List.of(), List.of(), 0L));
        assertEquals(1000L, service.status().secondsRemaining());
    }

    @Test
    void combinedEtaIncludesTuningAfterSweepAndHonorsConcurrencyAndDeviceSerialization() {
        var first = run("one", SweepMode.FULL, List.of(200, 185));
        var second = run("two", SweepMode.FULL, List.of(200, 185));
        var tuning = java.util.Map.of(first.deviceId(), 1000L, second.deviceId(), 500L);
        assertEquals(2400, EfficiencySweepService.estimateCombinedRemainingSeconds(List.of(first, second), tuning, 1));
        assertEquals(1450, EfficiencySweepService.estimateCombinedRemainingSeconds(List.of(first, second), tuning, 2));
        assertEquals(1000, EfficiencySweepService.estimateCombinedRemainingSeconds(List.of(), tuning, 2));
        assertEquals(0, EfficiencySweepService.estimateCombinedRemainingSeconds(List.of(), java.util.Map.of(), 2));
    }

    @Test
    void tuningEtaExistsBeforeFirstSampleAndDecreasesWithSearchAndConfirmationProgress() {
        long initial = AutoTuningSessionService.estimateRemainingSeconds(0, 0, 0, 5000);
        assertTrue(initial > 0);
        long first = AutoTuningSessionService.estimateRemainingSeconds(1, 0, 16, 5000);
        assertEquals(initial, first);
        assertEquals(first - 40, AutoTuningSessionService.estimateRemainingSeconds(1, 8, 16, 5000));
        long confirming = AutoTuningSessionService.estimateRemainingSeconds(25, 0, 32, 5000);
        assertTrue(confirming < first);
        assertEquals(confirming, AutoTuningSessionService.estimateRemainingSeconds(19, 0, 32, 5000));
        assertEquals(confirming - 80, AutoTuningSessionService.estimateRemainingSeconds(25, 16, 32, 5000));
    }

    @Test
    void etaCountsSiblingValidationRunsInParallel() {
        var first = run("one", SweepMode.VALIDATION, List.of(155, 170));
        var second = run("two", SweepMode.VALIDATION, List.of(155, 170));

        assertEquals(450, EfficiencySweepService.estimateRemainingSeconds(
                List.of(first, second), Instant.now()));
    }

    @Test
    void identicalModelsStillGetIndependentFullCurves() {
        var first = target("one", "TITAN RTX", 100, 320, "PearlHash");
        var sibling = target("two", "TITAN RTX", 100, 320, "PearlHash");
        var differentBounds = target("three", "TITAN RTX", 120, 320, "PearlHash");
        var differentAlgorithm = target("four", "TITAN RTX", 100, 320, "KAWPOW");

        List<List<EfficiencySweepService.Target>> groups = EfficiencySweepService.targetGroups(
                List.of(first, sibling, differentBounds, differentAlgorithm));

        assertEquals(List.of(1, 1, 1, 1), groups.stream().map(List::size).toList());
    }

    @Test
    void referenceBatchesSpreadCoinsAcrossDistinctPhysicalGpus() {
        var firstCoin = List.of(target("one", "TITAN RTX", 100, 320, "PearlHash"),
                target("two", "TITAN RTX", 100, 320, "PearlHash"));
        var secondCoin = List.of(targetForCoin("one", "ravencoin", "KAWPOW"),
                targetForCoin("two", "ravencoin", "KAWPOW"));
        var thirdCoin = List.of(targetForCoin("one", "decred", "BLAKE3"),
                targetForCoin("two", "decred", "BLAKE3"));

        var batches = EfficiencySweepService.referenceBatches(EfficiencySweepService.targetGroups(
                java.util.stream.Stream.of(firstCoin, secondCoin, thirdCoin).flatMap(List::stream).toList()));

        assertEquals(List.of(2, 2, 2), batches.stream().map(List::size).toList());
        assertEquals(6, batches.stream().flatMap(List::stream).count());
        for (var batch : batches) {
            List<String> devices = batch.stream().map(group -> group.getFirst().gpu().deviceId()).toList();
            assertEquals(devices.size(), Set.copyOf(devices).size());
        }
    }

    @Test
    void etaCountsCoinReferencesInTheSameBatchInParallel() {
        var first = run("one", SweepMode.FULL, List.of(200, 185), 0);
        var second = run("two", SweepMode.FULL, List.of(200, 185), 0);
        var nextBatch = run("one-again", SweepMode.FULL, List.of(200, 185), 1);

        assertEquals(900, EfficiencySweepService.estimateRemainingSeconds(
                List.of(first, second, nextBatch), Instant.now()));
    }

    @Test
    void schedulerPrefersUnlockedSameCoinValidationBeforeAnotherReference() {
        var validation = task(targetForCoin("one", "pearl", "PearlHash"), SweepMode.VALIDATION);
        var otherCoin = task(targetForCoin("two", "ravencoin", "KAWPOW"), SweepMode.FULL);
        List<EfficiencySweepService.SweepTask> validations = new java.util.ArrayList<>(List.of(validation));
        List<EfficiencySweepService.SweepTask> references = new java.util.ArrayList<>(List.of(otherCoin));

        var selected = EfficiencySweepService.takeRunnable(validations, references, Set.of());

        assertSame(validation, selected);
        assertTrue(validations.isEmpty());
        assertEquals(List.of(otherCoin), references);
    }

    @Test
    void schedulerUsesOtherCoinWorkWhileValidationDeviceIsBusy() {
        var blockedValidation = task(targetForCoin("one", "pearl", "PearlHash"), SweepMode.VALIDATION);
        var otherCoin = task(targetForCoin("two", "ravencoin", "KAWPOW"), SweepMode.FULL);
        List<EfficiencySweepService.SweepTask> validations = new java.util.ArrayList<>(List.of(blockedValidation));
        List<EfficiencySweepService.SweepTask> references = new java.util.ArrayList<>(List.of(otherCoin));

        var selected = EfficiencySweepService.takeRunnable(validations, references, Set.of("one"));

        assertSame(otherCoin, selected);
        assertEquals(List.of(blockedValidation), validations);
        assertTrue(references.isEmpty());
        assertNull(EfficiencySweepService.takeRunnable(validations, references, Set.of("one")));
    }

    @Test
    void schedulerKeepsSecondCoinQueuedUntilTheSameGpuIsFree() {
        var firstCoin = task(targetForCoin("one", "pearl", "PearlHash"), SweepMode.FULL);
        var secondCoin = task(targetForCoin("one", "ravencoin", "KAWPOW"), SweepMode.FULL);
        List<EfficiencySweepService.SweepTask> references = new java.util.ArrayList<>(List.of(firstCoin, secondCoin));
        List<EfficiencySweepService.SweepTask> validations = new java.util.ArrayList<>();

        assertSame(firstCoin, EfficiencySweepService.takeRunnable(validations, references, Set.of()));
        assertNull(EfficiencySweepService.takeRunnable(validations, references, Set.of("one")));
        assertEquals(List.of(secondCoin), references);
        assertSame(secondCoin, EfficiencySweepService.takeRunnable(validations, references, Set.of()));
        assertTrue(references.isEmpty());
    }

    @Test
    void resumeReusesOnlyFullyCompletedRunsAndNotCancelledPartialResults() {
        var complete = withStatus(identifiedRun("one", SweepMode.FULL, List.of(200, 185)), SweepRunState.COMPLETE);
        var cancelled = withStatus(identifiedRun("two", SweepMode.FULL, List.of(200, 185)), SweepRunState.CANCELLED);
        var completeProfile = profile("one", "pearl", "PearlHash", 185);
        var partialProfile = profile("two", "pearl", "PearlHash", 200);
        var previous = new EfficiencySweepService.Session(false, "Abgebrochen", Instant.now(), 2, 2,
                List.of(completeProfile, partialProfile), List.of(complete, cancelled), 0L);

        var checkpoint = EfficiencySweepService.reusableProfiles(previous);

        assertEquals(Set.of("one|pearl|PearlHash"), checkpoint.keySet());
        var resumed = EfficiencySweepService.applyCheckpoint(
                List.of(identifiedRun("one", SweepMode.FULL, List.of(200, 185)),
                        identifiedRun("two", SweepMode.FULL, List.of(200, 185))),
                previous.runs(), checkpoint);
        assertEquals(SweepRunState.COMPLETE, resumed.getFirst().status());
        assertEquals(SweepRunState.QUEUED, resumed.get(1).status());
    }

    @Test
    void resumeDoesNotReuseResultFromDifferentMinerSoftware() {
        var previousRun = withStatus(identifiedRun("one", SweepMode.FULL, List.of(200, 185)), SweepRunState.COMPLETE);
        var srbRun = new EfficiencySweepService.RunStatus(previousRun.id() + "|srbminer-multi",
                previousRun.deviceId(), previousRun.model(), previousRun.coin(), previousRun.algorithm(),
                previousRun.mode(), previousRun.status(), previousRun.limitWatts(), previousRun.plannedLimits(),
                previousRun.steps(), previousRun.samples(), previousRun.samplesRequired(),
                previousRun.referenceBatch(), previousRun.detail());
        var baseProfile = profile("one", "pearl", "PearlHash", 185);
        var srbProfile = new GpuEfficiencyStore.Profile(baseProfile.deviceId(), baseProfile.model(),
                baseProfile.coin(), baseProfile.algorithm(), baseProfile.bestStableWatts(),
                baseProfile.bestHashrateHs(), baseProfile.bestPowerWatts(), baseProfile.bestHashesPerWatt(),
                baseProfile.testedAt(), baseProfile.steps(), "srbminer-multi");
        var previous = new EfficiencySweepService.Session(false, "Abgebrochen", Instant.now(), 1, 1,
                List.of(srbProfile), List.of(srbRun), 0L);
        var checkpoint = EfficiencySweepService.reusableProfiles(previous);
        var quanRun = new EfficiencySweepService.RunStatus("one|pearl|PearlHash|quanpool-miner",
                srbRun.deviceId(), srbRun.model(), srbRun.coin(), srbRun.algorithm(), srbRun.mode(),
                SweepRunState.QUEUED, null, srbRun.plannedLimits(), List.of(), 0,
                srbRun.samplesRequired(), srbRun.referenceBatch(), srbRun.detail());

        assertEquals(Set.of(srbRun.id()), checkpoint.keySet());
        assertEquals(SweepRunState.QUEUED,
                EfficiencySweepService.applyCheckpoint(List.of(quanRun), previous.runs(), checkpoint)
                        .getFirst().status());
    }

    @Test
    void storeKeySeparatesDevicesAndAlgorithms() {
        assertEquals("GPU-abc|kawpow", GpuEfficiencyStore.key("GPU-abc", "kawpow"));
    }

    @Test
    void workerLookupMatchesCanonicalQuantusTelemetryAgainstDisplayAlgorithm() {
        var target = targetForCoin("one", "quantus", "QPoW (Poseidon2)");

        assertTrue(EfficiencySweepService.matchesTargetWorker(target, Coin.QUANTUS, "one", "quantus"));
        assertFalse(EfficiencySweepService.matchesTargetWorker(target, Coin.QUANTUS, "two", "quantus"));
        assertFalse(EfficiencySweepService.matchesTargetWorker(target, Coin.QUANTUS, "one", "etchash"));
    }

    @Test
    void unavailableReasonExplainsMissingDriverPermissionInsteadOfBlamingMinerConfiguration() {
        var gpu = gpu(false, "Power-Cap nicht schreibbar (Administrator/root und Treiber prüfen): Insufficient Permissions\nDetails");

        String reason = EfficiencySweepService.unavailableReason(List.of(gpu), true);

        assertTrue(reason.contains("Keine GPU-Leistungsgrenze ist schreibbar"));
        assertTrue(reason.contains("Administrator-/root-Rechten"));
        assertTrue(reason.contains("Insufficient Permissions"));
        assertFalse(reason.contains("\n"));
    }

    @Test
    void unavailableReasonDistinguishesMissingMinerFromMissingGpu() {
        assertEquals("Power-Limit-Test kann nicht gestartet werden: Keine GPU wurde erkannt",
                EfficiencySweepService.unavailableReason(List.of(), true));
        assertEquals("Power-Limit-Test kann nicht gestartet werden: SRBMiner-MULTI ist nicht installiert",
                EfficiencySweepService.unavailableReason(List.of(gpu(true, null)), false));
    }

    @Test
    void selectedTargetFailureSeparatesDriverAndInstallationFromRouteFailures() {
        var card = gpu("rtx-2080", "NVIDIA", 0);
        var blocked = new LocalGpuPowerService.Gpu("NVIDIA", 0, "rtx-2080", "RTX 2080", 100, 320,
                200, 200, 200, 25.0, "measured", false, "Insufficient Permissions", 300);
        assertEquals("benchmark_no_writable_selected_gpu",
                EfficiencySweepService.selectedTargetUnavailableReason(List.of(blocked), Set.of("rtx-2080"), true));
        assertEquals("benchmark_selected_miner_missing_for_sweep",
                EfficiencySweepService.selectedTargetUnavailableReason(List.of(card), Set.of("rtx-2080"), false));
        assertEquals("benchmark_no_selected_targets",
                EfficiencySweepService.selectedTargetUnavailableReason(List.of(card), Set.of("rtx-2080"), true));
    }

    private static de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu gpu(boolean writable, String error) {
        return new de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu(
                "NVIDIA", 0, "GPU-abc", "TITAN RTX", 100, 320, 100, 320,
                200, 25.0, "measured", writable, error);
    }

    private static EfficiencySweepService.RunStatus run(String id, SweepMode mode, List<Integer> limits) {
        return run(id, mode, limits, null);
    }

    private static EfficiencySweepService.RunStatus run(String id, SweepMode mode, List<Integer> limits,
                                                         Integer referenceBatch) {
        return new EfficiencySweepService.RunStatus(id, id, "TITAN RTX", "pearl", "PearlHash", mode,
                SweepRunState.QUEUED, null, limits, List.of(), 0, EfficiencySweepService.SAMPLES_PER_STEP, referenceBatch, "");
    }

    private static EfficiencySweepService.Target target(String id, String model, int min, int max, String algorithm) {
        var gpu = new de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu(
                "NVIDIA", 0, id, model, min, max, min, max, max, 25.0, "measured", true, null);
        return new EfficiencySweepService.Target("pearl", algorithm, gpu, null);
    }

    private static EfficiencySweepService.Target targetForCoin(String id, String coin, String algorithm) {
        var gpu = new de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu(
                "NVIDIA", id.equals("one") ? 0 : 1, id, "TITAN RTX", 100, 320, 100, 320,
                320, 25.0, "measured", true, null);
        return new EfficiencySweepService.Target(coin, algorithm, gpu, null);
    }

    private static EfficiencySweepService.SweepTask task(EfficiencySweepService.Target target, SweepMode mode) {
        return new EfficiencySweepService.SweepTask(target, target.coin() + "|" + target.algorithm(), mode, null);
    }

    private static EfficiencySweepService.RunStatus withStatus(EfficiencySweepService.RunStatus run, SweepRunState status) {
        return new EfficiencySweepService.RunStatus(run.id(), run.deviceId(), run.model(), run.coin(), run.algorithm(),
                run.mode(), status, run.limitWatts(), run.plannedLimits(), run.steps(), run.samples(),
                run.samplesRequired(), run.referenceBatch(), run.detail());
    }

    private static EfficiencySweepService.RunStatus identifiedRun(String device, SweepMode mode, List<Integer> limits) {
        var run = run(device, mode, limits);
        return new EfficiencySweepService.RunStatus(device + "|pearl|PearlHash", device, run.model(), run.coin(),
                run.algorithm(), run.mode(), run.status(), run.limitWatts(), run.plannedLimits(), run.steps(),
                run.samples(), run.samplesRequired(), run.referenceBatch(), run.detail());
    }

    private static GpuEfficiencyStore.Profile profile(String device, String coin, String algorithm, int watts) {
        var step = de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(watts, 1_000d, (double) watts, 60d, true, null);
        return new GpuEfficiencyStore.Profile(device, "TITAN RTX", coin, algorithm, watts, 1_000d,
                (double) watts, 1_000d / watts, Instant.now(), List.of(step));
    }
}
