package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BenchmarkSessionServiceTest {
    @Test
    void unavailableDefaultPayoutSkipsPearlInsteadOfPreventingAnotherInstalledBenchmark() {
        MiningService mining = mock(MiningService.class);
        XmrMinerService xmr = mock(XmrMinerService.class);
        PearlMinerService pearl = mock(PearlMinerService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        var miners = new de.verdox.solarminer.pcagent.miner.MinerFactory(xmr, pearl,
                mock(de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService.class), mock(de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService.class));
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        when(assignments.prepareBenchmarkDefaults()).thenReturn(Map.of("pearl",
                "SolarMiner default payout for pearl is unavailable. Check the proxy and fee target."));
        when(assignments.benchmarkGpuPhases()).thenReturn(List.of());
        when(xmr.readyForStart()).thenReturn(true);
        when(pearl.binaryAvailable()).thenReturn(false);
        when(lock.tryBegin("benchmark")).thenReturn(true);

        BenchmarkSessionService service = new BenchmarkSessionService(mining, miners,
                mock(BenchmarkSharingService.class), mock(MinerConsoleService.class), lock,
                mock(MiningPerformanceProfileStore.class), assignments);

        assertDoesNotThrow(() -> service.start("INSTALLED"));
        service.cancel();
        service.close();
    }

    @Test
    void unavailableDefaultPayoutExplainsWhyNoBenchmarkCanStart() {
        XmrMinerService xmr = mock(XmrMinerService.class);
        PearlMinerService pearl = mock(PearlMinerService.class);
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        var miners = new de.verdox.solarminer.pcagent.miner.MinerFactory(xmr, pearl,
                mock(de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService.class), mock(de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService.class));
        when(assignments.prepareBenchmarkDefaults()).thenReturn(Map.of("pearl",
                "SolarMiner default payout for pearl is unavailable. Check the proxy and fee target."));
        when(assignments.benchmarkGpuPhases()).thenReturn(List.of());
        when(xmr.readyForStart()).thenReturn(false);
        when(pearl.binaryAvailable()).thenReturn(false);

        BenchmarkSessionService service = new BenchmarkSessionService(mock(MiningService.class), miners,
                mock(BenchmarkSharingService.class), mock(MinerConsoleService.class), mock(LocalRunLock.class),
                mock(MiningPerformanceProfileStore.class), assignments);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.start("INSTALLED"));
        assertEquals("Benchmark cannot start: pearl (SolarMiner default payout for pearl is unavailable. Check the proxy and fee target.)",
                error.getMessage());
    }

    @Test
    void installedRunKeepsAStartingGpuAsExpectedWorkerUntilStartupGraceExpires() {
        MinerStats.Worker startingGpu = worker(MinerStats.MinerStatus.PAUSED, "GPU", "gpu-1");

        assertEquals(List.of(startingGpu), BenchmarkSessionService.phaseWorkers(List.of(startingGpu), "pearl", false));
        assertTrue(BenchmarkSessionService.phaseWorkers(List.of(startingGpu), "pearl", true).isEmpty());
        assertFalse(BenchmarkSessionService.workerUnavailable("gpu-1|PearlHash", startingGpu, Set.of(), Instant.now().plusSeconds(1)));
        assertTrue(BenchmarkSessionService.workerUnavailable("gpu-1|PearlHash", startingGpu, Set.of(), Instant.now().minusSeconds(1)));
    }

    @Test
    void startupAndLossRulesApplyEquallyToCpuAndGpuWorkers() {
        MinerStats.Worker startingCpu = worker(MinerStats.MinerStatus.PAUSED, "CPU", "cpu");
        MinerStats.Worker runningGpu = worker(MinerStats.MinerStatus.MINING, "GPU", "gpu-1");
        MinerStats.Worker failedCpu = worker(MinerStats.MinerStatus.ERROR, "CPU", "cpu");

        assertEquals(List.of(startingCpu), BenchmarkSessionService.phaseWorkers(List.of(startingCpu, runningGpu), "monero", false));
        assertEquals(List.of(runningGpu), BenchmarkSessionService.phaseWorkers(List.of(startingCpu, runningGpu), "pearl", true));
        assertFalse(BenchmarkSessionService.workerUnavailable("cpu|RandomX", startingCpu, Set.of(), Instant.now().plusSeconds(1)));
        assertTrue(BenchmarkSessionService.workerUnavailable("cpu|RandomX", failedCpu, Set.of(), Instant.now().plusSeconds(1)));
        assertTrue(BenchmarkSessionService.workerUnavailable("gpu-1|PearlHash", worker(MinerStats.MinerStatus.PAUSED, "GPU", "gpu-1"), Set.of("gpu-1|PearlHash"), Instant.now().plusSeconds(1)));
    }

    @Test
    void gpuCoinPhaseOnlyMeasuresWorkersForItsOwnAlgorithm() {
        MinerStats.Worker ravencoin = worker(MinerStats.MinerStatus.MINING, "GPU", "gpu-rvn", "kawpow");
        MinerStats.Worker etc = worker(MinerStats.MinerStatus.MINING, "GPU", "gpu-etc", "etchash");

        assertEquals(List.of(ravencoin), BenchmarkSessionService.phaseWorkers(List.of(ravencoin, etc), "ravencoin", false));
        assertEquals(List.of(etc), BenchmarkSessionService.phaseWorkers(List.of(ravencoin, etc), "ethereumclassic", false));
    }

    private static MinerStats.Worker worker(MinerStats.MinerStatus status, String hardwareType, String deviceId) {
        return worker(status, hardwareType, deviceId, "CPU".equals(hardwareType) ? "RandomX" : "PearlHash");
    }

    private static MinerStats.Worker worker(MinerStats.MinerStatus status, String hardwareType, String deviceId, String algorithm) {
        return new MinerStats.Worker(status, deviceId, algorithm, 0, 0,
                0, 0, 0, 0, 0, List.of(), hardwareType, "test", deviceId, null, null, MinerStats.PoolTelemetry.unavailable());
    }
}
