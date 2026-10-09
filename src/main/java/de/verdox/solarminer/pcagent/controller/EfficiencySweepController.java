package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.EfficiencySweepService;
import de.verdox.solarminer.pcagent.mining.GpuEfficiencyStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/local/efficiency")
public class EfficiencySweepController {
    private final EfficiencySweepService sweep;

    public EfficiencySweepController(EfficiencySweepService sweep) {
        this.sweep = sweep;
    }

    @GetMapping
    public EfficiencySweepService.Session status() {
        return sweep.status();
    }

    @PostMapping
    public EfficiencySweepService.Session start() {
        return sweep.start();
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> startRejected(IllegalStateException exception) {
        String message = exception.getMessage() == null
                ? "Power-Limit-Test konnte nicht gestartet werden" : exception.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", message));
    }

    @PostMapping("/cancel")
    public EfficiencySweepService.Session cancel() {
        return sweep.cancel();
    }

    @GetMapping("/profiles")
    public List<GpuEfficiencyStore.Profile> profiles() {
        return sweep.profiles();
    }
}
