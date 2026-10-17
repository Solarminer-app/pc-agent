package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.economics.CoinHoppingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only measured operating points for the benchmark comparison. */
@RestController
@RequestMapping("/api/agent/local/benchmarks/comparison")
public class BenchmarkComparisonController {
    private final CoinHoppingService candidates;

    public BenchmarkComparisonController(CoinHoppingService candidates) { this.candidates = candidates; }

    @GetMapping
    public List<CoinHoppingService.Candidate> compare(@RequestParam String deviceId) {
        return candidates.candidates(deviceId, 0);
    }
}
