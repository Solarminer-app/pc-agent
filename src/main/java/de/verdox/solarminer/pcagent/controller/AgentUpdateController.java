package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.update.AgentUpdateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/agent/local/update")
public class AgentUpdateController {
    private final AgentUpdateService updates;
    public AgentUpdateController(AgentUpdateService updates) { this.updates = updates; }

    @GetMapping public AgentUpdateService.Status status() { return updates.status(); }

    @PostMapping("/check") public AgentUpdateService.Status check() { updates.check(); return updates.status(); }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AgentUpdateService.Status start(HttpServletRequest request) {
        if (!updates.start(request.getRemoteAddr())) throw new IllegalStateException("update_unavailable");
        return updates.status();
    }
}
