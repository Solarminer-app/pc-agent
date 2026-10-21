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
import de.verdox.solarminer.pcagent.mining.fee.FeeTransparencyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class CoinHoppingServiceTest {
    @TempDir Path directory;

    @Test
    void measuredSweepProfitRankingChangesWithElectricityRate() {
        Instant now = Instant.now();
        GpuEfficiencyStore sweeps = mock(GpuEfficiencyStore.class);
        when(sweeps.all()).thenReturn(List.of(
                profile("quantus", "QPoW (Poseidon2)", now, 100, 229_000_000, 100, true),
                profile("quantus", "QPoW (Poseidon2)", now, 200, 293_000_000, 199, true)));
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        LocalGpuPowerService.Gpu gpu = mock(LocalGpuPowerService.Gpu.class);
        when(gpu.deviceId()).thenReturn("gpu-1");
        when(gpu.supportsDynamicPowerScaling()).thenReturn(true);
        when(gpu.minWatts()).thenReturn(100);
        when(gpu.maxWatts()).thenReturn(300);
        when(power.snapshot()).thenReturn(List.of(gpu));
        EconomicPlanningService planning = mock(EconomicPlanningService.class);
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, now,
                List.of(new EconomicPlanningService.WorkerCapability("gpu-1", "GPU", "Test", "quantus", false,
                        "FIXED", List.of(ready("quantus")))), null));
        MarketDataService market = mock(MarketDataService.class);
        when(market.forecastForProfile(org.mockito.ArgumentMatchers.eq("quantus"), anyDouble()))
                .thenAnswer(invocation -> forecast("quantus", invocation.<Double>getArgument(1) * 1.32 / 229_000_000, now));
        when(market.toUsd(0.31, "EUR")).thenReturn(0.31);
        when(market.toUsd(0.02, "EUR")).thenReturn(0.02);
        EnergyJournalService energy = mock(EnergyJournalService.class);
        EnergyJournalService.Overview overview = mock(EnergyJournalService.Overview.class);
        when(energy.overview()).thenReturn(overview);
        when(overview.settings()).thenReturn(new EnergyJournalService.Settings(new BigDecimal("0.31"), "EUR"));
        CoinHoppingService service = new CoinHoppingService(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("profit-ranking.json").toString(), sweeps, mock(MiningPerformanceProfileStore.class),
                market, planning, mock(WorkerAssignmentService.class), power,
                mock(BenchmarkSessionService.class), mock(EfficiencySweepService.class), mock(LocalRunLock.class),
                new CoinHoppingLedger(new ObjectMapper().findAndRegisterModules(),
                        directory.resolve("profit-ledger.json").toString()), energy);

        var points = service.candidates("gpu-1", 0);
        assertEquals(2, points.size());
        var low = points.stream().filter(point -> point.limitWatts() == 100).findFirst().orElseThrow();
        var high = points.stream().filter(point -> point.limitWatts() == 200).findFirst().orElseThrow();
        assertTrue(low.hashrateHps() / low.measuredWatts() > high.hashrateHps() / high.measuredWatts());
        assertEquals(1.32 - 2.4 * 0.31, low.profitUsdPerDay(), 0.000001);
        assertEquals(high.usdPerDay() - 4.776 * 0.31, high.profitUsdPerDay(), 0.000001);
        assertTrue(low.profitUsdPerDay() > high.profitUsdPerDay());

        when(overview.settings()).thenReturn(new EnergyJournalService.Settings(new BigDecimal("0.02"), "EUR"));
        points = service.candidates("gpu-1", 0);
        low = points.stream().filter(point -> point.limitWatts() == 100).findFirst().orElseThrow();
        high = points.stream().filter(point -> point.limitWatts() == 200).findFirst().orElseThrow();
        assertTrue(high.profitUsdPerDay() > low.profitUsdPerDay());
    }

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
        EnergyJournalService energy = mock(EnergyJournalService.class);
        EnergyJournalService.Overview overview = mock(EnergyJournalService.Overview.class);
        when(energy.overview()).thenReturn(overview);
        when(overview.settings()).thenReturn(new EnergyJournalService.Settings(new BigDecimal("0.30"), "EUR"));
        when(market.toUsd(0.30, "EUR")).thenReturn(0.30);
        CoinHoppingService service = new CoinHoppingService(new ObjectMapper().findAndRegisterModules(), directory.resolve("hopping.json").toString(),
                profiles, performance, market, planning, mock(WorkerAssignmentService.class), power,
                mock(BenchmarkSessionService.class), mock(EfficiencySweepService.class), mock(LocalRunLock.class),
                new CoinHoppingLedger(new ObjectMapper().findAndRegisterModules(), directory.resolve("ledger.json").toString()),
                energy);

        CoinHoppingService.View view = service.view("gpu-1", 180);
        assertEquals(List.of("quantus", "pearl"), view.candidates().stream().map(CoinHoppingService.Candidate::coin).toList());
        assertEquals("quantus", view.recommendedCoin());
        assertEquals("LIVE", view.candidates().get(1).source());
        assertTrue(view.candidates().getFirst().usdPerKwh() > view.candidates().get(1).usdPerKwh());
        assertEquals(3, service.view("gpu-1", 0).candidates().size());

        double withoutKnownFees = service.candidates("gpu-1", 180).getFirst().profitUsdPerDay();
        assertEquals(10 - 200 * 24.0 / 1000.0 * 0.30, withoutKnownFees, 0.000001);
        FeeTransparencyService fees = mock(FeeTransparencyService.class);
        when(fees.knownFeePercentage(anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(25.0);
        service.setFeeTransparency(fees);
        var withKnownFees = service.candidates("gpu-1", 180).getFirst();
        assertEquals(25.0, withKnownFees.feePercentage());
        assertEquals(withoutKnownFees, withKnownFees.profitUsdPerDay());

        when(overview.settings()).thenReturn(null);
        assertNull(service.candidates("gpu-1", 180).getFirst().profitUsdPerDay());
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
        when(assignments.applyEconomicAssignment("gpu-1", "quantus", null)).thenReturn(true);
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
        verify(assignments).applyEconomicAssignment("gpu-1", "quantus", null);
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


    @Test
    void confirmedClockPointsJoinBaselineRankingWithoutCollapsingSameWattSettings() {
        Instant now = Instant.now();
        var json = new ObjectMapper().findAndRegisterModules();
        var store = new de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore(json, directory.resolve("tuned.json").toString());
        String version = de.verdox.solarminer.pcagent.miner.MinerFactory.minerVersionForSoftware("srbminer-multi");
        for (int watts : List.of(100, 150, 200)) for (int core : List.of(1000, 1300)) {
            assertTrue(store.appendMeasurement(new de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore.Measurement(
                    "confirmed", now, "gpu-1", "Test", "NVIDIA", "quantus", de.verdox.solarminer.pcagent.coin.Coin.QUANTUS.assignmentAlgorithm(),
                    "srbminer-multi", version, core, 800, watts, "RESULT", core == 1300 ? 140d : 100d, watts - 10d, 60d, core, 800, 32, 5L, 0L, true, null)));
        }
        var sweeps = mock(GpuEfficiencyStore.class);
        when(sweeps.all()).thenReturn(List.of(profile("quantus", "QPoW (Poseidon2)", now, 100, 80, 90, true)));
        var power = mock(LocalGpuPowerService.class);
        when(power.snapshot()).thenReturn(List.of(new LocalGpuPowerService.Gpu("NVIDIA", 0, "gpu-1", "Test", 80, 300, 80, 200, 150, 120d, "measured", true, null, 200)));
        var planning = mock(EconomicPlanningService.class);
        when(planning.capabilities()).thenReturn(new EconomicPlanningService.Capabilities(1, now, List.of(
                new EconomicPlanningService.WorkerCapability("gpu-1", "GPU", "Test", "quantus", false, "FIXED", List.of(ready("quantus")))), null));
        var market = mock(MarketDataService.class);
        when(market.forecastForProfile(anyString(), anyDouble())).thenAnswer(call -> forecast("quantus", call.<Double>getArgument(1) / 100, now));
        when(market.toUsd(0.1, "USD")).thenReturn(0.1);
        var energy = mock(EnergyJournalService.class);
        var overview = mock(EnergyJournalService.Overview.class);
        when(energy.overview()).thenReturn(overview);
        when(overview.settings()).thenReturn(new EnergyJournalService.Settings(new BigDecimal("0.1"), "USD"));
        var service = new CoinHoppingService(json, directory.resolve("ranking.json").toString(), sweeps, mock(MiningPerformanceProfileStore.class),
                market, planning, mock(WorkerAssignmentService.class), power, mock(BenchmarkSessionService.class), mock(EfficiencySweepService.class),
                new LocalRunLock(), new CoinHoppingLedger(json, directory.resolve("ranking-ledger.json").toString()), energy);
        service.setTunedProfiles(store);
        var points = service.candidates("gpu-1", 0);
        assertEquals(7, points.size());
        assertEquals(3, points.stream().filter(row -> row.limitWatts() == 100).count());
        assertEquals(7, points.stream().map(CoinHoppingService.Candidate::pointKey).distinct().count());
        assertEquals("AUTO_TUNING", points.getFirst().source());
        assertEquals(1300, points.getFirst().tunedProfile().coreMhz());
        assertEquals(version, points.getFirst().tunedProfile().softwareVersion());
        var policies = mock(de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService.class);
        when(policies.ceiling("gpu-1", 85)).thenReturn(59d);
        service.setWorkerPolicies(policies);
        assertTrue(service.candidates("gpu-1", 0).stream().noneMatch(row -> row.tunedProfile() != null));
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
