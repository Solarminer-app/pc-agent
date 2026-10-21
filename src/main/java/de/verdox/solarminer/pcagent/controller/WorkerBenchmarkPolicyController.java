package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/local/benchmark-policies")
public class WorkerBenchmarkPolicyController {
    private final WorkerBenchmarkPolicyService policies;
    private final de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock lock;
    private final WorkerAssignmentService workers;
    private final MinerCatalogService catalog;
    public WorkerBenchmarkPolicyController(WorkerBenchmarkPolicyService policies, de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock lock,
            WorkerAssignmentService workers, MinerCatalogService catalog) {
        this.policies = policies; this.lock = lock; this.workers = workers; this.catalog = catalog;
    }
    @GetMapping public Map<String, WorkerBenchmarkPolicyService.Policy> all() { return policies.all(); }
    @GetMapping("/options/{id}")
    public java.util.List<MinerCatalogService.MinerOption> options(@PathVariable String id) {
        var worker = workers.workers().stream().filter(w -> id.equals(w.deviceId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("benchmark_worker_unavailable"));
        return catalog.allOptions().stream()
                .filter(option -> MinerCatalogService.supportsHardware(option, worker.hardwareType(), worker.vendor())
                        && (!"GPU".equals(worker.hardwareType()) || MinerCatalogService.supportsGpuModel(option, worker.hardwareModel())))
                .toList();
    }
    @PutMapping("/{id}") public WorkerBenchmarkPolicyService.Policy save(@PathVariable String id,
            @RequestBody WorkerBenchmarkPolicyService.Policy policy) {
        if (!lock.tryBegin("benchmark-preferences")) throw new IllegalStateException("power_mode_busy");
        try {
            var worker = workers.workers().stream().filter(w -> id.equals(w.deviceId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("benchmark_worker_unavailable"));
            for (var pair : policy.combinations())
                if (catalog.options(pair.coin()).stream().noneMatch(option -> option.id().equals(pair.minerSoftwareId())
                        && MinerCatalogService.supportsHardware(option, worker.hardwareType(), worker.vendor())
                        && (!"GPU".equals(worker.hardwareType()) || MinerCatalogService.supportsGpuModel(option, worker.hardwareModel()))))
                    throw new IllegalArgumentException("benchmark_combination_invalid");
            policies.save(id, policy); return policy;
        } finally { lock.end(); }
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("message", failure.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> conflict(IllegalStateException failure) {
        return ResponseEntity.status(409).body(Map.of("message", failure.getMessage()));
    }
}
