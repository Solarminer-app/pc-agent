package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.MinerConsoleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MinerConsoleControllerTest {
    @Test
    void servesGpuCoinAggregateAndDeviceConsoles() throws Exception {
        MinerConsoleService consoles = mock(MinerConsoleService.class);
        MockMvc api = standaloneSetup(new MinerConsoleController(consoles)).build();
        for (String name : new String[]{"ravencoin", "ravencoin-NVIDIA-0",
                "ethereumclassic", "ethereumclassic-AMD-1"}) {
            when(consoles.read(eq(name), eq(0L)))
                    .thenReturn(new MinerConsoleService.ConsoleChunk(0, "run", "", false));
            api.perform(get("/api/agent/local/console/{name}", name)).andExpect(status().isOk());
        }
        api.perform(get("/api/agent/local/console/unknown")).andExpect(status().isNotFound());
    }
}
