package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import de.verdox.solarminer.pcagent.mining.economics.EconomicPlanningService;
import de.verdox.solarminer.pcagent.mining.economics.MeasuredOperatingPointService;
import de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore;
import de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;


/** Regular-mining thermal stop; measurement owners enforce their own thermal checks. */
@Service
public class WorkerTemperatureSafetyService {
    private static final Logger log = LoggerFactory.getLogger(WorkerTemperatureSafetyService.class);
    private OperatingPointSafetyStore pointSafety;
    private MeasuredOperatingPointService points;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPointSafety(OperatingPointSafetyStore safety,
                               MeasuredOperatingPointService points) {
        this.pointSafety = safety; this.points = points;
    }
    private EconomicPlanningService planning;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPlanning(@org.springframework.context.annotation.Lazy EconomicPlanningService planning) { this.planning = planning; }
    @org.springframework.beans.factory.annotation.Value("${solarminer.agent.max-hashrate.max-temperature-c:80}")
    private double maxHashrateCeiling = 80;
    private double ceiling(WorkerAssignmentService.WorkerView worker) {
        return policies.ceiling(worker.deviceId(), "MAX_HASHRATE".equals(worker.powerMode())
                ? (Double.isFinite(maxHashrateCeiling) ? Math.max(60, Math.min(90, maxHashrateCeiling)) : 80) : 90);
    }
    private static final long COOLDOWN_NANOS = Duration.ofMinutes(1).toNanos();
    private static final long RETRY_NANOS = Duration.ofMinutes(1).toNanos();
    private static final double HYSTERESIS_C = 5;
    private final Map<String, Cooling> cooling = new HashMap<>();
    private LongSupplier clock = System::nanoTime;
    void setClock(LongSupplier clock) { this.clock = clock; }
    private static final class Cooling {
        final BooleanSupplier permit;
        Long coolSince;
        Long attemptedAt;
        Cooling(BooleanSupplier permit) { this.permit = permit; }
    }
    private WorkerPowerModeService powerModes;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPowerModes(WorkerPowerModeService powerModes) { this.powerModes = powerModes; }
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
                if (!"GPU".equals(worker.hardwareType())) continue;
                var pending = cooling.get(worker.deviceId());
                if (pending != null && (planning != null && planning.capabilities().activePlanExpiresAt() != null
                        || pending.permit == null || !pending.permit.getAsBoolean()
                        || worker.status() != MinerStats.MinerStatus.PAUSED)) {
                    cooling.remove(worker.deviceId());
                    pending = null;
                }
                if (policies.get(worker.deviceId()) == null && pending == null) continue;
                if (worker.status() != MinerStats.MinerStatus.MINING && pending == null) continue;
                var gpu = power.snapshot().stream().filter(g -> worker.deviceId().equals(g.deviceId())).findFirst().orElse(null);
                Double temperature;
                try { temperature = gpu == null ? null : power.readTemperatureC(gpu); }
                catch (RuntimeException unavailable) { temperature = null; }
                if (pending != null) {
                    double ceiling = ceiling(worker);
                    long now = clock.getAsLong();
                    if (temperature == null || !Double.isFinite(temperature) || temperature <= 0
                            || temperature > ceiling - HYSTERESIS_C) {
                        pending.coolSince = null;
                        continue;
                    }
                    if (pending.coolSince == null) pending.coolSince = now;
                    if (now - pending.coolSince < COOLDOWN_NANOS
                            || pending.attemptedAt != null && now - pending.attemptedAt < RETRY_NANOS) continue;
                    pending.attemptedAt = now;
                    try {
                        if (pointSafety != null && pointSafety.available()
                                && workers.restartAfterTemperatureStop(worker.deviceId(), pending.permit)) {
                            cooling.remove(worker.deviceId());
                            log.info(
                                    "Worker nach Abkühlung neu gestartet / Worker restarted after cooldown: {}", worker.deviceId());
                        }
                    } catch (RuntimeException failure) {
                        log.warn(
                                "Wiederanlauf nach Abkühlung wartet / Cooldown restart pending: {}: {}", worker.deviceId(), failure.getMessage());
                    }
                    continue;
                }
                if (!policies.temperatureSafe(worker.deviceId(), temperature, 90))
                    stopForTemperature(worker, gpu, temperature, ceiling(worker));
            }
        } finally { lock.end(); }
    }
    /** Caller already owns the local run lock (including the maximum-hashrate guard). */
    public void stopForTemperature(String device, Double temperature, double ceiling) {
        WorkerAssignmentService.WorkerView worker = null;
        LocalGpuPowerService.Gpu gpu = null;
        try {
            worker = workers.workers().stream().filter(w -> device.equals(w.deviceId())).findFirst().orElse(null);
            gpu = power.snapshot().stream().filter(g -> device.equals(g.deviceId())).findFirst().orElse(null);
        } catch (RuntimeException unavailable) {
            log.warn("GPU-Daten für Temperaturstopp fehlen / GPU data unavailable for thermal stop: {}", device);
        }
        if (worker != null) stopForTemperature(worker, gpu, temperature, ceiling);
        else workers.pause(device, "Temperaturstopp: GPU-Daten nicht lesbar / Temperature stop: GPU data unavailable");
    }

    private void stopForTemperature(WorkerAssignmentService.WorkerView worker, LocalGpuPowerService.Gpu gpu,
                                    Double temperature, double ceiling) {
        boolean readable = temperature != null && Double.isFinite(temperature) && temperature > 0;
        String reason = readable
                ? String.format(java.util.Locale.ROOT,
                        "Temperaturstopp / Temperature stop: %.1f °C >= %.1f °C", temperature, ceiling)
                : "Temperaturstopp: GPU-Temperatur nicht lesbar / Temperature stop: GPU temperature unavailable";
        if (readable && gpu != null && gpu.currentPowerLimitWatts() != null && pointSafety != null) {
            boolean economicMode = java.util.Set.of("REVENUE_PER_KWH", "COIN_REVENUE_PER_KWH", "PROFIT_PER_DAY", "COIN_PROFIT_PER_DAY")
                    .contains(worker.powerMode() == null ? "" : worker.powerMode());
            var current = points == null || !economicMode ? null : points.current(worker.deviceId());
            String key = current != null && Objects.equals(current.coin(), worker.coin())
                    && Objects.equals(current.minerSoftwareId(), worker.minerSoftwareId())
                    && Objects.equals(current.limitWatts(), gpu.currentPowerLimitWatts())
                    ? current.pointKey() : worker.coin() + "|" + worker.minerSoftwareId() + "|"
                        + gpu.currentPowerLimitWatts() + "|BASELINE";
            var profile = powerModes == null || !"AUTO_TUNING".equals(worker.powerMode()) ? null
                    : powerModes.appliedProfile(worker.deviceId());
            if (profile != null && Objects.equals(profile.coinId(), worker.coin())
                    && Objects.equals(profile.softwareId(), worker.minerSoftwareId())
                    && Objects.equals(profile.powerLimitWatts(), gpu.currentPowerLimitWatts()))
                key = OperatingPointSafetyStore.profileKey(profile);
            try { pointSafety.reject(worker.deviceId(), key, temperature, ceiling); }
            catch (RuntimeException failure) {
                log.warn("power_mode_point_safety_unavailable: {}", worker.deviceId(), failure);
            }
        }
        try {
            if (workers.pause(worker.deviceId(), reason)
                    && (!readable || gpu != null && gpu.currentPowerLimitWatts() != null))
                cooling.put(worker.deviceId(), new Cooling(workers.thermalRestartPermit(worker.deviceId())));
        }
        catch (RuntimeException failure) {
            log.warn("benchmark_temperature_limit: {}", worker.deviceId(), failure);
        }
    }

}
