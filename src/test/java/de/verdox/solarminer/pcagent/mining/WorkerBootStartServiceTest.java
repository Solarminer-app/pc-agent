package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.proxy.ManagedProxyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerBootStartServiceTest {
    @TempDir Path directory;

    private AgentControlSettingsService controls() {
        return new AgentControlSettingsService(new ObjectMapper(), directory.resolve("control.json").toString());
    }

    @Test
    void manualStopSurvivesRestartAndManualStartRearms() {
        AgentControlSettingsService controls = controls();
        assertTrue(controls.setWorkerAssignment("cpu", "monero", "xmrig"));
        assertTrue(controls.setWorkerStartOnBoot("cpu", true));
        assertTrue(controls.setWorkerManuallyStopped("cpu", true));

        AgentControlSettingsService reloaded = controls();
        assertTrue(reloaded.get().startsOnBoot("cpu"));
        assertTrue(reloaded.get().manuallyStopped("cpu"));
        assertTrue(reloaded.update(new AgentControlSettingsService.Settings(true, false, Map.of())));
        assertTrue(controls().get().manuallyStopped("cpu"), "ordinary settings writes must preserve pause intent");

        assertTrue(reloaded.setWorkerManuallyStopped("cpu", false));
        assertTrue(controls().get().startsOnBoot("cpu"));
        assertFalse(controls().get().manuallyStopped("cpu"));
    }

    @Test
    void startsOnlyBootSnapshotAfterProxyReadyAndHonorsLaterManualStop() {
        AgentControlSettingsService controls = controls();
        assertTrue(controls.setWorkerAssignment("cpu", "monero", "xmrig"));
        assertTrue(controls.setWorkerStartOnBoot("cpu", true));
        WorkerAssignmentService workers = mock(WorkerAssignmentService.class);
        MiningService mining = mock(MiningService.class);
        ManagedProxyService proxy = mock(ManagedProxyService.class);
        when(workers.workers()).thenReturn(List.of(worker("cpu")));
        when(mining.beginWorkerStart("cpu")).thenReturn(true);
        when(workers.start("cpu")).thenReturn(true);
        WorkerBootStartService boot = new WorkerBootStartService(controls, workers, mining, proxy);
        boot.captureBootIntent();

        boot.startPendingWorkers();
        verifyNoInteractions(workers);
        when(proxy.gateOpen()).thenReturn(true);
        assertTrue(controls.setWorkerManuallyStopped("cpu", true));
        boot.startPendingWorkers();
        verifyNoInteractions(workers);
        assertTrue(controls.setWorkerManuallyStopped("cpu", false));
        boot.startPendingWorkers();
        verifyNoInteractions(workers); // A manual stop removes this worker from the boot queue.

        boot.captureBootIntent();
        boot.startPendingWorkers();
        verify(workers).start("cpu");
        verify(mining).endWorkerStart("cpu");
        boot.startPendingWorkers();
        verify(workers, times(1)).start("cpu");
    }

    private static WorkerAssignmentService.WorkerView worker(String deviceId) {
        return new WorkerAssignmentService.WorkerView(deviceId, "CPU", "CPU", null, null,
                "monero", "Monero", "randomx", "xmrig", "XMRig", true, true, null,
                false, MinerStats.MinerStatus.STOPPED, null);
    }
}
