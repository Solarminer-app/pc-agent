package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AgentPowerControllerTest {
    @Test
    void walletTargetSharingRoutePersistsExplicitConsent() throws Exception {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        AgentControlSettingsService.Settings current = new AgentControlSettingsService.Settings(
                true, false, Map.of(), Map.of(), Map.of(), true, false);
        when(controls.get()).thenReturn(current);
        when(controls.update(any())).thenReturn(true);
        MockMvc api = standaloneSetup(new AgentPowerController(mock(MiningService.class),
                mock(LocalGpuPowerService.class), controls, mock(BenchmarkSessionService.class),
                mock(MinerConsoleService.class), mock(AgentIdentityService.class), mock(AgentStatusService.class))).build();

        api.perform(post("/api/agent/local/power-control/wallet-target-sharing").param("enabled", "true"))
                .andExpect(status().isOk());

        ArgumentCaptor<AgentControlSettingsService.Settings> saved = ArgumentCaptor.forClass(AgentControlSettingsService.Settings.class);
        verify(controls).update(saved.capture());
        assertTrue(saved.getValue().walletTargetSharingActive());
    }
}
