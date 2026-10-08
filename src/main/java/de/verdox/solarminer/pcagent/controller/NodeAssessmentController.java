package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.NodeAssessmentService;
import de.verdox.solarminer.pcagent.mining.AgentControlSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** LAN contract for the Node's economic/PV decision; no economic calculation lives in the agent. */
@RestController
@RequestMapping("/api/agent/local/node-assessment")
public class NodeAssessmentController {
    private final NodeAssessmentService assessments;
    private final AgentControlSettingsService controls;

    public NodeAssessmentController(NodeAssessmentService assessments, AgentControlSettingsService controls) {
        this.assessments = assessments;
        this.controls = controls;
    }

    @GetMapping
    public NodeAssessmentStatus get() {
        NodeAssessmentService.Assessment assessment = assessments.get();
        return new NodeAssessmentStatus(assessment.decision(), assessment.reason(), assessment.source(),
                assessment.evaluatedAt(), assessments.isConnected());
    }

    public record NodeAssessmentStatus(String decision, String reason, String source,
                                       java.time.Instant evaluatedAt, boolean connected) { }

    public void update(@RequestBody NodeAssessmentService.Assessment assessment) {
        if (!controls.get().externalControlEnabled())
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Node-Steuerung wurde lokal nicht freigegeben");
        if (!assessments.update(assessment)) throw new IllegalArgumentException("Ungültige Node-Bewertung");
    }
}
