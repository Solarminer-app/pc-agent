package de.verdox.solarminer.pcagent.mining.power;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import org.springframework.stereotype.Service;

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

    public WorkerPowerModeService(AgentControlSettingsService controls, LocalGpuPowerService gpus,
                                  GpuEfficiencyStore sweeps, MiningPerformanceProfileStore benchmarks) {
        this.controls = controls;
        this.gpus = gpus;
        this.sweeps = sweeps;
        this.benchmarks = benchmarks;
    }

    public Selection select(String deviceId, String coinId) {
        return selectForMode(deviceId, coinId, controls.get().powerModeFor(deviceId));
    }

    public Selection selectForMode(String deviceId, String coinId, String mode) {
        if ("MANUAL".equals(mode)) return new Selection(mode, null, null);
        Coin coin = Coin.byIdOrNull(coinId);
        if (WorkerIds.isCpu(deviceId)) {
            if (coin == null || !coin.isCpu() || "COIN_REVENUE_PER_KWH".equals(mode))
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
        LocalGpuPowerService.Gpu gpu = gpus.discover().stream().filter(card -> deviceId.equals(card.deviceId())).findFirst().orElse(null);
        if (gpu == null || !gpu.supportsDynamicPowerScaling()) return new Selection(mode, null, "gpu_power_control_unavailable");
        List<Point> points = new ArrayList<>();
        GpuEfficiencyStore.Profile sweep = sweeps.find(deviceId, coin.assignmentAlgorithm());
        if (sweep != null && coin.id().equals(sweep.coin()) && sweep.steps() != null) {
            for (GpuEfficiencyStore.StepResult step : sweep.steps()) {
                if (step.stable() && positive(step.medianHashrateHs()) && positive(step.avgPowerWatts()))
                    points.add(new Point(step.limitWatts(), step.medianHashrateHs() / step.avgPowerWatts()));
            }
        }
        for (MiningPerformanceProfileStore.Profile benchmark : benchmarks.all()) {
            if (!deviceId.equals(benchmark.workerId()) || !coin.id().equals(benchmark.coin())
                    || !coin.matchesAlgorithm(benchmark.algorithm()) || benchmark.powerTargetWatts() == null
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
        Selection choice = select(deviceId, coinId);
        if (choice.error() != null) return choice;
        if (WorkerIds.isCpu(deviceId)) return choice; // MiningService applies the measured thread-scaled target.
        LocalGpuPowerService.Gpu gpu = gpus.discover().stream().filter(card -> deviceId.equals(card.deviceId())).findFirst().orElse(null);
        if ("MANUAL".equals(choice.mode())) {
            if (gpu == null || !gpus.enforceConfiguredPowerLimit(gpu))
                return new Selection(choice.mode(), null, "power_mode_apply_failed");
            return choice;
        }
        if (gpu == null || !gpus.setTotalPowerTarget(choice.watts(), List.of(gpu)))
            return new Selection(choice.mode(), null, "power_mode_apply_failed");
        return choice;
    }

    private static boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }
    private record Point(int watts, double hashesPerWatt) { }
    public record Selection(String mode, Integer watts, String error) { }
}
