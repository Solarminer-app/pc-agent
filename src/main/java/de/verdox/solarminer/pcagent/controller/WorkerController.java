package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single worker resource (refactoring Phase 5d, owner decision O3): inventory,
 * assignment, start/pause and per-worker Node-automation consent live here. The former
 * duplicate assignment/consent routes under {@code /power-control/workers/*} were removed
 * when the dashboard moved to these paths; the Node-facing {@code /external/**} surface is
 * untouched.
 */
@RestController
@RequestMapping("/api/agent/local/workers")
public class WorkerController {
    private final WorkerAssignmentService workers;
    private final AgentControlSettingsService controls;
    private final LocalGpuPowerService gpus;
    private final MinerConsoleService consoles;
    private final AgentStatusService status;
    private de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService powerModes;
    private de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService coinRevenueMode;
    private de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock runLock;
    private MiningService mining;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMining(MiningService mining) { this.mining = mining; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPowerModes(de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService powerModes) {
        this.powerModes = powerModes;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCoinRevenueMode(de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService coinRevenueMode) {
        this.coinRevenueMode = coinRevenueMode;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRunLock(de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock runLock) {
        this.runLock = runLock;
    }

    public WorkerController(WorkerAssignmentService workers, AgentControlSettingsService controls,
                            LocalGpuPowerService gpus, MinerConsoleService consoles, AgentStatusService status) {
        this.workers = workers;
        this.controls = controls;
        this.gpus = gpus;
        this.consoles = consoles;
        this.status = status;
    }

    /** Loading plan P3: worker writes change the workers channel, overview and settings. */
    private void reportChanged(String... names) { status.invalidate(names); }

    /** Keep actionable worker failures in the JSON body consumed by the local dashboard. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> workerError(ResponseStatusException failure) {
        String message = failure.getReason();
        if (message == null || message.isBlank()) message = "Worker action failed";
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message", message));
    }

    @GetMapping
    public List<WorkerAssignmentService.WorkerView> workers() { return workers.workers(); }

    @PostMapping("/{deviceId}/power-mode")
    public WorkerAssignmentService.WorkerView powerMode(@PathVariable String deviceId, @RequestBody PowerModeRequest request) {
        if (runLock != null && !runLock.tryBegin("worker-power-mode"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "power_mode_busy");
        try { return savePowerMode(deviceId, request); }
        finally { if (runLock != null) runLock.end(); }
    }

    private WorkerAssignmentService.WorkerView savePowerMode(String deviceId, PowerModeRequest request) {
        if (request == null || !java.util.Set.of("MANUAL", "EFFICIENCY", "MAX_HASHRATE", "REVENUE_PER_KWH", "COIN_REVENUE_PER_KWH").contains(request.mode()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "power_mode_invalid");
        WorkerAssignmentService.WorkerView worker = workers.workers().stream()
                .filter(value -> deviceId.equals(value.deviceId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "gpu_not_found"));
        if (!"MANUAL".equals(request.mode())) {
            if ("CPU".equals(worker.hardwareType()) && ("COIN_REVENUE_PER_KWH".equals(request.mode()) || "MAX_HASHRATE".equals(request.mode())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "power_mode_requires_gpu");
            boolean revenueMode = "REVENUE_PER_KWH".equals(request.mode())
                    || "COIN_REVENUE_PER_KWH".equals(request.mode());
            if (revenueMode && "GPU".equals(worker.hardwareType())) {
                if (coinRevenueMode == null)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "power_mode_unavailable");
                var selection = coinRevenueMode.selectBestStartPoint(deviceId, worker.coin(), request.mode());
                if (selection.error() != null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, selection.error());
            } else {
                if (powerModes == null)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "power_mode_unavailable");
                var selection = powerModes.selectForMode(deviceId, worker.coin(), request.mode());
                if (selection.error() != null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, selection.error());
            }
        }
        String previous = controls.get().powerModeFor(deviceId);
        if (!controls.setWorkerPowerMode(deviceId, request.mode()))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "power_mode_save_failed");
        if (worker.status() == de.verdox.solarminer.pcagent.dto.MinerStats.MinerStatus.MINING && powerModes != null) {
            boolean revenueMode = "GPU".equals(worker.hardwareType())
                    && ("REVENUE_PER_KWH".equals(request.mode()) || "COIN_REVENUE_PER_KWH".equals(request.mode()));
            boolean started;
            String failure = null;
            if (revenueMode) {
                var applied = coinRevenueMode.applyBestStartPoint(deviceId, worker.coin(), request.mode(), false, true);
                started = applied.success();
                failure = applied.error();
            } else {
                var applied = powerModes.apply(deviceId, worker.coin());
                started = applied.error() == null;
                failure = applied.error();
            }
            if (started && "CPU".equals(worker.hardwareType())) {
                started = workers.pause(deviceId);
                if (started) started = workers.start(deviceId);
            }
            if (!started) {
                controls.setWorkerPowerMode(deviceId, previous);
                if ("CPU".equals(worker.hardwareType())) try { workers.start(deviceId); } catch (RuntimeException ignored) { }
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        failure == null ? "power_mode_apply_failed" : failure);
            }
        }
        if (coinRevenueMode != null && !"COIN_REVENUE_PER_KWH".equals(request.mode())) {
            coinRevenueMode.resetCooldown(deviceId);
            coinRevenueMode.cancelLocalStart(deviceId);
        }
        reportChanged("workers", "power-control", "overview", "settings");
        return workers.workers().stream().filter(value -> deviceId.equals(value.deviceId())).findFirst().orElseThrow();
    }

    public record PowerModeRequest(String mode) { }

    @PostMapping("/{deviceId}/assignment")
    public WorkerAssignmentService.WorkerView assignment(@PathVariable String deviceId,
                                                          @RequestBody WorkerAssignmentService.Assignment request) {
        try {
            WorkerAssignmentService.WorkerView view = workers.assign(deviceId, request);
            if (coinRevenueMode != null) coinRevenueMode.cancelLocalStart(deviceId);
            reportChanged("workers", "overview", "settings", "power-control");
            return view;
        }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage()); }
        catch (IllegalStateException failure) { throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage()); }
        catch (IOException failure) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failure.getMessage()); }
    }

    @PostMapping("/{deviceId}/start")
    public synchronized boolean start(@PathVariable String deviceId) {
        if (mining != null && !mining.beginWorkerStart(deviceId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "worker_start_in_progress");
        reportChanged("workers", "overview");
        try {
            WorkerAssignmentService.WorkerView worker = workers.workers().stream()
                    .filter(value -> deviceId.equals(value.deviceId())).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "gpu_not_found"));
            String mode = controls.get().powerModeFor(deviceId);
            boolean revenueMode = "GPU".equals(worker.hardwareType())
                    && ("REVENUE_PER_KWH".equals(mode) || "COIN_REVENUE_PER_KWH".equals(mode));
            boolean started;
            if (revenueMode) {
                if (coinRevenueMode == null)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "power_mode_unavailable");
                var result = coinRevenueMode.applyBestStartPoint(deviceId, worker.coin(), mode, true);
                if (!result.success()) throw new ResponseStatusException(HttpStatus.CONFLICT, result.error());
                started = true;
            } else started = workers.start(deviceId);
            if (started) reportChanged("workers", "overview", "power-control");
            return started;
        }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage()); }
        catch (IllegalStateException failure) { throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage()); }
        finally {
            if (mining != null) mining.endWorkerStart(deviceId);
            reportChanged("workers", "overview");
        }
    }

    @PostMapping("/{deviceId}/pause")
    public boolean pause(@PathVariable String deviceId) {
        boolean paused = workers.pause(deviceId);
        if (paused) {
            if (coinRevenueMode != null) {
                coinRevenueMode.resetCooldown(deviceId);
                coinRevenueMode.cancelLocalStart(deviceId);
            }
            reportChanged("workers", "overview", "power-control");
        }
        return paused;
    }

    /**
     * Per-worker Node-automation consent. Moved here from the power-control controller in
     * Phase 5d so every worker-scoped write shares one resource; the console echo keeps the
     * operator-visible audit trail identical.
     */
    @PostMapping("/{workerId}/external-control")
    public AgentControlSettingsService.Settings workerControl(@PathVariable String workerId, @RequestParam boolean enabled) {
        // REST audit R6: worker-id validation reads the driver snapshot, not live discovery.
        if (!WorkerIds.isCpu(workerId) && gpus.snapshot().stream().noneMatch(gpu -> gpu.deviceId().equals(workerId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker wurde nicht erkannt");
        if (!controls.setWorkerEnabled(workerId, enabled))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Worker-Freigabe konnte nicht gespeichert werden");
        reportChanged("settings", "overview", "workers");
        String line = Instant.now() + " [SolarMiner] [Remote Control] External control for " + workerId + (enabled ? " enabled" : " disabled");
        if (WorkerIds.isCpu(workerId)) consoles.append(Coin.MONERO.id(), line);
        else {
            consoles.append(Coin.PEARL.id(), line);
            gpus.snapshot().stream().filter(gpu -> gpu.deviceId().equals(workerId)).findFirst().ifPresent(gpu ->
                    consoles.append(Coin.PEARL.id() + "-" + gpu.vendor() + "-" + gpu.index(), line));
        }
        return controls.get();
    }
}
