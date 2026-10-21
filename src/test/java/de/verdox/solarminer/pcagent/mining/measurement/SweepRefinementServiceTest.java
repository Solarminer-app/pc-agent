package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.*;
import de.verdox.solarminer.pcagent.miner.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SweepRefinementServiceTest {
    final AutoTuningSessionService tuner = mock(AutoTuningSessionService.class);
    final MinerFactory miners = mock(MinerFactory.class);
    final LocalGpuPowerService power = mock(LocalGpuPowerService.class);
    final GpuTuningService clocks = mock(GpuTuningService.class);
    final WorkerBenchmarkPolicyService policies = mock(WorkerBenchmarkPolicyService.class);
    final GpuCoinMiner miner = mock(GpuCoinMiner.class, withSettings().extraInterfaces(MinerNativeTuning.class));
    final LocalGpuPowerService.Gpu gpu = new LocalGpuPowerService.Gpu("AMD", 0, "gpu", "Test", 80, 200, 80, 180, 150, 120d, "measured", true, null, 200);
    final EfficiencySweepService.Target target = new EfficiencySweepService.Target("pearl", "PearlHash", gpu, null, "srbminer-multi");
    final GpuEfficiencyStore.Profile baseline = new GpuEfficiencyStore.Profile("gpu", "Test", "pearl", "PearlHash", 150, 1000d, 120d, 1000d/120, Instant.now(), List.of(
            de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(100, 600d, 90d, 60d, true, null),
            de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(150, 1000d, 120d, 60d, true, null),
            de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(180, 1100d, 170d, 60d, true, null)), "srbminer-multi");
    final SweepRefinementService service = new SweepRefinementService(tuner, miners, power, clocks, policies, mock(AutoTuningProfileStore.class));
    SweepRefinementServiceTest() {
        when(miners.gpuMiner(Coin.PEARL, "srbminer-multi")).thenReturn(miner);
        when(clocks.refreshCapabilities("gpu")).thenReturn(new GpuTuningService.Capabilities("gpu", true, false, false, 900, 1400, null, null, null));
        when(clocks.restoreDriverDefaults("gpu")).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(clocks.restoreDefaults("gpu")).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(miner.resumeGpu("AMD", 0)).thenReturn(true);
        when(miner.stopGpu("AMD", 0)).thenReturn(true);
        when(((MinerNativeTuning)miner).clearNativeTuning(gpu)).thenReturn(true);
        when(power.setSweepPowerTarget(150, gpu)).thenReturn(true);
        when(power.readTemperatureC(gpu)).thenReturn(60d);
        when(policies.temperatureSafe("gpu", 60d, 85)).thenReturn(true);
        when(policies.ceiling("gpu", 85)).thenReturn(85d);
        when(miner.workerStats(anyList())).thenReturn(List.of(new MinerStats.Worker(MinerStats.MinerStatus.MINING,
                "Test", "PearlHash", 1000d/1e12, 60, 150, 80, 150, 180, 120, List.of(), "GPU", "Test", "gpu", null, null, MinerStats.PoolTelemetry.unavailable())));
        service.begin(true, List.of(target), false);
    }
    @Test void refinementRetriesOneManagedHandoffOnOnlyTheSelectedPhysicalGpu() {
        when(miner.resumeGpu("AMD", 0)).thenReturn(false, true);
        when(miner.lastError()).thenReturn("Another miner already controls AMD:0");
        when(miners.gpuMinerAdapters(Coin.PEARL)).thenReturn(List.of(new MinerFactory.GpuMinerAdapter("srbminer-multi", miner)));
        var result = mock(AutoTuningSessionService.Session.class);
        when(result.phase()).thenReturn("auto_tuning_complete"); when(result.result()).thenReturn(mock(AutoTuningProfileStore.Profile.class));
        when(tuner.refineWithinSweep(eq("gpu"), eq("pearl"), eq(baseline), any())).thenReturn(result);
        assertFalse(service.refine(target, baseline, () -> false));
        assertEquals("COMPLETE", service.status().runs().getFirst().state());
        verify(miner, times(2)).resumeGpu("AMD", 0); verify(miner, never()).stopGpu("AMD", 1);
    }
    @Test void persistentExternalConflictIsBoundedAndRetainsTheStartCause() {
        when(miner.resumeGpu("AMD", 0)).thenReturn(false);
        when(miner.lastError()).thenReturn("Another miner already controls AMD:0");
        assertFalse(service.refine(target, baseline, () -> false));
        verify(miner, times(2)).resumeGpu("AMD", 0); verifyNoInteractions(tuner);
        assertEquals("Another miner already controls AMD:0", service.status().runs().getFirst().detail());
    }
    @Test void singleStablePowerPointIsAnInsufficientCurveNotASmallDriverRange() {
        var single = new GpuEfficiencyStore.Profile("gpu", "Test", "pearl", "PearlHash", 100, 600d, 90d, 600d/90,
                Instant.now(), List.of(baseline.steps().getFirst()), "srbminer-multi");
        assertFalse(service.refine(target, single, () -> false));
        assertEquals("SKIPPED", service.status().runs().getFirst().state());
        assertEquals("benchmark_optimization_insufficient_curve", service.status().runs().getFirst().detail());
        verifyNoInteractions(tuner); verify(miner, never()).resumeGpu(anyString(), anyInt());
    }

    @Test void automaticallyHandsOffAndStopsBeforeClearingNativeSettings() {
        var result = mock(AutoTuningSessionService.Session.class);
        when(result.phase()).thenReturn("auto_tuning_complete");
        when(result.result()).thenReturn(mock(AutoTuningProfileStore.Profile.class));
        when(tuner.refineWithinSweep(eq("gpu"), eq("pearl"), eq(baseline), any())).thenReturn(result);
        assertFalse(service.refine(target, baseline, () -> false));
        assertEquals("COMPLETE", service.status().runs().getFirst().state());
        var order = inOrder(power, miner, tuner, clocks);
        order.verify(power).setSweepPowerTarget(150, gpu);
        order.verify(miner).resumeGpu("AMD", 0);
        order.verify(tuner).refineWithinSweep(eq("gpu"), eq("pearl"), eq(baseline), any());
        order.verify(miner).stopGpu("AMD", 0);
        order.verify((MinerNativeTuning)miner).clearNativeTuning(gpu);
        order.verify(clocks).restoreDefaults("gpu");
        assertFalse(service.incomplete());
    }
    @Test void unavailableClockControlsLeaveStableSweepUsableAndReportActualCause() {
        when(clocks.refreshCapabilities("gpu")).thenReturn(new GpuTuningService.Capabilities("gpu", false, false, false, null, null, null, null, "unsupported"));
        assertFalse(service.refine(target, baseline, () -> false));
        assertEquals("SKIPPED", service.status().runs().getFirst().state());
        assertEquals("auto_tuning_selected_control_unavailable", service.status().runs().getFirst().detail());
        verifyNoInteractions(tuner);
        verify(miner, never()).resumeGpu(anyString(), anyInt());
        assertTrue(service.incomplete());
    }
    @Test void everyBaselineCurveUsesDriverDefaultsRatherThanAnOldRecoveryOffset() {
        when(miners.gpuMinerAdapters(Coin.PEARL)).thenReturn(List.of(new MinerFactory.GpuMinerAdapter("srbminer-multi", miner)));
        service.resetBeforeCurve(target);
        var order = inOrder(miner, clocks);
        order.verify((MinerNativeTuning) miner).clearNativeTuning(gpu);
        order.verify(clocks).restoreDriverDefaults("gpu");
        verify(clocks, never()).restoreDefaults("gpu");
    }
    @Test void missingCurveAndCancellationNeverStartTuning() {
        assertFalse(service.refine(target, null, () -> false));
        assertEquals("benchmark_optimization_no_stable_curve", service.status().runs().getFirst().detail());
        assertFalse(service.refine(target, baseline, () -> true));
        assertEquals("CANCELLED", service.status().runs().getFirst().state());
        verifyNoInteractions(tuner, miner);
    }
    @Test void unsafeTemperatureStopsBeforeTuningAndBlocksParentRestoration() {
        when(policies.temperatureSafe("gpu", 60d, 85)).thenReturn(false);
        assertTrue(service.refine(target, baseline, () -> false));
        assertEquals("auto_tuning_temperature_limit", service.status().runs().getFirst().detail());
        verify(miner).stopGpu("AMD", 0);
        verifyNoInteractions(tuner);
    }
    @Test void failedStopIsUnsafeAndNeverClearsNativeSettings() {
        var result = mock(AutoTuningSessionService.Session.class);
        when(result.phase()).thenReturn("auto_tuning_cancelled");
        when(tuner.refineWithinSweep(eq("gpu"), eq("pearl"), eq(baseline), any())).thenReturn(result);
        when(miner.stopGpu("AMD", 0)).thenReturn(false);
        assertTrue(service.refine(target, baseline, () -> false));
        assertEquals("benchmark_stop_failed", service.status().runs().getFirst().detail());
        verify((MinerNativeTuning)miner, never()).clearNativeTuning(any());
    }
    @Test void warmupUsesSafeCurveAnchorWhenSavedBestCapExceedsCurrentUserBound() {
        var bounded = new GpuEfficiencyStore.Profile("gpu", "Test", "pearl", "PearlHash", 250,
                2000d, 240d, 2000d/240, Instant.now(), baseline.steps(), "srbminer-multi");
        var result = mock(AutoTuningSessionService.Session.class);
        when(result.phase()).thenReturn("auto_tuning_complete");
        when(result.result()).thenReturn(mock(AutoTuningProfileStore.Profile.class));
        when(tuner.refineWithinSweep(eq("gpu"), eq("pearl"), eq(bounded), any())).thenReturn(result);
        assertFalse(service.refine(target, bounded, () -> false));
        assertEquals("COMPLETE", service.status().runs().getFirst().state());
        verify(power).setSweepPowerTarget(150, gpu);
        verify(power, never()).setSweepPowerTarget(250, gpu);
    }

}
