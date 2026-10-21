package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.measurement.EfficiencySweepService;
import de.verdox.solarminer.pcagent.mining.measurement.GpuEfficiencyStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/local/efficiency")
public class EfficiencySweepController {
    private final EfficiencySweepService sweep;
    private final AgentStatusService status;

    public EfficiencySweepController(EfficiencySweepService sweep, AgentStatusService status) {
        this.sweep = sweep;
        this.status = status;
    }

    @GetMapping
    public EfficiencySweepService.Session status() {
        return sweep.status();
    }

    @PostMapping
    public EfficiencySweepService.Session start(@RequestParam(defaultValue = "restart") String mode,
                                                @RequestBody(required = false) StartRequest request) {
        List<String> coins = request == null ? null : request.coins();
        List<String> workers = request == null ? null : request.workers();
        if (workers == null || workers.isEmpty()) throw new IllegalArgumentException("benchmark_select_gpu_for_sweep");
        if (!List.of("restart", "resume").contains(mode)) throw new IllegalArgumentException("lab_start_mode_invalid");
        EfficiencySweepService.Session session = request.preset() != null || Boolean.TRUE.equals(request.autoStartMining())
                ? sweep.startConfigured("resume".equals(mode), coins, workers, request.maxConcurrentGpus(),
                    Boolean.TRUE.equals(request.autoTuneAfterSweep()), request.preset(), Boolean.TRUE.equals(request.autoStartMining()))
                : switch (mode) {
            case "restart" -> Boolean.TRUE.equals(request.autoTuneAfterSweep())
                    ? sweep.start(coins, workers, request.maxConcurrentGpus(), true) : sweep.start(coins, workers, request.maxConcurrentGpus());
            case "resume" -> Boolean.TRUE.equals(request.autoTuneAfterSweep())
                    ? sweep.resume(coins, workers, request.maxConcurrentGpus(), true) : sweep.resume(coins, workers, request.maxConcurrentGpus());
            default -> throw new IllegalArgumentException("Unbekannter Sweep-Startmodus");
        };
        // The sweep pauses/restores miners and rewrites power limits: wake its own channel
        // plus the miner/power views it disturbs (loading plan P3).
        if (status != null) status.invalidate("efficiency", "benchmarks", "overview", "power-control", "workers");
        return session;
    }

    public record StartRequest(List<String> coins, List<String> workers, Integer maxConcurrentGpus, Boolean autoTuneAfterSweep,
                               String preset, Boolean autoStartMining) {
        public StartRequest(List<String> coins, List<String> workers, Integer limit, Boolean refine) { this(coins, workers, limit, refine, null, false); }
        public StartRequest(List<String> coins, List<String> workers, Integer maxConcurrentGpus) { this(coins, workers, maxConcurrentGpus, false); }
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> startRejected(RuntimeException exception) {
        String message = exception.getMessage() == null
                ? "Power-Limit-Test konnte nicht gestartet werden" : exception.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", message));
    }

    @PostMapping("/cancel")
    public EfficiencySweepService.Session cancel() {
        EfficiencySweepService.Session session = sweep.cancel();
        if (status != null) status.invalidate("efficiency", "benchmarks", "overview", "power-control", "workers");
        return session;
    }

    @GetMapping("/profiles")
    public List<GpuEfficiencyStore.Profile> profiles() {
        return sweep.profiles();
    }

    @DeleteMapping("/profiles")
    public ResponseEntity<Void> deleteProfile(@RequestBody GpuEfficiencyStore.Profile profile) {
        if (!sweep.deleteProfile(profile)) return ResponseEntity.notFound().build();
        if (status != null) status.invalidate("efficiency", "benchmarks", "overview", "workers", "power-control");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/profiles/storage")
    public GpuEfficiencyStore.StorageStatus profileStorage() { return sweep.profileStorageStatus(); }
}
