package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.console.*;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

/** Independent specialist API. No old controller/HTTP API is invoked. */
@RestController
@RequestMapping("/api/agent/local/operations/tools")
public class ConsoleToolsController {
    private final ConsoleToolsReadService reads;
    private final ConsoleToolJobs jobs;
    private final MinerConsoleService logs;
    public ConsoleToolsController(ConsoleToolsReadService reads,ConsoleToolJobs jobs,MinerConsoleService logs) {
        this.reads=reads; this.jobs=jobs; this.logs=logs;
    }
    @GetMapping("/state/{section}") public ResponseEntity<?> state(@PathVariable String section,@RequestParam(required=false) String device) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(reads.read(section,device));
    }
    @GetMapping("/export/{section}") public ResponseEntity<?> export(@PathVariable String section) {
        if (!ConsoleToolsReadService.SECTIONS.contains(section)) throw new IllegalArgumentException("tool_invalid_request");
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Disposition","attachment; filename=operations-"+section+".json").body(reads.export(section));
    }
    @PostMapping("/tasks") public ResponseEntity<?> command(@RequestBody ConsoleToolJobs.Request request) {
        var job=jobs.submit(request);
        return ResponseEntity.accepted().header("Cache-Control","no-store").header("Location","/api/agent/local/operations/tools/tasks/"+job.id()).body(job);
    }
    @GetMapping("/tasks/{id}") public ResponseEntity<?> task(@PathVariable String id) {
        return jobs.get(id).map(job->ResponseEntity.ok().header("Cache-Control","no-store").body(job)).orElseGet(()->ResponseEntity.notFound().build());
    }
    @GetMapping("/logs") public ResponseEntity<MinerConsoleService.ConsoleChunk> log(@RequestParam String console,@RequestParam(defaultValue="0") long offset) throws IOException {
        if (offset<0 || !MinerConsoleService.isKnownConsole(console)) throw new IllegalArgumentException("tool_invalid_request");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(logs.read(console,offset));
    }
    @GetMapping("/logs/download") public ResponseEntity<Resource> download(@RequestParam String console) {
        if (!MinerConsoleService.isKnownConsole(console)) throw new IllegalArgumentException("tool_invalid_request");
        Resource resource=new FileSystemResource(logs.file(console));
        if (!resource.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Disposition","attachment; filename="+console+".log").body(resource);
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,IOException.class})
    public ResponseEntity<Map<String,String>> failure(Exception failure) {
        String message=failure.getMessage();
        String key=message!=null && message.matches("(?:tool|console)_[a-z_]+")?message:"tool_action_failed";
        return ResponseEntity.status(failure instanceof IllegalArgumentException?400:409).body(Map.of("error",key));
    }
}
