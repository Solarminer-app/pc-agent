package de.verdox.solarminer.pcagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AgentEventControllerTest {

    @Test
    void exposesSseOnlyForGenuinelyLiveDashboardState() {
        AgentEventController controller = new AgentEventController(
                mock(WorkerController.class),
                mock(BenchmarkController.class),
                mock(AgentStatusService.class),
                mock(EfficiencySweepController.class),
                new ObjectMapper());
        try {
            assertEquals(Set.of("telemetry", "workers", "benchmarks", "efficiency",
                    "benchmark-upload", "proxy-gate"), controller.channelNames());
        } finally {
            controller.stopInitialSnapshotExecutor();
        }
    }
}
