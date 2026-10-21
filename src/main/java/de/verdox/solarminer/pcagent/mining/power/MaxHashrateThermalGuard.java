package de.verdox.solarminer.pcagent.mining.power;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Stops maximum-hashrate mining when the GPU temperature cannot be trusted. */
@Service
public class MaxHashrateThermalGuard {
    private de.verdox.solarminer.pcagent.mining.measurement.WorkerTemperatureSafetyService thermalSafety;
    @org.springframework.beans.factory.annotation.Autowired
    public void setThermalSafety(de.verdox.solarminer.pcagent.mining.measurement.WorkerTemperatureSafetyService safety) { thermalSafety = safety; }
    private final AgentControlSettingsService controls;
    private final LocalGpuPowerService gpus;
    private final MiningService mining;
    private final WorkerAssignmentService workers;
    private final LocalRunLock lock;
    private final double ceiling;

    public MaxHashrateThermalGuard(AgentControlSettingsService controls, LocalGpuPowerService gpus,
                                   MiningService mining, WorkerAssignmentService workers, LocalRunLock lock,
                                   @Value("${solarminer.agent.max-hashrate.max-temperature-c:80}") double ceiling) {
        this.controls = controls;
        this.gpus = gpus;
        this.mining = mining;
        this.workers = workers;
        this.lock = lock;
        this.ceiling = Double.isFinite(ceiling) ? Math.max(60, Math.min(90, ceiling)) : 80;
    }

    @Scheduled(fixedDelayString = "${solarminer.agent.max-hashrate.temperature-check-ms:5000}")
    public void check() {
        if (!controls.get().hasAutomaticPowerMode() || !lock.tryBegin("max-hashrate-thermal-guard")) return;
        try {
            java.util.List<MinerStats.Worker> active;
            try {
                active = mining.getWorkerStats().stream()
                        .filter(worker -> worker.miningStatus() == MinerStats.MinerStatus.MINING
                                && "MAX_HASHRATE".equals(controls.get().powerModeFor(worker.deviceId())))
                        .toList();
            } catch (RuntimeException failure) {
                controls.get().workerPowerModes().forEach((id, mode) -> {
                    if ("MAX_HASHRATE".equals(mode)) pause(id, null);
                });
                return;
            }
            if (active.isEmpty()) return;
            var cards = gpus.snapshot();
            for (var worker : active) {
                var gpu = cards.stream().filter(card -> card.deviceId().equals(worker.deviceId())).findFirst().orElse(null);
                Double temperature;
                try { temperature = gpu == null ? null : gpus.readTemperatureC(gpu); }
                catch (RuntimeException failure) { temperature = null; }
                if (temperature == null || !Double.isFinite(temperature) || temperature >= ceiling)
                    pause(worker.deviceId(), temperature);
            }
        } finally {
            lock.end();
        }
    }

    private void pause(String deviceId, Double temperature) {
        try {
            if (thermalSafety != null) thermalSafety.stopForTemperature(deviceId, temperature, ceiling);
            else workers.pause(deviceId);
        }
        catch (RuntimeException ignored) { /* Retry on the next check; never skip another GPU. */ }
    }
}
