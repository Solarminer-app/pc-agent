package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.proxy.ManagedProxyService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/** One boot-only attempt window for workers explicitly armed by their operator. */
@Service
public class WorkerBootStartService {
    private static final Logger LOGGER = Logger.getLogger(WorkerBootStartService.class.getName());
    private static final Duration START_WINDOW = Duration.ofMinutes(5);
    private final AgentControlSettingsService controls;
    private final WorkerAssignmentService workers;
    private final MiningService mining;
    private final ManagedProxyService proxy;
    private final Set<String> pending = new HashSet<>();
    private Instant deadline;
    private boolean captured;

    public WorkerBootStartService(AgentControlSettingsService controls, WorkerAssignmentService workers,
                                  MiningService mining, ManagedProxyService proxy) {
        this.controls = controls;
        this.workers = workers;
        this.mining = mining;
        this.proxy = proxy;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void captureBootIntent() {
        pending.clear();
        pending.addAll(controls.get().workerStartOnBoot().keySet());
        captured = true;
        deadline = null;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public synchronized void startPendingWorkers() {
        if (!captured || pending.isEmpty()) return;
        if (!proxy.gateOpen()) return;
        if (deadline == null) deadline = Instant.now().plus(START_WINDOW);
        if (Instant.now().isAfter(deadline)) {
            LOGGER.warning("Worker boot start window expired for " + pending);
            pending.clear();
            return;
        }
        for (String deviceId : Set.copyOf(pending)) {
            var settings = controls.get();
            if (!settings.startsOnBoot(deviceId) || settings.manuallyStopped(deviceId)
                    || Coin.NONE.id().equals(settings.coinFor(deviceId))) {
                pending.remove(deviceId);
                continue;
            }
            try {
                var worker = workers.workers().stream().filter(row -> deviceId.equals(row.deviceId()))
                        .findFirst().orElse(null);
                if (worker == null) continue; // Hardware may appear after driver initialization.
                if (worker.status() == MinerStats.MinerStatus.MINING) {
                    pending.remove(deviceId);
                    continue;
                }
                if (!mining.beginWorkerStart(deviceId)) continue;
                try {
                    if (workers.start(deviceId)) pending.remove(deviceId);
                } finally {
                    mining.endWorkerStart(deviceId);
                }
            } catch (RuntimeException failure) {
                LOGGER.warning("Worker boot start for " + deviceId + " pending: " + failure.getMessage());
            }
        }
    }
}
