package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Regular-mining thermal stop; measurement owners enforce their own thermal checks. */
@Service
public class WorkerTemperatureSafetyService {
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
                    try { workers.pause(worker.deviceId()); }
                    catch (RuntimeException failure) {
                        org.slf4j.LoggerFactory.getLogger(getClass()).warn("benchmark_temperature_limit: {}", worker.deviceId(), failure);
                    }
                }
            }
        } finally { lock.end(); }
    }
}
