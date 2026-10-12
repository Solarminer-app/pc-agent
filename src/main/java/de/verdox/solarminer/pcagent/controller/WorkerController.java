package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import org.springframework.http.HttpStatus;
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

    @GetMapping
    public List<WorkerAssignmentService.WorkerView> workers() { return workers.workers(); }

    @PostMapping("/{deviceId}/assignment")
    public WorkerAssignmentService.WorkerView assignment(@PathVariable String deviceId,
                                                          @RequestBody WorkerAssignmentService.Assignment request) {
        try {
            WorkerAssignmentService.WorkerView view = workers.assign(deviceId, request);
            reportChanged("workers", "overview", "settings", "power-control");
            return view;
        }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage()); }
        catch (IllegalStateException failure) { throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage()); }
        catch (IOException failure) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failure.getMessage()); }
    }

    @PostMapping("/{deviceId}/start")
    public boolean start(@PathVariable String deviceId) {
        try {
            boolean started = workers.start(deviceId);
            if (started) reportChanged("workers", "overview", "power-control");
            return started;
        }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage()); }
        catch (IllegalStateException failure) { throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage()); }
    }

    @PostMapping("/{deviceId}/pause")
    public boolean pause(@PathVariable String deviceId) {
        boolean paused = workers.pause(deviceId);
        if (paused) reportChanged("workers", "overview", "power-control");
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
