package de.verdox.solarminer.pcagent.console;

import de.verdox.solarminer.pcagent.controller.AgentWriteAccessFilter;
import de.verdox.solarminer.pcagent.controller.ConsoleToolsController;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.fee.FeeTierService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConsoleToolsHttpTest {
    @Test void newToolPathsKeepSameOriginAccessAndExposeAcceptedJobs() throws Exception {
        var reads=mock(ConsoleToolsReadService.class);var jobs=mock(ConsoleToolJobs.class);var logs=mock(MinerConsoleService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ConsoleToolsController(reads,jobs,logs))
                .addFilters(new AgentWriteAccessFilter(mock(AgentControlSettingsService.class),mock(FeeTierService.class))).build();
        mvc.perform(get("/api/agent/local/operations/tools/state/updates")).andExpect(status().isForbidden());
        verifyNoInteractions(reads);
        when(reads.read("updates",null)).thenReturn(new ConsoleToolsReadService.Snapshot(null,null,null));
        mvc.perform(get("/api/agent/local/operations/tools/state/updates").header("Referer","http://localhost/console/tools.html"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        when(jobs.submit(any())).thenReturn(new ConsoleToolJobs.Job("test-request-123456","update_check",Instant.now(),"queued",null,null));
        mvc.perform(post("/api/agent/local/operations/tools/tasks").header("Origin","http://localhost").contentType("application/json")
                        .content("{\"id\":\"test-request-123456\",\"action\":\"update_check\",\"arguments\":{}}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("queued"));
        mvc.perform(get("/api/agent/local/operations/tools/logs").header("Referer","http://localhost/console/tools.html").param("console","../../secrets"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("tool_invalid_request"));
        verifyNoInteractions(logs);
    }
}
