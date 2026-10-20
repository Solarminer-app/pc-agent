package de.verdox.solarminer.pcagent.console;

import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.network.AgentNetworkDiscoveryService;
import de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleWorkerOperationsTest {
    @Test void localStartUsesDomainServicesWithRunLockAndAlwaysReleasesTransientState() {
        var workers = mock(WorkerAssignmentService.class);
        var mining = mock(MiningService.class);
        var controls = mock(AgentControlSettingsService.class);
        var discovery = mock(AgentNetworkDiscoveryService.class);
        var network = mock(NetworkDashboardService.class);
        var lock = new LocalRunLock();
        when(discovery.agentId()).thenReturn("own");
        when(mining.beginWorkerStart("GPU-0")).thenReturn(true);
        when(workers.workers()).thenReturn(List.of(ConsoleWorkspaceServiceTest.worker(0, null)));
        when(controls.get()).thenReturn(mock(AgentControlSettingsService.Settings.class));
        when(workers.startWithHeldRunLock("GPU-0")).thenAnswer(invocation -> {
            assertThat(lock.holder()).isEqualTo("operations-start"); return true;
        });
        var operations = new ConsoleWorkerOperations(workers, mining, controls, mock(WorkerCoinRevenueModeService.class), discovery, network, lock);
        var target = new ConsoleCommandService.Target("own", "GPU-0");
        assertThat(operations.apply(target, true)).isNull();
        assertThat(lock.busy()).isFalse(); verify(mining).endWorkerStart("GPU-0"); verifyNoInteractions(network);
        assertThat(lock.tryBegin("benchmark")).isTrue();
        assertThat(operations.apply(target, true)).isEqualTo("power_mode_busy");
        verify(workers, times(1)).startWithHeldRunLock("GPU-0");
        lock.end();
        doThrow(new IllegalStateException("driver rejected")).when(workers).startWithHeldRunLock("GPU-0");
        assertThatThrownBy(() -> operations.apply(target, true)).hasMessage("driver rejected");
        assertThat(lock.busy()).isFalse(); verify(mining, times(3)).endWorkerStart("GPU-0");
    }

    @Test void remoteCommandsUseDiscoveryBoundPeerControlAndCannotAssignRemoteHardware() {
        var discovery = mock(AgentNetworkDiscoveryService.class); when(discovery.agentId()).thenReturn("own");
        var network = mock(NetworkDashboardService.class); var workers = mock(WorkerAssignmentService.class);
        when(network.controlPeer("peer", "gpu", false)).thenReturn(new NetworkDashboardService.WorkerControlResult(false, "peer_worker_control_disabled"));
        var operations = new ConsoleWorkerOperations(workers, mock(MiningService.class), mock(AgentControlSettingsService.class),
                mock(WorkerCoinRevenueModeService.class), discovery, network, new LocalRunLock());
        var target = new ConsoleCommandService.Target("peer", "gpu");
        assertThat(operations.apply(target, false)).isEqualTo("peer_worker_control_disabled");
        assertThat(operations.assign(target, new ConsoleCommandService.Assignment("pearl", "peakminer"))).isEqualTo("console_local_configuration_only");
        verifyNoInteractions(workers);
    }
}
