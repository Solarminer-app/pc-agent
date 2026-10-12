package de.verdox.solarminer.pcagent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate local read model endpoint (refactoring Phase 5b, owner decision O3). One GET
 * returns every dashboard section with its own generatedAt/stale flag plus the derived
 * ui section (alerts/summary); the same sections arrive push-based on the SSE "status"
 * channel. Local dashboard contract only — the Node-facing /external/** surface is
 * untouched.
 */
@RestController
@RequestMapping("/api/agent/local")
public class AgentStatusController {
    private final AgentStatusService status;

    public AgentStatusController(AgentStatusService status) {
        this.status = status;
    }

    @GetMapping("/status")
    public AgentStatusService.Aggregate status(@RequestParam(name = "sections", required = false) String sections) {
        if (sections == null || sections.isBlank()) return status.aggregate();
        return status.aggregate(java.util.Arrays.stream(sections.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList());
    }

    /** The derived alerts/summary section alone — the lightest read for the status strip. */
    @GetMapping("/status/ui")
    public AgentStatusService.UiState ui() { return status.uiState(); }

    /** Closed-enum coin catalog (id/ticker/name/algorithm/device) for UI coin rendering. */
    @GetMapping("/status/coin-catalog")
    public java.util.List<AgentStatusService.CoinRow> coinCatalog() { return AgentStatusService.coinCatalog(); }

    /**
     * Local diagnostics for the loading plan (P6): per-section TTL and how often the
     * section producer actually ran since startup. Not consumed by the UI; used to
     * measure evaluation counts before/after the event-driven re-evaluation.
     */
    @GetMapping("/status/stats")
    public java.util.List<AgentStatusService.SectionStats> stats() { return status.sectionStats(); }
}
