package de.verdox.solarminer.pcagent.mining.power;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningSelectionStore;
import de.verdox.solarminer.pcagent.mining.economics.AutoTuningProfileEconomicsService;
import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Applies a locally selected, measured operating point before a GPU miner starts. */
@Service
public class WorkerPowerModeService {
    private final AgentControlSettingsService controls;
    private final LocalGpuPowerService gpus;
    private final GpuEfficiencyStore sweeps;
    private final MiningPerformanceProfileStore benchmarks;
    private de.verdox.solarminer.pcagent.miner.MinerFactory miners;
    private AutoTuningProfileStore autoTuningProfiles;
    private GpuTuningService gpuTuning;
    private AutoTuningSelectionStore autoTuningSelections;
    private AutoTuningProfileEconomicsService autoTuningEconomics;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMinerFactory(de.verdox.solarminer.pcagent.miner.MinerFactory miners) { this.miners = miners; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAutoTuning(AutoTuningProfileStore profiles, GpuTuningService tuning,
                              AutoTuningSelectionStore selections,
                              @org.springframework.context.annotation.Lazy AutoTuningProfileEconomicsService economics) {
        this.autoTuningProfiles = profiles; this.gpuTuning = tuning;
        this.autoTuningSelections = selections; this.autoTuningEconomics = economics;
    }

    /** Focused-test compatibility; production wiring also supplies selection and economics services. */
    public void setAutoTuning(AutoTuningProfileStore profiles, GpuTuningService tuning) {
        this.autoTuningProfiles = profiles; this.gpuTuning = tuning;
    }
    private final double maxHashrateTemperatureC;

    public WorkerPowerModeService(AgentControlSettingsService controls, LocalGpuPowerService gpus,
                                  GpuEfficiencyStore sweeps, MiningPerformanceProfileStore benchmarks,
                                  @Value("${solarminer.agent.max-hashrate.max-temperature-c:80}") double maxHashrateTemperatureC) {
        this.controls = controls;
        this.gpus = gpus;
        this.sweeps = sweeps;
        this.benchmarks = benchmarks;
        this.maxHashrateTemperatureC = Double.isFinite(maxHashrateTemperatureC)
                ? Math.max(60, Math.min(90, maxHashrateTemperatureC)) : 80;
    }

    public Selection select(String deviceId, String coinId) {
        return selectForMode(deviceId, coinId, controls.get().powerModeFor(deviceId));
    }

    public Selection selectForMode(String deviceId, String coinId, String mode) {
        if ("MANUAL".equals(mode)) return new Selection(mode, null, null);
        Coin coin = Coin.byIdOrNull(coinId);
        if ("MAX_HASHRATE".equals(mode) && WorkerIds.isCpu(deviceId))
            return new Selection(mode, null, "power_mode_requires_gpu");
        if (WorkerIds.isCpu(deviceId)) {
            if (coin == null || !coin.isCpu() || "COIN_REVENUE_PER_KWH".equals(mode)
                    || "PROFIT_PER_DAY".equals(mode) || "COIN_PROFIT_PER_DAY".equals(mode))
                return new Selection(mode, null, "power_mode_requires_gpu_coin");
            return benchmarks.all().stream()
                    .filter(profile -> deviceId.equals(profile.workerId()) && coin.id().equals(profile.coin())
                            && coin.matchesAlgorithm(profile.algorithm()) && profile.powerTargetWatts() != null
                            && profile.powerTargetWatts() > 0 && profile.observations() >= 12
                            && positive(profile.hashrateHps()) && profile.powerWatts() > 0)
                    .max(Comparator.comparingDouble(profile -> profile.hashrateHps() / profile.powerWatts()))
                    .map(profile -> new Selection(mode, profile.powerTargetWatts(), null))
                    .orElseGet(() -> new Selection(mode, null, "power_mode_no_measured_point"));
        }
        if (coin == null || !coin.isGpu()) return new Selection(mode, null, "power_mode_requires_gpu_coin");
        String softwareId = miners == null ? null : miners.minerSoftwareForWorker(coin, deviceId);
        if ("AUTO_TUNING".equals(mode)) {
            LocalGpuPowerService.Gpu card = gpus.discover().stream()
                    .filter(gpu -> deviceId.equals(gpu.deviceId())).findFirst().orElse(null);
            if (card == null || !card.supportsDynamicPowerScaling())
                return new Selection(mode, null, "gpu_power_control_unavailable");
            Integer safeMaxWatts = card.safeTuningMaxWatts();
            if (safeMaxWatts == null) return new Selection(mode, null, "auto_tuning_driver_default_power_unavailable");
            int startingWatts = gpus.configuredPowerLimitWatts(card);
            if (startingWatts < card.minWatts() || startingWatts > safeMaxWatts)
                return new Selection(mode, null, "auto_tuning_start_target_unsafe");
            String version = miners == null ? null : miners.minerVersionForWorker(coin, deviceId);
            if (autoTuningSelections != null
                    && autoTuningSelections.get(deviceId).goal() == AutoTuningSelectionStore.Goal.PROFIT_PER_DAY
                    && autoTuningProfiles != null
                    && !autoTuningProfiles.matrix(deviceId, coin.id(), softwareId, version, card.model(), card.vendor(),
                    coin.assignmentAlgorithm()).isEmpty()
                    && (autoTuningEconomics == null || autoTuningEconomics.compare(deviceId, coin.id(), softwareId,
                    version, card.model(), card.vendor(), safeMaxWatts, coin.assignmentAlgorithm()).profit() == null))
                return new Selection(mode, null, "auto_tuning_profit_unavailable");
            AutoTuningProfileStore.Profile profile = selectedAutoTuningProfile(deviceId, coin.id(), softwareId,
                    version, card, safeMaxWatts, coin.assignmentAlgorithm());
            if (profile == null) return new Selection(mode, startingWatts, null);
            return new Selection(mode, profile.powerLimitWatts(), null);
        }
        LocalGpuPowerService.Gpu gpu = gpus.discover().stream().filter(card -> deviceId.equals(card.deviceId())).findFirst().orElse(null);
        if (gpu == null || !gpu.supportsDynamicPowerScaling()) return new Selection(mode, null, "gpu_power_control_unavailable");
        if ("MAX_HASHRATE".equals(mode)) {
            Double currentTemperature = gpus.readTemperatureC(gpu);
            if (currentTemperature == null || !Double.isFinite(currentTemperature)
                    || currentTemperature >= maxHashrateTemperatureC - 10)
                return new Selection(mode, null, "power_mode_temperature_unavailable_or_high");
            GpuEfficiencyStore.Profile profile = sweeps.find(deviceId, coin.assignmentAlgorithm(), softwareId);
            if (profile == null || !coin.id().equals(profile.coin()) || profile.steps() == null)
                return new Selection(mode, null, "power_mode_no_safe_hashrate_point");
            return profile.steps().stream()
                    .filter(step -> step.stable() && positive(step.medianHashrateHs())
                            && step.maxTemperatureC() != null && Double.isFinite(step.maxTemperatureC())
                            && step.maxTemperatureC() < maxHashrateTemperatureC
                            && step.limitWatts() >= gpu.minWatts() && step.limitWatts() <= gpu.maxWatts())
                    .max(Comparator.comparingDouble((GpuEfficiencyStore.StepResult step) -> step.medianHashrateHs())
                            .thenComparingInt(step -> -step.limitWatts()))
                    .map(step -> new Selection(mode, step.limitWatts(), null))
                    .orElseGet(() -> new Selection(mode, null, "power_mode_no_safe_hashrate_point"));
        }
        List<Point> points = new ArrayList<>();
        GpuEfficiencyStore.Profile sweep = sweeps.find(deviceId, coin.assignmentAlgorithm(), softwareId);
        if (sweep != null && coin.id().equals(sweep.coin()) && sweep.steps() != null) {
            for (GpuEfficiencyStore.StepResult step : sweep.steps()) {
                if (step.stable() && positive(step.medianHashrateHs()) && positive(step.avgPowerWatts()))
                    points.add(new Point(step.limitWatts(), step.medianHashrateHs() / step.avgPowerWatts()));
            }
        }
        for (MiningPerformanceProfileStore.Profile benchmark : benchmarks.all()) {
            if (!deviceId.equals(benchmark.workerId()) || !coin.id().equals(benchmark.coin())
                    || !coin.matchesAlgorithm(benchmark.algorithm()) || benchmark.powerTargetWatts() == null
                    || (softwareId != null && benchmark.minerSoftwareId() != null
                    && !softwareId.equals(benchmark.minerSoftwareId()))
                    || benchmark.observations() < 12 || !positive(benchmark.hashrateHps())
                    || benchmark.powerWatts() <= 0) continue;
            points.add(new Point(benchmark.powerTargetWatts(), benchmark.hashrateHps() / benchmark.powerWatts()));
        }
        return points.stream().filter(point -> point.watts() >= gpu.minWatts() && point.watts() <= gpu.maxWatts())
                .max(Comparator.comparingDouble(Point::hashesPerWatt).thenComparingInt(point -> -point.watts()))
                .map(point -> new Selection(mode, point.watts(), null))
                .orElseGet(() -> new Selection(mode, null, "power_mode_no_measured_point"));
    }

    public Selection apply(String deviceId, String coinId) {
        return applyForBudget(deviceId, coinId, null);
    }

    public boolean needsAutoTuningCalibration(String deviceId, String coinId) {
        Coin coin = Coin.byIdOrNull(coinId);
        if (coin == null || autoTuningProfiles == null) return true;
        LocalGpuPowerService.Gpu gpu = gpus.discover().stream()
                .filter(card -> deviceId.equals(card.deviceId())).findFirst().orElse(null);
        if (gpu == null || gpu.safeTuningMaxWatts() == null) return true;
        String software = miners == null ? null : miners.minerSoftwareForWorker(coin, deviceId);
        String version = miners == null ? null : miners.minerVersionForWorker(coin, deviceId);
        return autoTuningProfiles.mostEfficientWithinBudget(deviceId, coin.id(), software, version,
                gpu.model(), gpu.vendor(), gpu.safeTuningMaxWatts(), coin.assignmentAlgorithm()) == null;
    }

    public boolean restoreAutoTuningClocks(String deviceId) {
        return gpuTuning != null && gpuTuning.restoreDefaults(deviceId).applied();
    }

    /** Choose the highest calibrated tier that fits the live per-GPU allocation. */
    public Selection applyForBudget(String deviceId, String coinId, Integer budgetWatts) {
        Selection choice = select(deviceId, coinId);
        if (choice.error() != null) return choice;
        if (WorkerIds.isCpu(deviceId)) return choice; // MiningService applies the measured thread-scaled target.
        LocalGpuPowerService.Gpu gpu = gpus.discover().stream().filter(card -> deviceId.equals(card.deviceId())).findFirst().orElse(null);
        if (gpu == null) return new Selection(choice.mode(), null, "gpu_power_control_unavailable");
        if ("MANUAL".equals(choice.mode())) {
            if (gpu == null || !gpus.enforceConfiguredPowerLimit(gpu))
                return new Selection(choice.mode(), null, "power_mode_apply_failed");
            return choice;
        }
        if ("AUTO_TUNING".equals(choice.mode())) {
            Integer safeMaxWatts = gpu.safeTuningMaxWatts();
            if (safeMaxWatts == null) return new Selection(choice.mode(), null, "auto_tuning_driver_default_power_unavailable");
            if (budgetWatts != null && budgetWatts > safeMaxWatts)
                return new Selection(choice.mode(), null, "auto_tuning_budget_above_safe_limit");
            Coin coin = Coin.byIdOrNull(coinId);
            String softwareId = coin == null || miners == null ? null : miners.minerSoftwareForWorker(coin, deviceId);
            String version = coin == null || miners == null ? null : miners.minerVersionForWorker(coin, deviceId);
            AutoTuningProfileStore.Profile profile = autoTuningProfiles == null || coin == null ? null
                    : budgetWatts == null
                    ? selectedAutoTuningProfile(deviceId, coin.id(), softwareId, version, gpu, safeMaxWatts,
                    coin.assignmentAlgorithm())
                    : autoTuningProfiles.forBudget(deviceId, coin.id(), softwareId, version,
                    gpu.model(), gpu.vendor(), budgetWatts, coin.assignmentAlgorithm());
            if (profile == null && budgetWatts == null) {
                if (gpuTuning == null || !gpuTuning.restoreDefaults(deviceId).applied()
                        || !gpus.setTotalPowerTarget(choice.watts(), List.of(gpu)))
                    return new Selection(choice.mode(), null, "power_mode_apply_failed");
                return choice;
            }
            if (profile == null || profile.powerLimitWatts() < gpu.minWatts() || gpuTuning == null)
                return new Selection(choice.mode(), null, "auto_tuning_budget_below_profile");
            if ("NVIDIA".equalsIgnoreCase(gpu.vendor()) && miners != null) {
                var nativeTuning = miners.gpuMinerForWorker(coin.id(), deviceId)
                        .filter(de.verdox.solarminer.pcagent.miner.MinerNativeTuning.class::isInstance)
                        .map(de.verdox.solarminer.pcagent.miner.MinerNativeTuning.class::cast);
                if (nativeTuning.isPresent()) {
                    return nativeTuning.get().applyNativeTuning(gpu, profile)
                            ? new Selection(choice.mode(), profile.powerLimitWatts(), null)
                            : new Selection(choice.mode(), null, "srbminer_native_tuning_failed");
                }
            }
            var clocks = profile.memoryOffsetMhz() == null
                    ? gpuTuning.apply(deviceId, profile.coreMhz(), profile.coreMhz(), profile.memoryMhz(), profile.memoryMhz())
                    : gpuTuning.apply(deviceId, profile.coreMhz(), profile.coreMhz(), null, null,
                    profile.memoryOffsetMhz(), profile.memoryOffsetPstate());
            if (!clocks.applied()) return new Selection(choice.mode(), null, clocks.reason());
            if (!gpus.setAutoTuningPowerTarget(profile.powerLimitWatts(), gpu)) {
                gpuTuning.restoreDefaults(deviceId);
                return new Selection(choice.mode(), null, "power_mode_apply_failed");
            }
            return new Selection(choice.mode(), profile.powerLimitWatts(), null);
        }
        if (gpu == null || !gpus.setTotalPowerTarget(choice.watts(), List.of(gpu)))
            return new Selection(choice.mode(), null, "power_mode_apply_failed");
        return choice;
    }

    private static boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }

    private AutoTuningProfileStore.Profile selectedAutoTuningProfile(String deviceId, String coinId,
            String softwareId, String version, LocalGpuPowerService.Gpu gpu, int budgetWatts, String algorithm) {
        if (autoTuningProfiles == null) return null;
        AutoTuningSelectionStore.Goal goal = autoTuningSelections == null ? AutoTuningSelectionStore.Goal.EFFICIENCY
                : autoTuningSelections.get(deviceId).goal();
        if (goal == AutoTuningSelectionStore.Goal.PROFIT_PER_DAY && autoTuningEconomics != null) {
            var profit = autoTuningEconomics.compare(deviceId, coinId, softwareId, version, gpu.model(), gpu.vendor(),
                    budgetWatts, algorithm).profit();
            if (profit != null) return profit.profile();
        }
        return autoTuningProfiles.mostEfficientWithinBudget(deviceId, coinId, softwareId, version,
                gpu.model(), gpu.vendor(), budgetWatts, algorithm);
    }
    private record Point(int watts, double hashesPerWatt) { }
    public record Selection(String mode, Integer watts, String error) { }
}
