package de.verdox.solarminer.pcagent.console;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleCommandServiceTest {
    private final ConsoleWorkspaceService workspace = mock(ConsoleWorkspaceService.class);
    private final ConsoleWorkerOperations operations = mock(ConsoleWorkerOperations.class);
    private final ConsoleCommandService commands = new ConsoleCommandService(workspace, operations);
    private final ConsoleCommandService.Target target = new ConsoleCommandService.Target("own", "gpu");
    @AfterEach void close() { commands.close(); }

    private static ConsoleWorkspaceService.Worker row(String id, String block) {
        return new ConsoleWorkspaceService.Worker("own", id, true, "RTX", "GPU", "pearl", "PearlHash", "PeakMiner",
                "STOPPED", null, null, null, null, null, "MANUAL", null, false, false, Instant.now(), block, block);
    }
    private ConsoleCommandService.Job completed(String id) throws Exception {
        // A bounded wait asserts an observable job result rather than an implementation timing.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var job = commands.get(id).orElseThrow();
            if (job.complete()) return job;
            Thread.sleep(5);
        }
        throw new AssertionError("Job did not complete");
    }

    @Test void duplicateAdmissionIsRejectedWhileAcceptedJobRuns() throws Exception {
        when(workspace.find("own", "gpu")).thenReturn(Optional.of(row("gpu", null)));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(operations.apply(target, true)).thenAnswer(invocation -> { entered.countDown(); release.await(2, TimeUnit.SECONDS); return null; });
        var request = new ConsoleCommandService.Request("start", List.of(target));
        var job = commands.submit(request);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> commands.submit(request)).hasMessage("console_already_pending");
        } finally { release.countDown(); }
        assertThat(completed(job.id()).results().getFirst().state()).isEqualTo("succeeded");
        verify(operations, times(1)).apply(target, true);
    }

    @Test void mixedResultsPreservePermissionFailureAndContinueOtherTargets() throws Exception {
        var other = new ConsoleCommandService.Target("own", "other");
        when(workspace.find("own", "gpu")).thenReturn(Optional.of(row("gpu", "console_stale")));
        when(workspace.find("own", "other")).thenReturn(Optional.of(row("other", null)));
        var job = completed(commands.submit(new ConsoleCommandService.Request("start", List.of(target, other))).id());
        assertThat(job.results().getFirst().error()).isEqualTo("console_stale");
        assertThat(job.results().get(1).state()).isEqualTo("succeeded");
        verify(operations, never()).apply(target, true);
        verify(operations).apply(other, true);
    }

    @Test void invalidOrDuplicateTargetsNeverReachDomainServices() {
        assertThatThrownBy(() -> commands.submit(new ConsoleCommandService.Request("restart", List.of(target))))
                .hasMessage("console_invalid_command");
        assertThatThrownBy(() -> commands.submit(new ConsoleCommandService.Request("start", List.of(target, target))))
                .hasMessage("console_invalid_command");
        assertThatThrownBy(() -> commands.submit(new ConsoleCommandService.Request("start", List.of())))
                .hasMessage("console_invalid_command");
        verifyNoInteractions(operations);
    }

    @Test void serviceExceptionsBecomeIndividualFailureWithoutClaimingSuccess() throws Exception {
        when(workspace.find("own", "gpu")).thenReturn(Optional.of(row("gpu", null)));
        when(operations.apply(target, false)).thenThrow(new IllegalStateException("Driver declined"));
        var job = completed(commands.submit(new ConsoleCommandService.Request("pause", List.of(target))).id());
        assertThat(job.results().getFirst().state()).isEqualTo("failed");
        assertThat(job.results().getFirst().detail()).isEqualTo("Driver declined");
    }
}
