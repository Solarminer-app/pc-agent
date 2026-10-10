package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.coin.SweepMode;
import de.verdox.solarminer.pcagent.coin.SweepRunState;
import de.verdox.solarminer.pcagent.mining.EfficiencySweepService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class EfficiencySweepControllerTest {
    @Test
    void statusExposesQueueLiveSamplesAndEta() throws Exception {
        EfficiencySweepService sweep = mock(EfficiencySweepService.class);
        var run = new EfficiencySweepService.RunStatus("gpu|pearl", "GPU-one", "TITAN RTX", "pearl",
                "PearlHash", SweepMode.FULL, SweepRunState.RUNNING, 170, List.of(200, 185, 170), List.of(), 4, 12,
                0, "TITAN RTX · PearlHash · 170 W");
        when(sweep.status()).thenReturn(new EfficiencySweepService.Session(true, "170 W", Instant.now(),
                0, 2, List.of(), List.of(run), 240L));
        MockMvc api = standaloneSetup(new EfficiencySweepController(sweep)).build();

        api.perform(get("/api/agent/local/efficiency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secondsRemaining").value(240))
                .andExpect(jsonPath("$.runs[0].referenceBatch").value(0))
                .andExpect(jsonPath("$.runs[0].plannedLimits[1]").value(185))
                .andExpect(jsonPath("$.runs[0].samples").value(4));
    }

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

    @Test
    void resumeModeUsesTheSavedSweepCheckpoint() throws Exception {
        EfficiencySweepService sweep = mock(EfficiencySweepService.class);
        when(sweep.resume()).thenReturn(new EfficiencySweepService.Session(false, "Idle", null,
                0, 0, List.of(), List.of(), 0L));
        MockMvc api = standaloneSetup(new EfficiencySweepController(sweep)).build();

        api.perform(post("/api/agent/local/efficiency").queryParam("mode", "resume"))
                .andExpect(status().isOk());

        verify(sweep).resume();
    }
}
