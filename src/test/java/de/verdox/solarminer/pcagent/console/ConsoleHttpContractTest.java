package de.verdox.solarminer.pcagent.console;

import de.verdox.solarminer.pcagent.controller.*;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.fee.FeeTierService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConsoleHttpContractTest {
    @Test void newApiRetainsSameOriginBoundaryAndUsesAcceptedJobContract() throws Exception {
        var commands = mock(ConsoleCommandService.class);
        var settings = mock(AgentControlSettingsService.class);
        var job = new ConsoleCommandService.Job("test-job", "start", Instant.now(), false,
                List.of(new ConsoleCommandService.Result(new ConsoleCommandService.Target("own", "gpu"), "queued", null, null)));
        when(commands.submit(any())).thenReturn(job);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConsoleCommandController(commands))
                .addFilters(new AgentWriteAccessFilter(settings, mock(FeeTierService.class))).build();
        String body = "{\"action\":\"start\",\"targets\":[{\"agentId\":\"own\",\"deviceId\":\"gpu\"}]}";
        mvc.perform(post("/api/agent/local/operations/commands").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(commands);
        mvc.perform(post("/api/agent/local/operations/commands").header("Origin", "http://localhost")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/agent/local/operations/commands/test-job"))
                .andExpect(jsonPath("$.complete").value(false)).andExpect(jsonPath("$.results[0].state").value("queued"));
        mvc.perform(get("/api/agent/local/operations/commands/missing").header("Referer", "http://localhost/console/index.html"))
                .andExpect(status().isNotFound());
    }

    @Test void settingsOnlyChangeExplicitLocalPermissionsAndPreserveWorkerAssignments() throws Exception {
        var controls = mock(AgentControlSettingsService.class);
        var identity = mock(AgentIdentityService.class);
        var previous = new AgentControlSettingsService.Settings(true, false, Map.of("gpu", false), Map.of("gpu", "pearl"),
                Map.of("gpu", "FIXED"), false, true);
        when(controls.get()).thenReturn(previous); when(controls.update(any())).thenReturn(true); when(identity.rename("Rig")).thenReturn(true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConsoleConfigurationController(mock(MinerCatalogService.class), controls, identity)).build();
        mvc.perform(put("/api/agent/local/operations/configuration").contentType("application/json")
                        .content("{\"name\":\"Rig\",\"sharing\":true,\"externalControl\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("console_settings_saved"));
        var saved = ArgumentCaptor.forClass(AgentControlSettingsService.Settings.class);
        verify(controls).update(saved.capture());
        assertThat(saved.getValue().workerCoins()).isEqualTo(previous.workerCoins());
        assertThat(saved.getValue().workerExternalControl()).isEqualTo(previous.workerExternalControl());
        assertThat(saved.getValue().walletTargetSharingEnabled()).isTrue();
        assertThat(saved.getValue().networkSharingActive()).isTrue();
        assertThat(saved.getValue().externalControlEnabled()).isTrue();
        mvc.perform(put("/api/agent/local/operations/configuration").contentType("application/json")
                        .content("{\"name\":\"bad\\nname\",\"sharing\":false,\"externalControl\":false}"))
                .andExpect(status().isBadRequest());
        verify(controls, times(1)).update(any());
    }
}
