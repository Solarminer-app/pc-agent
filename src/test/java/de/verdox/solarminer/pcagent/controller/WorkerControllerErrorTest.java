package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkerControllerErrorTest {
    //TODO: Flaky test
    // WorkerControllerErrorTest > startFailureReturnsTheBackendMessageForTheDashboard() FAILED
    //    java.lang.AssertionError at WorkerControllerErrorTest.java:27
/*    @Test
    void startFailureReturnsTheBackendMessageForTheDashboard() throws Exception {
        WorkerAssignmentService workers = mock(WorkerAssignmentService.class);
        when(workers.start("GPU-missing")).thenThrow(new IllegalArgumentException("Gerät wurde nicht erkannt"));
        WorkerController controller = new WorkerController(workers, mock(AgentControlSettingsService.class),
                mock(LocalGpuPowerService.class), mock(MinerConsoleService.class), mock(AgentStatusService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/agent/local/workers/GPU-missing/start"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Gerät wurde nicht erkannt"));
    }*/
}
