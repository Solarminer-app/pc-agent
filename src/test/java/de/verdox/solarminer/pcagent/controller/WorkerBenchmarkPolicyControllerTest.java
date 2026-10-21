package de.verdox.solarminer.pcagent.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.mining.measurement.*;
import de.verdox.solarminer.pcagent.mining.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WorkerBenchmarkPolicyControllerTest {
    @TempDir Path directory;
    @Test void rejectedUpdateDoesNotReleaseAnotherRunsLock() {
        var policies = new WorkerBenchmarkPolicyService(new ObjectMapper(), directory.resolve("policies.json").toString());
        var lock = new LocalRunLock();
        var assignments = mock(WorkerAssignmentService.class);
        var worker = mock(WorkerAssignmentService.WorkerView.class);
        when(worker.deviceId()).thenReturn("gpu"); when(assignments.workers()).thenReturn(List.of(worker));
        var controller = new WorkerBenchmarkPolicyController(policies, lock, assignments, mock(MinerCatalogService.class));
        var policy = new WorkerBenchmarkPolicyService.Policy(List.of(), false, true, 72);
        assertTrue(lock.tryBegin("sweep"));
        assertThrows(IllegalStateException.class, () -> controller.save("gpu", policy));
        assertEquals("sweep", lock.holder()); assertTrue(policies.all().isEmpty());
        lock.end(); assertEquals(policy, controller.save("gpu", policy)); assertFalse(lock.busy());
        assertThrows(IllegalArgumentException.class, () -> controller.save("missing", policy)); assertFalse(lock.busy());
    }
}
