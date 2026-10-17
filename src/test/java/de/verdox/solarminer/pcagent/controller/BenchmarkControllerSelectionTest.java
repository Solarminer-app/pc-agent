package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSharingService;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BenchmarkControllerSelectionTest {
    @Test
    void benchmarkRequiresWorkersAndPassesSelectedIdsToTheService() throws Exception {
        BenchmarkSessionService sessions = mock(BenchmarkSessionService.class);
        MockMvc api = standaloneSetup(new BenchmarkController(sessions, mock(BenchmarkSharingService.class),
                mock(AgentStatusService.class), mock(MiningPerformanceProfileStore.class))).build();

        api.perform(post("/api/agent/local/benchmarks").contentType("application/json")
                        .content("{\"mode\":\"INSTALLED\",\"coins\":[\"pearl\"]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sessions);

        when(sessions.start("INSTALLED", List.of("pearl"), List.of("gpu-one"), false, null))
                .thenReturn(new BenchmarkSessionService.Session(true, "INSTALLED", "Preparing",
                        null, null, null, 0, 1, List.of(), null));
        api.perform(post("/api/agent/local/benchmarks").contentType("application/json")
                        .content("{\"mode\":\"INSTALLED\",\"coins\":[\"pearl\"],\"workers\":[\"gpu-one\"]}"))
                .andExpect(status().isOk());
        verify(sessions).start("INSTALLED", List.of("pearl"), List.of("gpu-one"), false, null);
    }

    @Test
    void autoStartChoiceIsPassedWithTheSelectedWorkers() throws Exception {
        BenchmarkSessionService sessions = mock(BenchmarkSessionService.class);
        MockMvc api = standaloneSetup(new BenchmarkController(sessions, mock(BenchmarkSharingService.class),
                mock(AgentStatusService.class), mock(MiningPerformanceProfileStore.class))).build();
        when(sessions.start("INSTALLED", List.of("pearl"), List.of("gpu-one"), true, 1))
                .thenReturn(new BenchmarkSessionService.Session(true, "INSTALLED", "Preparing",
                        null, null, null, 0, 1, List.of(), null));

        api.perform(post("/api/agent/local/benchmarks").contentType("application/json")
                        .content("{\"mode\":\"INSTALLED\",\"coins\":[\"pearl\"],\"workers\":[\"gpu-one\"],\"startMiningAfterBenchmark\":true,\"maxConcurrentGpus\":1}"))
                .andExpect(status().isOk());
        verify(sessions).start("INSTALLED", List.of("pearl"), List.of("gpu-one"), true, 1);
    }
}
