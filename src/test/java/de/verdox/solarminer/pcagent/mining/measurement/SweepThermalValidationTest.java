package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongToDoubleFunction;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SweepThermalValidationTest {
    GpuEfficiencyStore.StepResult measure(LongToDoubleFunction temperature) {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA",2,"gpu","TITAN RTX",100,280,100,280,205,198d,"measured",true,null);
        var power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(gpu));
        var seconds = new AtomicLong(-5);
        when(power.readTemperatureC(gpu)).thenAnswer(call -> temperature.applyAsDouble(seconds.get()));
        var miner = mock(GpuCoinMiner.class);
        when(miner.resumeGpu("NVIDIA",2)).thenReturn(true);
        when(miner.workerStats(anyList())).thenReturn(List.of(new MinerStats.Worker(MinerStats.MinerStatus.MINING,
                "TITAN RTX","PearlHash",81d,60,205,100,280,280,198,List.of(),"GPU","TITAN RTX","gpu",null,null,
                MinerStats.PoolTelemetry.unavailable())));
        var miners = mock(MinerFactory.class);
        when(miners.gpuMiner(Coin.PEARL,"peakminer")).thenReturn(miner);
        var service = new EfficiencySweepService(miners,power,mock(MinerConsoleService.class),mock(PayoutDefaultsService.class),
                mock(ProxyConfigurationService.class),mock(GpuEfficiencyStore.class),mock(BenchmarkSharingService.class),
                new LocalRunLock(),15,84,.1,240);
        service.setThermalSampling(0, () -> TimeUnit.SECONDS.toNanos(seconds.addAndGet(5)));
        return service.measureStep(new EfficiencySweepService.Target("pearl","PearlHash",gpu,null,"peakminer"),205,List.of());
    }
    @Test void theReportedColdStartCannotBecomeAStablePointBeforeItsLateOverheat() {
        var result = measure(seconds -> seconds <= 60 ? 59 + seconds / 6d
                : seconds <= 120 ? 69 + (seconds - 60) / 6d : Math.min(84,79+(seconds-120)/15d));
        assertFalse(result.stable()); assertNull(result.thermalValidation());
        assertEquals(84d,result.maxTemperatureC());
    }
    @Test void aSteadyLoadedPointCarriesPersistableEvidenceAndRealMeasurements() {
        var result = measure(seconds -> 78);
        assertTrue(result.stable()); assertTrue(result.thermalValidation().safeFor(84));
        assertTrue(result.thermalValidation().loadedSeconds() >= 180);
        assertEquals(198d,result.avgPowerWatts()); assertEquals(81e12,result.medianHashrateHs());
    }
    @Test void stableButInsufficientHeadroomTimesOutWithoutApprovingThePoint() {
        var result = measure(seconds -> 82);
        assertFalse(result.stable()); assertNull(result.thermalValidation());
        assertEquals("benchmark_temperature_not_settled",result.note());
    }
}
