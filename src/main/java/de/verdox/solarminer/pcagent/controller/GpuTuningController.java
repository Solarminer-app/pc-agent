package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Local-only driver capability and calibration controls for the Auto Tuning page. */
@RestController
@RequestMapping("/api/agent/local/gpu-tuning")
public class GpuTuningController {
    private final LocalGpuPowerService power;
    private final GpuTuningService tuning;
    private final LocalRunLock runLock;
    private final AutoTuningSessionService sessions;
    private final AutoTuningProfileStore profiles;
    private final WorkerAssignmentService workers;

    public GpuTuningController(LocalGpuPowerService power, GpuTuningService tuning, LocalRunLock runLock,
                               AutoTuningSessionService sessions, AutoTuningProfileStore profiles,
                               WorkerAssignmentService workers) {
        this.power = power;
        this.tuning = tuning;
        this.runLock = runLock;
        this.sessions = sessions;
        this.profiles = profiles;
        this.workers = workers;
    }

    @GetMapping
    public List<Device> devices() {
        var assignments = workers.workers();
        return power.snapshot().stream().map(gpu -> {
            var worker = assignments.stream().filter(value -> gpu.deviceId().equals(value.deviceId())).findFirst().orElse(null);
            return new Device(gpu.deviceId(), gpu.model(), gpu.vendor(), gpu.currentPowerLimitWatts(), gpu.currentWatts(),
                    worker == null ? null : worker.coin(), worker == null || worker.status() == null ? null : worker.status().name(),
                    tuning.capabilities(gpu.deviceId()));
        }).toList();
    }

    @GetMapping("/session")
    public AutoTuningSessionService.Session session() { return sessions.status(); }

    @GetMapping("/profiles")
    public List<AutoTuningProfileStore.Profile> profiles() { return profiles.all(); }

    @PostMapping("/start")
    public AutoTuningSessionService.Session start(@RequestBody Start request) {
        if (request == null || request.deviceId() == null || request.coinId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gpu_tuning_request_invalid");
        try { return sessions.start(request.deviceId(), request.coinId()); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()); }
        catch (IllegalStateException error) { throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage()); }
    }

    @PostMapping("/cancel")
    public AutoTuningSessionService.Session cancel() { return sessions.cancel(); }

    @PostMapping("/{deviceId}/apply")
    public GpuTuningService.ApplyResult apply(@PathVariable String deviceId, @RequestBody Apply request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gpu_tuning_request_invalid");
        if (!runLock.tryBegin("gpu-clock-control")) throw new ResponseStatusException(HttpStatus.CONFLICT, "power_mode_busy");
        try {
            var result = tuning.apply(deviceId, request.coreMinMhz(), request.coreMaxMhz(),
                    request.memoryMinMhz(), request.memoryMaxMhz());
            if (!result.applied()) throw new ResponseStatusException(HttpStatus.CONFLICT, result.reason());
            return result;
        } finally { runLock.end(); }
    }

    @PostMapping("/{deviceId}/restore")
    public GpuTuningService.ApplyResult restore(@PathVariable String deviceId) {
        if (!runLock.tryBegin("gpu-clock-control")) throw new ResponseStatusException(HttpStatus.CONFLICT, "power_mode_busy");
        try {
            var result = tuning.restoreDriverDefaults(deviceId);
            if (!result.applied()) throw new ResponseStatusException(HttpStatus.CONFLICT, result.reason());
            return result;
        } finally { runLock.end(); }
    }

    public record Device(String deviceId, String model, String vendor, Integer powerLimitWatts,
                         Double measuredWatts, String coinId, String workerStatus,
                         GpuTuningService.Capabilities tuning) { }
    public record Apply(Integer coreMinMhz, Integer coreMaxMhz, Integer memoryMinMhz, Integer memoryMaxMhz) { }
    public record Start(String deviceId, String coinId) { }
}
