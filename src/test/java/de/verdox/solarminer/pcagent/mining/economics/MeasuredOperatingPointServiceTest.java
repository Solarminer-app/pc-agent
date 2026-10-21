package de.verdox.solarminer.pcagent.mining.economics;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.lowlevel.*;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MeasuredOperatingPointServiceTest {
    final MinerFactory miners = mock(MinerFactory.class);
    final LocalGpuPowerService power = mock(LocalGpuPowerService.class);
    final GpuTuningService clocks = mock(GpuTuningService.class);
    final MeasuredOperatingPointService points = new MeasuredOperatingPointService(miners, power, clocks);
    final LocalGpuPowerService.Gpu gpu = new LocalGpuPowerService.Gpu("AMD", 0, "gpu", "Test", 80, 250, 80, 200, 150, 120d, "measured", true, null, 200);
    final Instant now = Instant.now();
    CoinHoppingService.Candidate raw() { return new CoinHoppingService.Candidate("pearl", 150, 120, 1000, 1, now, now, "SWEEP", 1, 2, "srbminer-multi", null, 1d); }
    CoinHoppingService.Candidate tuned(int core) {
        return raw().withTuning(new AutoTuningProfileStore.Profile("gpu", "Test", "AMD", "pearl", Coin.PEARL.assignmentAlgorithm(),
                "srbminer-multi", MinerFactory.minerVersionForSoftware("srbminer-multi"), core, 800, 150, 1000, 120, 32, 1000d/120, now));
    }
    MeasuredOperatingPointServiceTest() {
        when(clocks.restoreDriverDefaults("gpu")).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(clocks.apply(anyString(), any(), any(), any(), any())).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(power.setTotalPowerTarget(eq(150L), anyList())).thenReturn(true);
    }
    @Test void sameWattCapWithDifferentClocksRequiresRestartAndAppliesExactSettings() {
        assertTrue(points.apply(tuned(1200), gpu));
        assertTrue(points.matches("gpu", tuned(1200)));
        assertFalse(points.matches("gpu", tuned(1300)));
        assertTrue(points.needsRestart("gpu", tuned(1300)));
        verify(clocks).apply("gpu", 1200, 1200, 800, 800);
        verify(power).setTotalPowerTarget(150L, List.of(gpu));
        assertTrue(points.needsRestart("gpu", raw()));
    }
    @Test void failedTunedWriteRollsBackClocksEvenWhenPreviousPointWasRaw() {
        assertTrue(points.apply(raw(), gpu));
        when(power.setTotalPowerTarget(eq(150L), anyList())).thenReturn(false, true);
        assertFalse(points.apply(tuned(1200), gpu));
        assertEquals(raw(), points.current("gpu"));
        assertTrue(points.restore(raw(), gpu, 150));
        verify(clocks, times(3)).restoreDriverDefaults("gpu");
        assertTrue(points.matches("gpu", raw()));
    }
    @Test void unknownPreviousPointIsNotRecordedAfterFailedRestoration() {
        when(clocks.restoreDriverDefaults("gpu")).thenReturn(new GpuTuningService.ApplyResult(false, "gpu_clock_restore_failed"));
        assertFalse(points.restore(null, gpu, 150));
        assertNull(points.current("gpu"));
    }
    @Test void staleSoftwareVersionCannotBeApplied() {
        var old = new AutoTuningProfileStore.Profile("gpu", "Test", "AMD", "pearl", Coin.PEARL.assignmentAlgorithm(),
                "srbminer-multi", "old", 1200, 800, 150, 1000, 120, 32, 1000d/120, now);
        assertFalse(points.apply(raw().withTuning(old), gpu));
        verifyNoInteractions(clocks, power);
    }

    @Test void unknownClockStateRequiresAStopAndVerifiedDefaultsBeforeUsingABaseline() {
        assertFalse(points.matches("gpu", raw()));
        assertTrue(points.needsRestart("gpu", raw()));
        assertTrue(points.apply(raw(), gpu));
        var order = inOrder(clocks, power);
        order.verify(clocks).restoreDriverDefaults("gpu");
        order.verify(power).setTotalPowerTarget(150L, List.of(gpu));
        assertFalse(points.needsRestart("gpu", raw()));
    }

    @Test void failedDefaultResetCannotApplyOrCacheABaseline() {
        when(clocks.restoreDriverDefaults("gpu")).thenReturn(new GpuTuningService.ApplyResult(false, "gpu_clock_restore_failed"));
        assertFalse(points.apply(raw(), gpu));
        verifyNoInteractions(power);
        assertNull(points.current("gpu"));
    }
    @Test void failedNativeCleanupCannotWriteDriverClocksOrPower() {
        var miner = mock(GpuCoinMiner.class, withSettings().extraInterfaces(MinerNativeTuning.class));
        when(miners.gpuMinerAdapters(Coin.PEARL)).thenReturn(List.of(new MinerFactory.GpuMinerAdapter("peakminer", miner)));
        when(((MinerNativeTuning) miner).clearNativeTuning(gpu)).thenReturn(false);
        assertFalse(points.apply(raw(), gpu));
        verifyNoInteractions(clocks, power);
    }
}
