package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.agent.NodeAssessmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
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
    private final AgentStatusService status;

    public NodeAssessmentController(NodeAssessmentService assessments, AgentControlSettingsService controls,
                                    @org.springframework.context.annotation.Lazy AgentStatusService status) {
        this.assessments = assessments;
        this.controls = controls;
        this.status = status;
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
        // The Node pushes assessments over /external/**; the local dashboard still learns
        // about them via an immediate section re-evaluation (loading plan P3/P6 safety net).
        if (status != null) status.invalidate("node-assessment", "overview");
    }
}
