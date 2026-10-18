package de.verdox.solarminer.pcagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentEventControllerTest {

    @Test
    void failedSseSendDoesNotCompleteAnAlreadyFailedAsyncContext() throws Exception {
        AgentEventController controller = new AgentEventController(
                mock(WorkerController.class), mock(BenchmarkController.class),
                mock(AgentStatusService.class), mock(EfficiencySweepController.class), new ObjectMapper());
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("AsyncListener.onError already returned"))
                .when(emitter).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        try {
            controller.send(emitter, "efficiency", "{}");
            verify(emitter, never()).complete();
        } finally {
            controller.stopInitialSnapshotExecutor();
        }
    }

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
