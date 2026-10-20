package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.console.ConsoleWorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** New console API, deliberately independent of the existing page controllers. */
@RestController
@RequestMapping("/api/agent/local/operations")
public class ConsoleWorkspaceController {
    private final ConsoleWorkspaceService workspace;
    public ConsoleWorkspaceController(ConsoleWorkspaceService workspace) { this.workspace = workspace; }

    @GetMapping("/workspace")
    public ResponseEntity<ConsoleWorkspaceService.Workspace> workspace(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String agent,
            @RequestParam(defaultValue = "") String state, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(workspace.view(q.length() > 200 ? q.substring(0, 200) : q, agent, state, page, size));
    }

    @GetMapping("/worker")
    public ResponseEntity<ConsoleWorkspaceService.Worker> worker(@RequestParam String agent, @RequestParam String device) {
        return workspace.find(agent, device).map(row -> ResponseEntity.ok().header("Cache-Control", "no-store").body(row))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
