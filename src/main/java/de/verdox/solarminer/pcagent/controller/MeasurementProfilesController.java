package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** One consistent read of durable measurements for the benchmark workspace. */
@RestController
@RequestMapping("/api/agent/local/measurements/profiles")
public class MeasurementProfilesController {
    private final MiningPerformanceProfileStore performance;
    private final GpuEfficiencyStore efficiency;

    public MeasurementProfilesController(MiningPerformanceProfileStore performance, GpuEfficiencyStore efficiency) {
        this.performance = performance;
        this.efficiency = efficiency;
    }

    @GetMapping
    public Snapshot profiles() {
        return new Snapshot(performance.all(), efficiency.all(), performance.storageStatus(), efficiency.storageStatus());
    }

    public record Snapshot(List<MiningPerformanceProfileStore.Profile> benchmarks,
                           List<GpuEfficiencyStore.Profile> sweeps,
                           MiningPerformanceProfileStore.StorageStatus benchmarkStorage,
                           GpuEfficiencyStore.StorageStatus sweepStorage) { }
}
