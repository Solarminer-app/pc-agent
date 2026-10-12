package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.miner.CoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.miner.RandomXCapability;
import de.verdox.solarminer.pcagent.mining.MiningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Program-controlled, algorithm-specific mining settings exposed to the local agent UI
 * (refactoring Phase 5d). The logic lives in the coin adapter behind
 * {@link RandomXCapability}; this controller only resolves the capability through the
 * {@link MinerFactory} and keeps process-restart ownership in the orchestrator, so it
 * contains no coin or algorithm branching of its own.
 */
@RestController
@RequestMapping("/api/agent/local/miners/{coin}/optimizations")
public class MinerOptimizationController {
    private final MinerFactory miners;
    private final MiningService mining;
    private final AgentStatusService agentStatus;

    public MinerOptimizationController(MinerFactory miners, MiningService mining, AgentStatusService agentStatus) {
        this.miners = miners;
        this.mining = mining;
        this.agentStatus = agentStatus;
    }

    @GetMapping
    public ResponseEntity<?> status(@PathVariable String coin) {
        RandomXCapability capability = capability(coin);
        return capability == null ? unsupported(coin) : ResponseEntity.ok(capability.randomXStatus());
    }

    @PostMapping("/{setting}")
    public ResponseEntity<?> activate(@PathVariable String coin, @PathVariable String setting,
                                      @RequestBody Toggle request) throws IOException, InterruptedException {
        if (request == null) throw new IllegalArgumentException("Optimierungseinstellung fehlt");
        RandomXCapability capability = capability(coin);
        if (capability == null) return unsupported(coin);
        CoinMiner miner = miners.miner(coin).orElseThrow();
        boolean wasRunning = miner.running();
        RandomXCapability.Status applied = capability.applyRandomXOptimization(setting, request.enabled());
        if (wasRunning && capability.restartRequiredAfterToggle(request.enabled())) {
            mining.pauseMining(coin);
            mining.resumeMining(coin);
        }
        if (agentStatus != null) agentStatus.invalidate("overview", "workers", "miner-options");
        return ResponseEntity.ok(applied);
    }

    /** The adapter for this coin, or null when it exposes no RandomX tuning. */
    private RandomXCapability capability(String coin) {
        CoinMiner miner = miners.miner(coin).orElse(null);
        return miner instanceof RandomXCapability capability ? capability : null;
    }

    private ResponseEntity<Map<String, String>> unsupported(String coin) {
        return ResponseEntity.status(404).body(Map.of("message",
                "Diese Mining-Optimierung wird von diesem Miner nicht unterstützt"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({IOException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> activationFailed(Exception exception) {
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        String message = exception.getMessage() == null ? "Die Mining-Optimierung konnte nicht geändert werden" : exception.getMessage();
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    public record Toggle(boolean enabled) { }
}
