package de.verdox.solarminer.pcagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerBootSettingControllerTest {
    @TempDir Path directory;

    @Test
    void localPauseSuppressesBootAndManualStartRearms() {
        AgentControlSettingsService controls = new AgentControlSettingsService(new ObjectMapper(),
                directory.resolve("control.json").toString());
        assertTrue(controls.setWorkerAssignment("cpu", "monero", "xmrig"));
        WorkerAssignmentService workers = mock(WorkerAssignmentService.class);
        when(workers.workers()).thenReturn(List.of(new WorkerAssignmentService.WorkerView(
                "cpu", "CPU", "CPU", null, null, "monero", "Monero", "randomx", "xmrig", "XMRig",
                true, true, null, false, MinerStats.MinerStatus.STOPPED, null)));
        when(workers.pause("cpu")).thenReturn(true);
        when(workers.start("cpu")).thenReturn(true);
        MiningService mining = mock(MiningService.class);
        when(mining.beginWorkerStart("cpu")).thenReturn(true);
        WorkerController controller = new WorkerController(workers, controls, mock(LocalGpuPowerService.class),
                mock(MinerConsoleService.class), mock(AgentStatusService.class));
        controller.setMining(mining);

        controller.startOnBoot("cpu", true);
        assertTrue(controls.get().startsOnBoot("cpu"));
        assertTrue(controller.pause("cpu"));
        assertTrue(controls.get().manuallyStopped("cpu"));
        assertTrue(controller.start("cpu"));
        assertFalse(controls.get().manuallyStopped("cpu"));
        assertTrue(controls.get().startsOnBoot("cpu"));
    }
}
