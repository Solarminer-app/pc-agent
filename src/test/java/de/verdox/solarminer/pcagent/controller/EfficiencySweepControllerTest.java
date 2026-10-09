package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.EfficiencySweepService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class EfficiencySweepControllerTest {
    @Test
    void rejectedStartReturnsTheActionableReasonToTheDashboard() throws Exception {
        EfficiencySweepService sweep = mock(EfficiencySweepService.class);
        when(sweep.start()).thenThrow(new IllegalStateException(
                "Keine GPU-Leistungsgrenze ist schreibbar: Insufficient Permissions"));
        MockMvc api = standaloneSetup(new EfficiencySweepController(sweep)).build();

        api.perform(post("/api/agent/local/efficiency"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Keine GPU-Leistungsgrenze ist schreibbar: Insufficient Permissions"));
    }
}
