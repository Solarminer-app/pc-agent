package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.coin.SweepMode;
import de.verdox.solarminer.pcagent.coin.SweepRunState;
import de.verdox.solarminer.pcagent.mining.measurement.EfficiencySweepService;
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
        MockMvc api = standaloneSetup(new EfficiencySweepController(sweep, null)).build();

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
        when(sweep.start(null, List.of("gpu-one"), null)).thenThrow(new IllegalStateException(
                "Keine GPU-Leistungsgrenze ist schreibbar: Insufficient Permissions"));
        MockMvc api = standaloneSetup(new EfficiencySweepController(sweep, null)).build();

        api.perform(post("/api/agent/local/efficiency").contentType("application/json")
                        .content("{\"workers\":[\"gpu-one\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Keine GPU-Leistungsgrenze ist schreibbar: Insufficient Permissions"));
    }

    @Test
    void resumeModeUsesTheSavedSweepCheckpoint() throws Exception {
        EfficiencySweepService sweep = mock(EfficiencySweepService.class);
        when(sweep.resume(null, List.of("gpu-one"), null)).thenReturn(new EfficiencySweepService.Session(false, "Idle", null,
                0, 0, List.of(), List.of(), 0L));
        MockMvc api = standaloneSetup(new EfficiencySweepController(sweep, null)).build();

        api.perform(post("/api/agent/local/efficiency").queryParam("mode", "resume")
                        .contentType("application/json").content("{\"workers\":[\"gpu-one\"]}"))
                .andExpect(status().isOk());

        verify(sweep).resume(null, List.of("gpu-one"), null);
    }

    @Test
    void coinSelectionIsPassedThroughToTheSweepAndUnknownCoinsAreRejected() throws Exception {
        EfficiencySweepService sweep = mock(EfficiencySweepService.class);
        when(sweep.start(List.of("pearl"), List.of("gpu-one"), null)).thenReturn(new EfficiencySweepService.Session(true, "Vorbereitung",
                Instant.now(), 0, 1, List.of(), List.of(), 0L));
        when(sweep.start(List.of("notacoin"), List.of("gpu-one"), null)).thenThrow(new IllegalArgumentException("Unknown benchmark coin: notacoin"));
        MockMvc api = standaloneSetup(new EfficiencySweepController(sweep, null)).build();

        api.perform(post("/api/agent/local/efficiency")
                        .contentType("application/json")
                        .content("{\"coins\":[\"pearl\"],\"workers\":[\"gpu-one\"]}"))
                .andExpect(status().isOk());
        verify(sweep).start(List.of("pearl"), List.of("gpu-one"), null);

        api.perform(post("/api/agent/local/efficiency")
                        .contentType("application/json")
                        .content("{\"coins\":[\"notacoin\"],\"workers\":[\"gpu-one\"]}"))
                .andExpect(status().isConflict());

        api.perform(post("/api/agent/local/efficiency").contentType("application/json")
                        .content("{\"coins\":[\"pearl\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("benchmark_select_gpu_for_sweep"));
    }
    @Test
    void optimizationOptionStartsAndResumesTheCombinedRunAndExposesProgress() throws Exception {
        var sweep = mock(EfficiencySweepService.class);
        var optimization = new de.verdox.solarminer.pcagent.mining.measurement.SweepRefinementService.Status(true,
                List.of(new de.verdox.solarminer.pcagent.mining.measurement.SweepRefinementService.Progress(
                        "gpu|pearl|srbminer-multi", "gpu", "pearl", "srbminer-multi", "RUNNING", "auto_tuning_phase_confirming", 19, 24, 5, 32)));
        var session = new EfficiencySweepService.Session(true, "benchmark_optimization_tuning", Instant.now(), 1, 1, List.of(), List.of(), null, optimization);
        when(sweep.start(List.of("pearl"), List.of("gpu"), 1, true)).thenReturn(session);
        when(sweep.resume(List.of("pearl"), List.of("gpu"), 1, true)).thenReturn(session);
        MockMvc api = standaloneSetup(new EfficiencySweepController(sweep, null)).build();
        for (String mode : List.of("restart", "resume")) api.perform(post("/api/agent/local/efficiency")
                        .queryParam("mode", mode).contentType("application/json")
                        .content("{\"coins\":[\"pearl\"],\"workers\":[\"gpu\"],\"maxConcurrentGpus\":1,\"autoTuneAfterSweep\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.optimization.enabled").value(true))
                .andExpect(jsonPath("$.optimization.runs[0].sampleCount").value(32));
        verify(sweep).start(List.of("pearl"), List.of("gpu"), 1, true);
        verify(sweep).resume(List.of("pearl"), List.of("gpu"), 1, true);
    }

}
