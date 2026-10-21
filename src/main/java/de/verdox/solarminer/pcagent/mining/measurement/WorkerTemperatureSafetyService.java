package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Regular-mining thermal stop; measurement owners enforce their own thermal checks. */
@Service
public class WorkerTemperatureSafetyService {
    private de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore pointSafety;
    private de.verdox.solarminer.pcagent.mining.economics.MeasuredOperatingPointService points;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPointSafety(de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore safety,
                               de.verdox.solarminer.pcagent.mining.economics.MeasuredOperatingPointService points) {
        this.pointSafety = safety; this.points = points;
    }
    private final WorkerBenchmarkPolicyService policies;
    private final WorkerAssignmentService workers;
    private final LocalGpuPowerService power;
    private final LocalRunLock lock;
    public WorkerTemperatureSafetyService(WorkerBenchmarkPolicyService policies, WorkerAssignmentService workers,
                                         LocalGpuPowerService power, LocalRunLock lock) {
        this.policies = policies; this.workers = workers; this.power = power; this.lock = lock;
    }
    @Scheduled(fixedDelayString = "${solarminer.agent.worker-temperature-check-ms:2000}")
    public void evaluate() {
        // Measurement owners perform their own immediate stop and cleanup. A user pause here
        // would interfere with their temporary overrides and cooldown/retry ownership.
        if (!lock.tryBegin("worker-temperature")) return;
        try {
            for (var worker : workers.workers()) {
                if (!"GPU".equals(worker.hardwareType()) || worker.status() != MinerStats.MinerStatus.MINING
                        || policies.get(worker.deviceId()) == null) continue;
                var gpu = power.snapshot().stream().filter(g -> worker.deviceId().equals(g.deviceId())).findFirst().orElse(null);
                Double temperature;
                try { temperature = gpu == null ? null : power.readTemperatureC(gpu); }
                catch (RuntimeException unavailable) { temperature = null; }
                if (!policies.temperatureSafe(worker.deviceId(), temperature, 90)) {
                    boolean readable = temperature != null && Double.isFinite(temperature) && temperature > 0;
                    double ceiling = policies.ceiling(worker.deviceId(), 90);
                    String reason = readable
                            ? String.format(java.util.Locale.ROOT,
                                    "Temperaturstopp / Temperature stop: %.1f °C >= %.1f °C", temperature, ceiling)
                            : "Temperaturstopp: GPU-Temperatur nicht lesbar / Temperature stop: GPU temperature unavailable";
                    if (readable && pointSafety != null) {
                        var current = points.current(worker.deviceId());
                        String key = current != null && java.util.Objects.equals(current.coin(), worker.coin())
                                && java.util.Objects.equals(current.minerSoftwareId(), worker.minerSoftwareId())
                                && java.util.Objects.equals(current.limitWatts(), gpu.currentPowerLimitWatts())
                                ? current.pointKey() : worker.coin() + "|" + worker.minerSoftwareId() + "|"
                                    + gpu.currentPowerLimitWatts() + "|BASELINE";
                        try { pointSafety.reject(worker.deviceId(), key); }
                        catch (RuntimeException failure) {
                            org.slf4j.LoggerFactory.getLogger(getClass()).warn("power_mode_point_safety_unavailable: {}", worker.deviceId(), failure);
                        }
                    }
                    try { workers.pause(worker.deviceId(), reason); }
                    catch (RuntimeException failure) {
                        org.slf4j.LoggerFactory.getLogger(getClass()).warn("benchmark_temperature_limit: {}", worker.deviceId(), failure);
                    }
                }
            }
        } finally { lock.end(); }
    }
}
