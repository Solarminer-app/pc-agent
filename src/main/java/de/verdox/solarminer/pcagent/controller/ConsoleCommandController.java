package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.console.ConsoleCommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agent/local/operations/commands")
public class ConsoleCommandController {
    private final ConsoleCommandService commands;
    public ConsoleCommandController(ConsoleCommandService commands) { this.commands = commands; }

    @PostMapping
    public ResponseEntity<ConsoleCommandService.Job> submit(@RequestBody ConsoleCommandService.Request request) {
        var job = commands.submit(request);
        return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .header("Location", "/api/agent/local/operations/commands/" + job.id()).body(job);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConsoleCommandService.Job> get(@PathVariable String id) {
        return commands.get(id).map(job -> ResponseEntity.ok().header("Cache-Control", "no-store").body(job))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(ConsoleCommandService.Rejected.class)
    public ResponseEntity<Map<String, String>> rejected(ConsoleCommandService.Rejected failure) {
        return ResponseEntity.status(failure.conflict() ? 409 : 400).body(Map.of("error", failure.getMessage()));
    }
}
