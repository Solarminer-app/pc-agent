package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/agent/local/workers")
public class WorkerController {
    private final WorkerAssignmentService workers;

    public WorkerController(WorkerAssignmentService workers) { this.workers = workers; }

    @GetMapping
    public List<WorkerAssignmentService.WorkerView> workers() { return workers.workers(); }

    @PostMapping("/{deviceId}/assignment")
    public WorkerAssignmentService.WorkerView assignment(@PathVariable String deviceId,
                                                          @RequestBody WorkerAssignmentService.Assignment request) {
        try { return workers.assign(deviceId, request); }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage()); }
        catch (IllegalStateException failure) { throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage()); }
        catch (IOException failure) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failure.getMessage()); }
    }

    @PostMapping("/{deviceId}/start")
    public boolean start(@PathVariable String deviceId) { return workers.start(deviceId); }

    @PostMapping("/{deviceId}/pause")
    public boolean pause(@PathVariable String deviceId) { return workers.pause(deviceId); }
}
