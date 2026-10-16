package de.verdox.solarminer.pcagent.mining.economics;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.EfficiencySweepService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class CoinHoppingServiceTest {
    @TempDir Path directory;

    @Test
    void configuredThresholdAndCooldownControlBoundary() {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        assertTrue(CoinHoppingService.worthHop(1, 1.08, 5));
        org.junit.jupiter.api.Assertions.assertFalse(CoinHoppingService.worthHop(1, 1.08, 10));
        org.junit.jupiter.api.Assertions.assertFalse(CoinHoppingService.cooldownExpired(now.minusSeconds(60 * 29), now, 30));
        assertTrue(CoinHoppingService.cooldownExpired(now.minusSeconds(60 * 30), now, 30));
    }

    @Test
    void comparesOnlyStableExactTargetWithFreshMarketAndReadyRoute() {
        GpuEfficiencyStore profiles = mock(GpuEfficiencyStore.class);
        MiningPerformanceProfileStore performance = mock(MiningPerformanceProfileStore.class);
        MarketDataService market = mock(MarketDataService.class);
        EconomicPlanningService planning = mock(EconomicPlanningService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        LocalGpuPowerService.Gpu gpu = mock(LocalGpuPowerService.Gpu.class);
        when(gpu.deviceId()).thenReturn("gpu-1");
        when(gpu.supportsDynamicPowerScaling()).thenReturn(true);
        when(gpu.minWatts()).thenReturn(100);
        when(gpu.maxWatts()).thenReturn(300);
        when(power.snapshot()).thenReturn(List.of(gpu));
        Instant now = Instant.now();
        when(profiles.all()).thenReturn(List.of(
                profile("quantus", "QPoW (Poseidon2)", now, 180, 100d, 200d, true),
                profile("ravencoin", "kawpow", now, 180, 100d, 200d, false),
                profile("decred", "blake3_decred", now, 170, 100d, 200d, true)));
        when(performance.all()).thenReturn(List.of(new MiningPerformanceProfileStore.Profile("gpu-1", "GPU", "Test",
                "pearl", "pearlhash", 100d, 200, 12, now, "LIVE", 180)));
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, now,
                List.of(new EconomicPlanningService.WorkerCapability("gpu-1", "GPU", "Test", "pearl", false, "FIXED",
                        List.of(ready("pearl"), ready("quantus"), ready("ravencoin"), ready("decred")))), null));
        when(market.forecastForProfile(anyString(), anyDouble())).thenAnswer(invocation -> {
            String coin = invocation.getArgument(0);
            return forecast(coin, coin.equals("quantus") ? 10d : 5d, now);
        });
        CoinHoppingService service = new CoinHoppingService(new ObjectMapper().findAndRegisterModules(), directory.resolve("hopping.json").toString(),
                profiles, performance, market, planning, mock(WorkerAssignmentService.class), power,
                mock(BenchmarkSessionService.class), mock(EfficiencySweepService.class), mock(LocalRunLock.class),
                new CoinHoppingLedger(new ObjectMapper().findAndRegisterModules(), directory.resolve("ledger.json").toString()),
                mock(EnergyJournalService.class));

        CoinHoppingService.View view = service.view("gpu-1", 180);
        assertEquals(List.of("quantus", "pearl"), view.candidates().stream().map(CoinHoppingService.Candidate::coin).toList());
        assertEquals("quantus", view.recommendedCoin());
        assertEquals("LIVE", view.candidates().get(1).source());
        assertTrue(view.candidates().getFirst().usdPerKwh() > view.candidates().get(1).usdPerKwh());
        assertEquals(3, service.view("gpu-1", 0).candidates().size());
    }

    @Test
    void enabledHoppingSwitchesOnlyAnActiveWorkerAfterFivePercentGain() {
        GpuEfficiencyStore profiles = mock(GpuEfficiencyStore.class);
        MarketDataService market = mock(MarketDataService.class);
        EconomicPlanningService planning = mock(EconomicPlanningService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        BenchmarkSessionService benchmark = mock(BenchmarkSessionService.class);
        EfficiencySweepService sweep = mock(EfficiencySweepService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        Instant now = Instant.now();
        when(profiles.all()).thenReturn(List.of(profile("pearl", "pearlhash", now, 180, 100d, 200d, true),
                profile("quantus", "QPoW (Poseidon2)", now, 150, 100d, 160d, true)));
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, now,
                List.of(new EconomicPlanningService.WorkerCapability("gpu-1", "GPU", "Test", "pearl", false, "FIXED",
                        List.of(ready("pearl"), ready("quantus")))), null));
        when(market.forecastForProfile(anyString(), anyDouble())).thenAnswer(invocation -> {
            String coin = invocation.getArgument(0);
            return forecast(coin, coin.equals("quantus") ? 10d : 5d, now);
        });
        LocalGpuPowerService.Gpu gpu = mock(LocalGpuPowerService.Gpu.class);
        when(gpu.deviceId()).thenReturn("gpu-1");
        when(gpu.supportsDynamicPowerScaling()).thenReturn(true);
        when(gpu.minWatts()).thenReturn(100);
        when(gpu.maxWatts()).thenReturn(300);
        when(gpu.currentPowerLimitWatts()).thenReturn(180);
        when(power.snapshot()).thenReturn(List.of(gpu));
        when(power.setTotalPowerTarget(150, List.of(gpu))).thenReturn(true);
        when(lock.tryBegin("coin-hopping")).thenReturn(true);
        when(assignments.applyEconomicAssignment("gpu-1", "quantus")).thenReturn(true);
        when(assignments.start("gpu-1")).thenReturn(true);
        WorkerAssignmentService.WorkerView view = mock(WorkerAssignmentService.WorkerView.class);
        MinerStats.Worker worker = mock(MinerStats.Worker.class);
        when(view.deviceId()).thenReturn("gpu-1");
        when(view.telemetry()).thenReturn(worker);
        when(worker.miningStatus()).thenReturn(MinerStats.MinerStatus.MINING);
        when(assignments.workers()).thenReturn(List.of(view));
        when(benchmark.status()).thenReturn(mock(BenchmarkSessionService.Session.class));
        when(sweep.status()).thenReturn(mock(EfficiencySweepService.Session.class));
        EnergyJournalService energy = mock(EnergyJournalService.class);
        EnergyJournalService.Overview overview = mock(EnergyJournalService.Overview.class);
        EnergyJournalService.Session active = mock(EnergyJournalService.Session.class);
        when(active.deviceId()).thenReturn("gpu-1");
        when(active.coin()).thenReturn("pearl");
        when(active.startedAt()).thenReturn(now.minusSeconds(120));
        when(overview.activeSessions()).thenReturn(List.of(active));
        when(energy.overview()).thenReturn(overview);
        CoinHoppingLedger ledger = new CoinHoppingLedger(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("ledger2.json").toString());
        CoinHoppingService service = new CoinHoppingService(new ObjectMapper().findAndRegisterModules(), directory.resolve("enabled.json").toString(),
                profiles, mock(MiningPerformanceProfileStore.class), market, planning, assignments, power, benchmark, sweep, lock,
                ledger, energy);
        service.configure(new CoinHoppingService.Settings(true, "gpu-1", 0, 30, 5));
        assertEquals(List.of("quantus:150", "pearl:180"), service.view().candidates().stream()
                .map(candidate -> candidate.coin() + ":" + candidate.limitWatts()).toList());

        service.evaluate();

        assertEquals("coin_hopping_switched", service.view().lastOutcome());
        verify(assignments).applyEconomicAssignment("gpu-1", "quantus");
        verify(power).setTotalPowerTarget(150, List.of(gpu));
        verify(assignments).start("gpu-1");
        verify(lock).end();
        assertEquals("coin_hopping_switched", service.view().lastOutcome());
        assertEquals(0, service.view().settings().targetWatts());
        assertEquals(150, ledger.summary().actions().getFirst().targetWatts());
        CoinHoppingService restarted = new CoinHoppingService(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("enabled.json").toString(), profiles, mock(MiningPerformanceProfileStore.class), market, planning, assignments, power,
                benchmark, sweep, lock, ledger, energy);
        assertTrue(restarted.view().settings().enabled());
        assertEquals(service.view().lastSwitchAt(), restarted.view().lastSwitchAt());
    }

    private static EconomicPlanningService.CoinCapability ready(String coin) {
        return new EconomicPlanningService.CoinCapability(coin, coin, true, true, null, null);
    }

    private static GpuEfficiencyStore.Profile profile(String coin, String algorithm, Instant testedAt, int limit,
                                                       double rate, double watts, boolean stable) {
        return new GpuEfficiencyStore.Profile("gpu-1", "Test", coin, algorithm, stable ? limit : null,
                stable ? rate : null, stable ? watts : null, stable ? rate / watts : null, testedAt,
                List.of(new GpuEfficiencyStore.StepResult(limit, rate, watts, 65d, stable, null)));
    }

    private static MarketDataService.Forecast forecast(String coin, double usdPerDay, Instant updatedAt) {
        return new MarketDataService.Forecast(coin, coin, true, null, 100d, 1000d, 1d, 60d, 1d,
                1d, usdPerDay, usdPerDay, updatedAt, false, List.of("currency.solarminer.app"));
    }
}
