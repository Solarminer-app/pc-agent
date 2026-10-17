package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.network.FleetComparisonService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Cached read-only LAN scenarios; collection runs outside HTTP request threads. */
@RestController
public class FleetComparisonController {
    private final FleetComparisonService comparison;

    public FleetComparisonController(FleetComparisonService comparison) { this.comparison = comparison; }

    @GetMapping("/api/agent/external/fleet-comparison")
    public FleetComparisonService.AgentPoints sharedPoints() {
        var points = comparison.sharedPoints();
        if (points == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return points;
    }

    @GetMapping("/api/agent/local/network/fleet-comparison")
    public List<FleetComparisonService.AgentResult> fleet() { return comparison.fleet(); }
}
