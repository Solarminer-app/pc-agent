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
        EfficiencySweepService.Session session = switch (mode) {
            case "restart" -> sweep.start(coins);
            case "resume" -> sweep.resume(coins);
            default -> throw new IllegalArgumentException("Unbekannter Sweep-Startmodus");
        };
        // The sweep pauses/restores miners and rewrites power limits: wake its own channel
        // plus the miner/power views it disturbs (loading plan P3).
        if (status != null) status.invalidate("efficiency", "benchmarks", "overview", "power-control", "workers");
        return session;
    }

    public record StartRequest(List<String> coins) { }

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
}
