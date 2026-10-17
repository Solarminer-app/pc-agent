package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;

class BenchmarkSessionServiceTest {
    @Test
    void autoStartUsesConfiguredWorkerPathAndKeepsFailuresPerWorker() {
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        when(assignments.pause("cpu")).thenReturn(true);
        when(assignments.startWithHeldRunLock("cpu")).thenReturn(true);
        when(assignments.pause("gpu-a")).thenReturn(true);
        when(assignments.startWithHeldRunLock("gpu-a"))
                .thenThrow(new IllegalStateException("power_mode_no_measured_point"));
        when(assignments.pause("gpu-b")).thenReturn(false);
        BenchmarkSessionService service = new BenchmarkSessionService(mock(MiningService.class),
                mock(de.verdox.solarminer.pcagent.miner.MinerFactory.class),
                mock(BenchmarkSharingService.class), mock(MinerConsoleService.class), mock(LocalRunLock.class),
                mock(MiningPerformanceProfileStore.class), assignments);

        var results = service.startConfiguredWorkers(Set.of("cpu", "gpu-a", "gpu-b"));

        assertEquals(3, results.size());
        assertEquals("STARTED", results.stream().filter(result -> result.deviceId().equals("cpu"))
                .findFirst().orElseThrow().status());
        assertEquals("power_mode_no_measured_point", results.stream().filter(result -> result.deviceId().equals("gpu-a"))
                .findFirst().orElseThrow().message());
        assertEquals("benchmark_auto_start_stop_failed", results.stream().filter(result -> result.deviceId().equals("gpu-b"))
                .findFirst().orElseThrow().message());
        org.mockito.Mockito.verify(assignments).startWithHeldRunLock("cpu");
        org.mockito.Mockito.verify(assignments).startWithHeldRunLock("gpu-a");
        org.mockito.Mockito.verify(assignments, org.mockito.Mockito.never()).startWithHeldRunLock("gpu-b");
        service.close();
    }

    @Test
    void liveTraceKeepsPhysicalWorkersSeparateAndBoundsTheWirePayload() {
        MinerStats.Worker first = new MinerStats.Worker(MinerStats.MinerStatus.MINING, "worker", "PearlHash",
                0.000000000002, 60, 180, 100, 180, 300, 175, List.of(), "GPU", "RTX 4090", "gpu-a",
                null, null, MinerStats.PoolTelemetry.unavailable());
        MinerStats.Worker second = new MinerStats.Worker(MinerStats.MinerStatus.MINING, "worker", "PearlHash",
                0.000000000003, 60, 180, 100, 180, 300, 180, List.of(), "GPU", "RTX 4090", "gpu-b",
                null, null, MinerStats.PoolTelemetry.unavailable());
        var traces = BenchmarkSessionService.liveSeries(Map.of(
                "gpu-a|PearlHash|srbminer", java.util.Collections.nCopies(14, first),
                "gpu-b|PearlHash|teamredminer", List.of(second)));

        assertEquals(2, traces.size());
        var a = traces.stream().filter(trace -> trace.deviceId().equals("gpu-a")).findFirst().orElseThrow();
        var b = traces.stream().filter(trace -> trace.deviceId().equals("gpu-b")).findFirst().orElseThrow();
        assertEquals("srbminer", a.minerSoftwareId());
        assertEquals(12, a.points().size());
        assertEquals(1, a.points().getFirst().index());
        assertEquals(2.0, a.points().getFirst().hashrateHs(), 0.000001);
        assertEquals(175, a.points().getFirst().powerWatts());
        assertEquals("teamredminer", b.minerSoftwareId());
        assertEquals(1, b.points().size());
        assertEquals(3.0, b.points().getFirst().hashrateHs(), 0.000001);
    }

    @Test
    void workerSelectionRejectsEmptyAndStaleIds() {
        Set<String> available = Set.of("cpu", "gpu-a", "gpu-b");
        assertEquals(Set.of("gpu-a", "gpu-b"), BenchmarkSessionService.validateWorkerSelection(
                List.of("gpu-a", "gpu-b", "gpu-a"), available));
        assertEquals("benchmark_select_worker", assertThrows(IllegalArgumentException.class,
                () -> BenchmarkSessionService.validateWorkerSelection(List.of(), available)).getMessage());
        assertEquals("benchmark_worker_unavailable", assertThrows(IllegalArgumentException.class,
                () -> BenchmarkSessionService.validateWorkerSelection(List.of("gpu-missing"), available)).getMessage());
    }

    @Test
    void everyDeviceMeasuresEveryEligibleCoinIncludingIdenticalModels() {
        LocalGpuPowerService.Gpu a0 = gpu("gpu-a0", "RTX 4090", 0);
        LocalGpuPowerService.Gpu a1 = gpu("gpu-a1", "RTX 4090", 1);
        LocalGpuPowerService.Gpu b0 = gpu("gpu-b0", "RTX 3060", 2);
        Map<de.verdox.solarminer.pcagent.coin.Coin, List<LocalGpuPowerService.Gpu>> cards = new LinkedHashMap<>();
        cards.put(de.verdox.solarminer.pcagent.coin.Coin.PEARL, List.of(a0, a1, b0));
        cards.put(de.verdox.solarminer.pcagent.coin.Coin.RAVENCOIN, List.of(a0, a1, b0));
        cards.put(de.verdox.solarminer.pcagent.coin.Coin.ETHEREUMCLASSIC, List.of(a0, a1, b0));

        List<Map<de.verdox.solarminer.pcagent.coin.Coin, List<LocalGpuPowerService.Gpu>>> batches =
                BenchmarkSessionService.planGpuBatches(cards);

        assertEquals(3, batches.size());
        assertEquals(3, batches.getFirst().size());
        Set<de.verdox.solarminer.pcagent.coin.Coin> firstModelCoins = new java.util.HashSet<>();
        batches.getFirst().forEach((coin, devices) -> devices.stream().filter(gpu -> gpu.model().equals("RTX 4090"))
                .forEach(ignored -> firstModelCoins.add(coin)));
        assertEquals(2, firstModelCoins.size());
        for (String deviceId : List.of("gpu-a0", "gpu-a1", "gpu-b0")) {
            Set<de.verdox.solarminer.pcagent.coin.Coin> measured = new java.util.HashSet<>();
            batches.forEach(batch -> batch.forEach((coin, devices) -> devices.stream()
                    .filter(gpu -> gpu.deviceId().equals(deviceId)).forEach(ignored -> measured.add(coin))));
            assertEquals(cards.keySet(), measured);
            for (var batch : batches) assertEquals(1, batch.values().stream().flatMap(List::stream)
                    .filter(gpu -> gpu.deviceId().equals(deviceId)).count());
        }
    }
    @Test
    void deviceOnlyRunsCoinsForWhichItIsSelected() {
        LocalGpuPowerService.Gpu a = gpu("gpu-a", "Titan RTX", 0);
        LocalGpuPowerService.Gpu b = gpu("gpu-b", "Titan RTX", 1);
        var cards = new LinkedHashMap<de.verdox.solarminer.pcagent.coin.Coin, List<LocalGpuPowerService.Gpu>>();
        cards.put(de.verdox.solarminer.pcagent.coin.Coin.PEARL, List.of(a, b));
        cards.put(de.verdox.solarminer.pcagent.coin.Coin.RAVENCOIN, List.of(a));
        var batches = BenchmarkSessionService.planGpuBatches(cards);
        assertEquals(2, batches.size());
        assertEquals(Set.of(de.verdox.solarminer.pcagent.coin.Coin.PEARL,
                de.verdox.solarminer.pcagent.coin.Coin.RAVENCOIN), batches.stream()
                .filter(batch -> batch.values().stream().flatMap(List::stream).anyMatch(gpu -> gpu.deviceId().equals("gpu-a")))
                .flatMap(batch -> batch.keySet().stream()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, batches.stream().flatMap(batch -> batch.values().stream()).flatMap(List::stream)
                .filter(gpu -> gpu.deviceId().equals("gpu-b")).count());
    }
    @Test
    void unavailableDefaultPayoutSkipsPearlInsteadOfPreventingAnotherInstalledBenchmark() {
        MiningService mining = mock(MiningService.class);
        XmrMinerService xmr = mock(XmrMinerService.class);
        PearlMinerService pearl = mock(PearlMinerService.class);
        LocalRunLock lock = mock(LocalRunLock.class);
        var miners = new de.verdox.solarminer.pcagent.miner.MinerFactory(xmr, pearl,
                mock(de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService.class), mock(de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.class));
        WorkerAssignmentService assignments = mock(WorkerAssignmentService.class);
        when(assignments.prepareBenchmarkDefaults(java.util.Set.of())).thenReturn(Map.of("pearl",
                "SolarMiner default payout for pearl is unavailable. Check the proxy and fee target."));
        when(assignments.benchmarkGpuPhases()).thenReturn(List.of());
        when(xmr.readyForStart()).thenReturn(true);
        when(pearl.binaryAvailable()).thenReturn(false);
        when(lock.tryBegin("benchmark")).thenReturn(true);

        BenchmarkSessionService service = new BenchmarkSessionService(mining, miners,
                mock(BenchmarkSharingService.class), mock(MinerConsoleService.class), lock,
                mock(MiningPerformanceProfileStore.class), assignments, mock(de.verdox.solarminer.pcagent.mining.MinerCatalogService.class));

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
                mock(de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService.class), mock(de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.class));
        when(assignments.prepareBenchmarkDefaults(java.util.Set.of())).thenReturn(Map.of("pearl",
                "SolarMiner default payout for pearl is unavailable. Check the proxy and fee target."));
        when(assignments.benchmarkGpuPhases()).thenReturn(List.of());
        when(xmr.readyForStart()).thenReturn(false);
        when(pearl.binaryAvailable()).thenReturn(false);

        BenchmarkSessionService service = new BenchmarkSessionService(mock(MiningService.class), miners,
                mock(BenchmarkSharingService.class), mock(MinerConsoleService.class), mock(LocalRunLock.class),
                mock(MiningPerformanceProfileStore.class), assignments, mock(de.verdox.solarminer.pcagent.mining.MinerCatalogService.class));

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

    @Test
    void unknownCoinsInASelectionAreRejectedBeforeAnyRunStarts() {
        BenchmarkSessionService service = new BenchmarkSessionService(mock(MiningService.class),
                new de.verdox.solarminer.pcagent.miner.MinerFactory(mock(XmrMinerService.class),
                        mock(PearlMinerService.class), mock(de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService.class),
                        mock(de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.class)),
                mock(BenchmarkSharingService.class), mock(MinerConsoleService.class), mock(LocalRunLock.class),
                mock(MiningPerformanceProfileStore.class), mock(WorkerAssignmentService.class),
                mock(de.verdox.solarminer.pcagent.mining.MinerCatalogService.class));

        assertThrows(IllegalArgumentException.class, () -> service.start("INSTALLED", List.of("notacoin")));
        assertEquals(Set.of("pearl"), BenchmarkSessionService.validateCoinSelection(List.of("pearl")));
        assertEquals(Set.of(), BenchmarkSessionService.validateCoinSelection(List.of()));
    }

    private static MinerStats.Worker worker(MinerStats.MinerStatus status, String hardwareType, String deviceId) {
        return worker(status, hardwareType, deviceId, "CPU".equals(hardwareType) ? "RandomX" : "PearlHash");
    }

    private static MinerStats.Worker worker(MinerStats.MinerStatus status, String hardwareType, String deviceId, String algorithm) {
        return new MinerStats.Worker(status, deviceId, algorithm, 0, 0,
                0, 0, 0, 0, 0, List.of(), hardwareType, "test", deviceId, null, null, MinerStats.PoolTelemetry.unavailable());
    }

    private static LocalGpuPowerService.Gpu gpu(String id, String model, int index) {
        return new LocalGpuPowerService.Gpu("NVIDIA", index, id, model, 100, 400, 100, 400,
                300, 200d, "driver", true, null);
    }
}
