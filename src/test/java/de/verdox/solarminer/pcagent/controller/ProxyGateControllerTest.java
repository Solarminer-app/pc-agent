package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.ManagedProxyService;
import de.verdox.solarminer.pcagent.mining.ProxyConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ProxyGateControllerTest {
    @Test
    void servesTheManagedProxyLogIncrementally() throws Exception {
        ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
        when(proxy.managedProxyLog(eq(12L)))
                .thenReturn(new ManagedProxyService.ProxyLogChunk(18, "proxy started\\n", false));
        MockMvc api = standaloneSetup(new ProxyGateController(proxy)).build();

        api.perform(get("/api/agent/local/proxy-gate/log").param("offset", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextOffset").value(18))
                .andExpect(jsonPath("$.data").value("proxy started\n"));
    }
}
