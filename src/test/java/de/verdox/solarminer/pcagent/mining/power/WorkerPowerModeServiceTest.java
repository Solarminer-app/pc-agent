package de.verdox.solarminer.pcagent.mining.power;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningSelectionStore;
import de.verdox.solarminer.pcagent.mining.economics.AutoTuningProfileEconomicsService;
import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerPowerModeServiceTest {
    @TempDir Path directory;

    @Test
    void uncalibratedModeStartsAtSavedManualTargetBelowDriverStockCap() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentControlSettingsService controls = new AgentControlSettingsService(json,
                directory.resolve("baseline-control.json").toString());
        assertTrue(controls.setWorkerPowerMode("GPU-baseline", "AUTO_TUNING"));
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        var card = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-baseline", "Test", 80, 220,
                80, 150, 150, 100.0, "measured", true, null, 200);
        when(gpus.discover()).thenReturn(List.of(card));
        when(gpus.configuredPowerLimitWatts(card)).thenReturn(150);
        when(gpus.setTotalPowerTarget(150, List.of(card))).thenReturn(true);
        GpuTuningService tuning = mock(GpuTuningService.class);
        when(tuning.restoreDefaults("GPU-baseline")).thenReturn(new GpuTuningService.ApplyResult(true, null));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, gpus,
                new GpuEfficiencyStore(json, directory.resolve("baseline-sweeps.json").toString()),
                new MiningPerformanceProfileStore(json, directory.resolve("baseline-bench.json").toString()), 80);
        modes.setAutoTuning(new AutoTuningProfileStore(json, directory.resolve("baseline-profiles.json").toString()), tuning);

        assertTrue(modes.needsAutoTuningCalibration("GPU-baseline", "pearl"));
        assertEquals(150, modes.select("GPU-baseline", "pearl").watts());
        assertNull(modes.apply("GPU-baseline", "pearl").error());
        verify(gpus).setTotalPowerTarget(150, List.of(card));
    }

    @Test
    void autoTuningModeRequiresMatchingProfileAndAppliesClockAndPowerSettings() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        LocalGpuPowerService.Gpu card = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test", 80, 250,
                100, 220, 200, 150.0, "measured", true, null, 220);
        when(gpus.discover()).thenReturn(List.of(card));
        when(gpus.configuredPowerLimitWatts(card)).thenReturn(200);
        when(gpus.setAutoTuningPowerTarget(150, card)).thenReturn(true);
        when(gpus.setAutoTuningPowerTarget(100, card)).thenReturn(true);
        AutoTuningProfileStore profiles = new AutoTuningProfileStore(json, directory.resolve("auto-profiles.json").toString());
        var profile = de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.profile(new AutoTuningProfileStore.Profile("GPU-a", "Test", "NVIDIA", "pearl", "PearlHash",
                "srbminer-multi", 1200, 800, 150, 100.0, 125, 32, .8, Instant.now()));
        assertTrue(profiles.saveMatrix(List.of(
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.profile(new AutoTuningProfileStore.Profile("GPU-a", "Test", "NVIDIA", "pearl", "PearlHash",
                        "srbminer-multi", 1100, 700, 100, 80.0, 90, 32, .89, Instant.now())),
                profile,
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.profile(new AutoTuningProfileStore.Profile("GPU-a", "Test", "NVIDIA", "pearl", "PearlHash",
                        "srbminer-multi", 1300, 900, 200, 120.0, 180, 32, .67, Instant.now())))));
        AgentControlSettingsService controls = new AgentControlSettingsService(json, directory.resolve("auto-control.json").toString());
        assertTrue(controls.setWorkerPowerMode("GPU-a", "AUTO_TUNING"));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, gpus,
                new GpuEfficiencyStore(json, directory.resolve("auto-sweeps.json").toString()),
                new MiningPerformanceProfileStore(json, directory.resolve("auto-bench.json").toString()), 80);
        de.verdox.solarminer.pcagent.miner.MinerFactory miners = mock(de.verdox.solarminer.pcagent.miner.MinerFactory.class);
        when(miners.minerSoftwareForWorker(any(), eq("GPU-a"))).thenReturn("srbminer-multi");
        modes.setMinerFactory(miners);
        GpuTuningService tuning = mock(GpuTuningService.class);
        when(tuning.apply("GPU-a", 1200, 1200, 800, 800)).thenReturn(new GpuTuningService.ApplyResult(true, null));
        when(tuning.apply("GPU-a", 1100, 1100, 700, 700)).thenReturn(new GpuTuningService.ApplyResult(true, null));
        modes.setAutoTuning(profiles, tuning);

        assertNull(modes.selectForMode("GPU-a", "pearl", "AUTO_TUNING").error());
        assertEquals(100, modes.selectForMode("GPU-a", "pearl", "AUTO_TUNING").watts());
        assertNull(modes.apply("GPU-a", "pearl").error());
        assertNull(modes.applyForBudget("GPU-a", "pearl", 175).error());
        verify(gpus).setAutoTuningPowerTarget(100, card);
        verify(tuning).apply("GPU-a", 1200, 1200, 800, 800);
        verify(gpus).setAutoTuningPowerTarget(150, card);
        assertEquals(150, modes.appliedProfile("GPU-a").powerLimitWatts());
        var safety = new de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore(json, directory.resolve("auto-safety.json").toString());
        modes.setPointSafety(safety);
        var policies = new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService(json, directory.resolve("auto-policy.json").toString());
        policies.save("GPU-a", new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 84));
        modes.setWorkerPolicies(policies);
        var winner = profiles.matrix("GPU-a", "pearl", "srbminer-multi", null, "Test", "NVIDIA", "PearlHash").stream()
                .filter(row -> row.powerLimitWatts() == 100).findFirst().orElseThrow();
        safety.reject("GPU-a", de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore.profileKey(winner), 84d, 84d);
        assertEquals(150, modes.selectForMode("GPU-a", "pearl", "AUTO_TUNING").watts(), "A rejected tuned point must yield to another calibrated tier");
        assertFalse(modes.needsAutoTuningCalibration("GPU-a", "pearl"), "A valid fallback tier does not require another calibration");
    }

    @Test
    void profitGoalFallsBackToEfficiencyWhileMarketDataIsUnavailable() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        LocalGpuPowerService.Gpu card = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-profit", "Test", 80, 250,
                100, 220, 200, 150.0, "measured", true, null, 220);
        when(gpus.discover()).thenReturn(List.of(card));
        when(gpus.configuredPowerLimitWatts(card)).thenReturn(200);
        AutoTuningProfileStore profiles = new AutoTuningProfileStore(json, directory.resolve("profit-profiles.json").toString());
        assertTrue(profiles.saveMatrix(List.of(
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.profile(new AutoTuningProfileStore.Profile("GPU-profit", "Test", "NVIDIA", "pearl", "PearlHash",
                        "srbminer-multi", 1100, 700, 100, 80.0, 90, 32, .89, Instant.now())),
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.profile(new AutoTuningProfileStore.Profile("GPU-profit", "Test", "NVIDIA", "pearl", "PearlHash",
                        "srbminer-multi", 1200, 800, 150, 100.0, 125, 32, .8, Instant.now())),
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.profile(new AutoTuningProfileStore.Profile("GPU-profit", "Test", "NVIDIA", "pearl", "PearlHash",
                        "srbminer-multi", 1300, 900, 200, 120.0, 180, 32, .67, Instant.now())))));
        AgentControlSettingsService controls = new AgentControlSettingsService(json, directory.resolve("profit-control.json").toString());
        assertTrue(controls.setWorkerPowerMode("GPU-profit", "AUTO_TUNING"));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, gpus,
                new GpuEfficiencyStore(json, directory.resolve("profit-sweeps.json").toString()),
                new MiningPerformanceProfileStore(json, directory.resolve("profit-bench.json").toString()), 80);
        de.verdox.solarminer.pcagent.miner.MinerFactory miners = mock(de.verdox.solarminer.pcagent.miner.MinerFactory.class);
        when(miners.minerSoftwareForWorker(any(), eq("GPU-profit"))).thenReturn("srbminer-multi");
        modes.setMinerFactory(miners);
        AutoTuningSelectionStore selections = new AutoTuningSelectionStore();
        assertTrue(selections.set("GPU-profit", new AutoTuningSelectionStore.Selection(true, true, true,
                AutoTuningSelectionStore.Goal.PROFIT_PER_DAY)));
        AutoTuningProfileEconomicsService economics = mock(AutoTuningProfileEconomicsService.class);
        when(economics.compare(anyString(), anyString(), any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(new AutoTuningProfileEconomicsService.Comparison(null, null));
        modes.setAutoTuning(profiles, mock(GpuTuningService.class), selections, economics);

        var choice = modes.selectForMode("GPU-profit", "pearl", "AUTO_TUNING");
        assertNull(choice.error());
        assertEquals(100, choice.watts());
    }

    @Test
    void choosesBestMeasuredHashesPerWattWithinGpuBoundsAndPersistsMode() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentControlSettingsService controls = new AgentControlSettingsService(json, directory.resolve("control.json").toString());
        assertTrue(controls.setWorkerAssignment("GPU-a", "pearl", "srbminer-multi"));
        assertTrue(controls.setWorkerPowerMode("GPU-a", "EFFICIENCY"));
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        LocalGpuPowerService.Gpu card = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test", 80, 250,
                100, 220, 200, 150.0, "measured", true, null);
        when(gpus.discover()).thenReturn(List.of(card));
        GpuEfficiencyStore sweeps = new GpuEfficiencyStore(json, directory.resolve("sweeps.json").toString());
        sweeps.save(new GpuEfficiencyStore.Profile("GPU-a", "Test", "pearl", "PearlHash", 150, 120.0,
                150.0, 0.8, Instant.now(), List.of(
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(90, 120.0, 90.0, 60.0, true, null),
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(150, 120.0, 150.0, 60.0, true, null),
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(130, 80.0, 130.0, 60.0, true, null),
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(180, 180.0, 180.0, 90.0, false, null))));
        MiningPerformanceProfileStore benchmarks = new MiningPerformanceProfileStore(json, directory.resolve("bench.json").toString());
        benchmarks.save(new MiningPerformanceProfileStore.Profile("GPU-a", "GPU", "Test", "pearl", "PearlHash",
                160.0, 160, 12, Instant.now(), "BENCHMARK", 160));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, gpus, sweeps, benchmarks, 80);

        assertEquals(150, modes.select("GPU-a", "pearl").watts());
        when(gpus.setTotalPowerTarget(150, List.of(card))).thenReturn(true);
        assertNull(modes.apply("GPU-a", "pearl").error());
        verify(gpus).setTotalPowerTarget(150, List.of(card));
        assertEquals("EFFICIENCY", new AgentControlSettingsService(json, directory.resolve("control.json").toString())
                .get().powerModeFor("GPU-a"));
        var policies = new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService(json, directory.resolve("select-policy.json").toString());
        policies.save("GPU-a", new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 84));
        modes.setWorkerPolicies(policies);
        var safety = new de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore(json, directory.resolve("select-safety.json").toString());
        modes.setPointSafety(safety);
        safety.reject("GPU-a", "pearl|null|150|BASELINE", 84d, 84d);
        assertEquals(130, modes.select("GPU-a", "pearl").watts());
        when(gpus.configuredPowerLimitWatts(card)).thenReturn(150);
        controls.setWorkerPowerMode("GPU-a", "MANUAL");
        assertEquals("power_mode_no_validated_point", modes.apply("GPU-a", "pearl").error());
        controls.setWorkerPowerMode("GPU-a", "EFFICIENCY");
        policies.save("GPU-a", new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 86));
        assertEquals(150, modes.select("GPU-a", "pearl").watts());
    }

    @Test
    void rejectsAutomaticModeWithoutMeasurements() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentControlSettingsService controls = new AgentControlSettingsService(json, directory.resolve("empty-control.json").toString());
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        when(gpus.discover()).thenReturn(List.of(new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test",
                80, 250, 100, 220, 200, 150.0, "measured", true, null)));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, gpus,
                new GpuEfficiencyStore(json, directory.resolve("empty-sweeps.json").toString()),
                new MiningPerformanceProfileStore(json, directory.resolve("empty-bench.json").toString()), 80);
        assertEquals("power_mode_no_validated_point", modes.selectForMode("GPU-a", "pearl", "EFFICIENCY").error());
        verify(gpus, never()).setTotalPowerTarget(anyLong(), anyList());
    }

    @Test
    void cpuUsesMeasuredThreadTargetAndCannotEnterCoinSwitchingMode() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentControlSettingsService controls = new AgentControlSettingsService(json, directory.resolve("cpu-control.json").toString());
        MiningPerformanceProfileStore benchmarks = new MiningPerformanceProfileStore(json, directory.resolve("cpu-bench.json").toString());
        benchmarks.save(new MiningPerformanceProfileStore.Profile("cpu", "CPU", "Test CPU", "monero", "RandomX",
                100.0, 100, 12, Instant.now(), "BENCHMARK", 100));
        benchmarks.save(new MiningPerformanceProfileStore.Profile("cpu", "CPU", "Test CPU", "monero", "RandomX",
                140.0, 200, 12, Instant.now(), "BENCHMARK", 200));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, mock(LocalGpuPowerService.class),
                new GpuEfficiencyStore(json, directory.resolve("cpu-sweeps.json").toString()), benchmarks, 80);

        assertEquals(100, modes.selectForMode("cpu", "monero", "EFFICIENCY").watts());
        assertEquals("power_mode_requires_gpu_coin", modes.selectForMode("cpu", "monero", "COIN_REVENUE_PER_KWH").error());
    }

    @Test
    void maximumHashrateChoosesFastestCoolStablePointAndRequiresLiveTemperature() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentControlSettingsService controls = new AgentControlSettingsService(json, directory.resolve("max-control.json").toString());
        LocalGpuPowerService gpus = mock(LocalGpuPowerService.class);
        LocalGpuPowerService.Gpu card = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test", 80, 250,
                100, 220, 200, 150.0, "measured", true, null);
        when(gpus.discover()).thenReturn(List.of(card));
        when(gpus.readTemperatureC(card)).thenReturn(45.0);
        GpuEfficiencyStore sweeps = new GpuEfficiencyStore(json, directory.resolve("max-sweeps.json").toString());
        sweeps.save(new GpuEfficiencyStore.Profile("GPU-a", "Test", "pearl", "PearlHash", 150, 120.0,
                150.0, 0.8, Instant.now(), List.of(
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(120, 100.0, 120.0, 60.0, true, null),
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(170, 145.0, 170.0, 76.0, true, null),
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(200, 180.0, 200.0, 82.0, true, null),
                de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(210, 190.0, 210.0, 70.0, false, null))));
        WorkerPowerModeService modes = new WorkerPowerModeService(controls, gpus, sweeps,
                new MiningPerformanceProfileStore(json, directory.resolve("max-bench.json").toString()), 80);

        assertEquals(170, modes.selectForMode("GPU-a", "pearl", "MAX_HASHRATE").watts());
        when(gpus.readTemperatureC(card)).thenReturn(70.0);
        assertEquals("power_mode_temperature_unavailable_or_high",
                modes.selectForMode("GPU-a", "pearl", "MAX_HASHRATE").error());
        when(gpus.readTemperatureC(card)).thenReturn(null);
        assertEquals("power_mode_temperature_unavailable_or_high",
                modes.selectForMode("GPU-a", "pearl", "MAX_HASHRATE").error());
        assertEquals("power_mode_requires_gpu", modes.selectForMode("cpu", "monero", "MAX_HASHRATE").error());
    }
}
