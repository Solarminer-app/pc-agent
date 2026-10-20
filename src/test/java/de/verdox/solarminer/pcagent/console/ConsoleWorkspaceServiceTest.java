package de.verdox.solarminer.pcagent.console;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.EfficiencySweepService;
import de.verdox.solarminer.pcagent.mining.network.AgentNetworkDiscoveryService;
import de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConsoleWorkspaceServiceTest {
    private final WorkerAssignmentService workers = mock(WorkerAssignmentService.class);
    private final NetworkDashboardService network = mock(NetworkDashboardService.class);
    private ConsoleWorkspaceService service;

    @BeforeEach void setUp() {
        var identity = mock(AgentIdentityService.class);
        var discovery = mock(AgentNetworkDiscoveryService.class);
        var benchmarks = mock(BenchmarkSessionService.class);
        var sweeps = mock(EfficiencySweepService.class);
        when(identity.displayName()).thenReturn("Local");
        when(discovery.agentId()).thenReturn("own");
        when(network.cards()).thenReturn(List.of());
        when(benchmarks.status()).thenReturn(mock(BenchmarkSessionService.Session.class));
        when(sweeps.status()).thenReturn(mock(EfficiencySweepService.Session.class));
        service = new ConsoleWorkspaceService(workers, identity, discovery, network, benchmarks, sweeps);
    }

    static WorkerAssignmentService.WorkerView worker(int id, MinerStats.Worker telemetry) {
        return new WorkerAssignmentService.WorkerView("GPU-" + id, "GPU", "RTX " + id, "NVIDIA", id,
                "pearl", "Pearl", "PearlHash", "peakminer", "PeakMiner", true, true, "stratum+tcp://pool",
                false, MinerStats.MinerStatus.MINING, telemetry, "MANUAL");
    }

    @Test void readsAreBoundedFilteredAndNeverCollectHardwareOnRequestThreads() {
        when(workers.workers()).thenReturn(IntStream.range(0, 137).mapToObj(id -> worker(id, null)).toList());
        assertThat(service.view("", "", "", 0, 500).workers()).isEmpty();
        verifyNoInteractions(workers);
        service.collect();
        var page = service.view("", "", "", Integer.MAX_VALUE, 500);
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.workers()).hasSize(37);
        assertThat(page.summary().total()).isEqualTo(137);
        assertThat(service.view("RTX 136", "own", "MINING", -1, 50).workers()).hasSize(1);
        assertThat(service.find("own", "GPU-136")).isPresent();
        assertThat(service.find("other", "GPU-136")).isEmpty();
        verify(workers, times(1)).workers();
    }

    @Test void localRightsDoNotDependOnRemoteConsentAndMissingMetricsRemainNull() {
        when(workers.workers()).thenReturn(List.of(worker(0, null)));
        service.view("", "", "", 0, 50); service.collect();
        var row = service.find("own", "GPU-0").orElseThrow();
        assertThat(row.startBlocked()).isNull();
        assertThat(row.pauseBlocked()).isNull();
        assertThat(row.watts()).isNull();
        assertThat(row.hashrateHps()).isNull();
        assertThat(row.acceptedShares()).isNull();
        assertThat(service.view("", "", "", 0, 50).summary().reportingWorkers()).isZero();
    }

    @Test void observedAlgorithmWinsOverAssignmentWithoutChangingConfiguredCoin() {
        var live = mock(MinerStats.Worker.class);
        when(live.currentAlgorithm()).thenReturn("KawPow");
        when(live.terahashPerSecond()).thenReturn(0.00003);
        when(live.temperatureCelsius()).thenReturn(Double.NaN);
        when(workers.workers()).thenReturn(List.of(worker(0, live)));
        service.view("", "", "", 0, 50); service.collect();
        var row = service.find("own", "GPU-0").orElseThrow();
        assertThat(row.algorithm()).isEqualTo("KawPow");
        assertThat(row.coin()).isEqualTo("pearl");
        assertThat(row.hashrateHps()).isEqualTo(30_000_000);
        assertThat(row.temperature()).isNull();
    }

    @Test void stalePeersCannotControlOrContributeToLiveTotalsAndOldPeersAreReadOnly() {
        var peer = new NetworkDashboardService.WorkerRow("GPU", "KawPow", "GPU", "RTX", 3e7, 180,
                "MINING", "ravencoin", "peer-gpu", true, true, true, false, false);
        when(network.cards()).thenReturn(List.of(new NetworkDashboardService.AgentCard("peer", "Peer", "192.168.1.2",
                8084, true, false, null, List.of(peer), List.of(), null, null, true,
                Instant.now().minusSeconds(30), List.of(), List.of())));
        var view = service.view("", "", "", 0, 50);
        assertThat(view.workers().getFirst().stale()).isTrue();
        assertThat(view.workers().getFirst().startBlocked()).isEqualTo("console_stale");
        assertThat(view.summary().active()).isZero();
        assertThat(view.summary().reportedWatts()).isZero();
        var older = new NetworkDashboardService.WorkerRow("GPU", "KawPow", "GPU", "RTX", 3e7, 180,
                "MINING", "ravencoin", "peer-gpu", true, true, null, null, null);
        when(network.cards()).thenReturn(List.of(new NetworkDashboardService.AgentCard("peer", "Peer", "192.168.1.2",
                8084, true, false, null, List.of(older), List.of(), null, null, true,
                Instant.now(), List.of(), List.of())));
        assertThat(service.find("peer", "peer-gpu").orElseThrow().pauseBlocked()).isEqualTo("console_worker_disabled");
    }

    @Test void legacyWorkersWithoutIdsRemainDistinctAndNeverGainControl() {
        var legacy = new NetworkDashboardService.WorkerRow("GPU", "KawPow", "GPU", "RTX", 3e7, 180,
                "MINING", null, null, null, null, null, null, null);
        when(network.cards()).thenReturn(List.of(new NetworkDashboardService.AgentCard("peer", "Peer", "192.168.1.2",
                8084, true, false, null, List.of(legacy, legacy), List.of(), null, null, true,
                Instant.now(), List.of(), List.of())));
        var view = service.view("", "", "", 0, 50);
        assertThat(view.workers()).hasSize(2);
        assertThat(view.workers().stream().map(ConsoleWorkspaceService.Worker::rowId)).doesNotHaveDuplicates();
        assertThat(view.workers()).allMatch(row -> "console_worker_identity_missing".equals(row.startBlocked())
                && "console_worker_identity_missing".equals(row.pauseBlocked()));
    }

    @Test void failedCollectionDoesNotFreshenCachedData() {
        when(workers.workers()).thenReturn(List.of(worker(0, null)));
        service.view("", "", "", 0, 50); service.collect();
        Instant before = service.find("own", "GPU-0").orElseThrow().collectedAt();
        when(workers.workers()).thenThrow(new IllegalStateException("driver unavailable"));
        service.collect();
        assertThat(service.find("own", "GPU-0").orElseThrow().collectedAt()).isEqualTo(before);
    }
}
