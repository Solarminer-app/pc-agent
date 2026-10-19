package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningSelectionStore;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final AutoTuningSelectionStore selections;

    public GpuTuningController(LocalGpuPowerService power, GpuTuningService tuning, LocalRunLock runLock,
                               AutoTuningSessionService sessions, AutoTuningProfileStore profiles,
                               WorkerAssignmentService workers, AutoTuningSelectionStore selections) {
        this.power = power;
        this.tuning = tuning;
        this.runLock = runLock;
        this.sessions = sessions;
        this.profiles = profiles;
        this.workers = workers;
        this.selections = selections;
    }

    @GetMapping
    public List<Device> devices() {
        var assignments = workers.workers();
        return power.snapshot().stream().map(gpu -> {
            var worker = assignments.stream().filter(value -> gpu.deviceId().equals(value.deviceId())).findFirst().orElse(null);
            return new Device(gpu.deviceId(), gpu.model(), gpu.vendor(), gpu.currentPowerLimitWatts(), gpu.currentWatts(),
                    worker == null ? null : worker.coin(), worker == null || worker.status() == null ? null : worker.status().name(),
                    tuning.capabilities(gpu.deviceId()), worker == null ? "MANUAL" : worker.powerMode(),
                    tuning.memoryOffsetRange(gpu.deviceId()) != null, gpu.supportsDynamicPowerScaling(),
                    selections.get(gpu.deviceId()));
        }).toList();
    }

    @GetMapping("/session")
    public AutoTuningSessionService.Session session() { return sessions.status(); }

    @GetMapping("/profiles")
    public List<AutoTuningProfileStore.Profile> profiles() { return profiles.all(); }

    @DeleteMapping("/{deviceId}/calibration-data")
    public Map<String, Boolean> clearCalibrationData(@PathVariable String deviceId) {
        if (power.snapshot().stream().noneMatch(gpu -> gpu.deviceId().equals(deviceId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "gpu_not_found");
        if (!runLock.tryBegin("gpu-tuning-data-clear"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "auto_tuning_busy");
        try {
            if (!sessions.clearCompletedSession(deviceId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "auto_tuning_busy");
            if (!profiles.clearDevice(deviceId))
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "auto_tuning_clear_failed");
        } finally { runLock.end(); }
        if (workers.workers().stream().anyMatch(worker -> deviceId.equals(worker.deviceId())
                && worker.status() == de.verdox.solarminer.pcagent.dto.MinerStats.MinerStatus.MINING))
            workers.maybeStartAutoTuning(deviceId);
        return Map.of("cleared", true);
    }

    @GetMapping("/measurements")
    public List<AutoTuningProfileStore.Measurement> measurements(
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "100") int limit) {
        if (limit < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gpu_tuning_request_invalid");
        List<AutoTuningProfileStore.Measurement> rows = profiles.measurements().stream()
                .filter(row -> deviceId == null || deviceId.equals(row.deviceId())).toList();
        return limit == 0 || rows.size() <= limit ? rows : rows.subList(rows.size() - limit, rows.size());
    }

    @GetMapping("/measurement-summary")
    public List<MeasurementHistory> measurementSummary() {
        Map<String, HistoryBuilder> byDevice = new LinkedHashMap<>();
        for (AutoTuningProfileStore.Measurement row : profiles.measurements()) {
            HistoryBuilder history = byDevice.computeIfAbsent(row.deviceId(), ignored -> new HistoryBuilder());
            if ("SAMPLE".equals(row.type())) history.samples++;
            if ("RESULT".equals(row.type())) {
                if (Boolean.TRUE.equals(row.stable())) history.stable++;
                else history.unstable++;
                history.recent.add(row);
                if (history.recent.size() > 5) history.recent.removeFirst();
            }
        }
        return byDevice.entrySet().stream().map(entry -> new MeasurementHistory(entry.getKey(),
                entry.getValue().samples, entry.getValue().stable, entry.getValue().unstable,
                List.copyOf(entry.getValue().recent))).toList();
    }

    private static final class HistoryBuilder {
        int samples, stable, unstable;
        final List<AutoTuningProfileStore.Measurement> recent = new ArrayList<>();
    }

    public record MeasurementHistory(String deviceId, int samples, int stable, int unstable,
                                     List<AutoTuningProfileStore.Measurement> recent) { }

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

    @PutMapping("/{deviceId}/selection")
    public AutoTuningSelectionStore.Selection selection(@PathVariable String deviceId,
                                                         @RequestBody AutoTuningSelectionStore.Selection selection) {
        if (power.snapshot().stream().noneMatch(gpu -> gpu.deviceId().equals(deviceId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "gpu_not_found");
        if (selection == null || !selection.core() && !selection.memory() && !selection.power())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auto_tuning_selection_required");
        if (!selections.set(deviceId, selection))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "auto_tuning_selection_save_failed");
        return selection;
    }

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
                         GpuTuningService.Capabilities tuning, String powerMode,
                         boolean memoryOffsetControl, boolean powerControl,
                         AutoTuningSelectionStore.Selection selection) { }
    public record Apply(Integer coreMinMhz, Integer coreMaxMhz, Integer memoryMinMhz, Integer memoryMaxMhz) { }
    public record Start(String deviceId, String coinId) { }
}
