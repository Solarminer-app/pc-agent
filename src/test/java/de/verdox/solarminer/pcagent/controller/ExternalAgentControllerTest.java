package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.fee.FeeTierService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ExternalAgentControllerTest {
    private final MiningController mining = mock(MiningController.class);
    private final AgentPowerController power = mock(AgentPowerController.class);
    private final TelemetryController telemetry = mock(TelemetryController.class);
    private final NodeAssessmentController assessment = mock(NodeAssessmentController.class);
    private final AgentControlSettingsService controls = mock(AgentControlSettingsService.class);

    private MockMvc api(boolean externalEnabled) {
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(
                true, externalEnabled, Map.of(), Map.of()));
        return standaloneSetup(new ExternalAgentController(mining, power, telemetry, assessment))
                .addFilters(new AgentWriteAccessFilter(controls, mock(FeeTierService.class))).build();
    }

    @Test
    void disabledExternalControlAllowsMonitoringButRejectsWrites() throws Exception {
        MockMvc api = api(false);
        api.perform(get("/api/agent/external/status")).andExpect(status().isOk());
        api.perform(get("/api/agent/external/power-control")).andExpect(status().isOk());
        api.perform(get("/api/agent/external/telemetry")).andExpect(status().isOk());
        api.perform(post("/api/agent/external/pause")).andExpect(status().isForbidden());
        verify(mining).getMiningStats();
        verify(power).externalStatus();
        verify(telemetry).telemetry();
        verifyNoInteractions(assessment);
    }

    @Test
    void enabledExternalControlUsesOnlyDedicatedExternalRoutes() throws Exception {
        when(power.externalPause()).thenReturn(true);
        MockMvc api = api(true);

        api.perform(post("/api/agent/external/pause"))
                .andExpect(status().isOk()).andExpect(content().string("true"));
        api.perform(post("/api/agent/pause")).andExpect(status().isForbidden());

        verify(power).externalPause();
    }
}
