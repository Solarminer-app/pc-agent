package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;

class WorkerAssignmentServiceTest {
    @TempDir Path directory;

    @Test
    void benchmarkRestartStopsWorkerWithoutResettingItsPowerModeTarget() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(gpu));
        MiningService mining = mock(MiningService.class);
        when(mining.stopExternalWorker("GPU-a", "Benchmark: worker restart / Worker-Neustart")).thenReturn(true);
        WorkerAssignmentService service = new WorkerAssignmentService(mock(AgentControlSettingsService.class),
                mining, power, mock(MinerCatalogService.class),
                mock(de.verdox.solarminer.pcagent.miner.MinerFactory.class),
                mock(PayoutDefaultsService.class), mock(ProxyConfigurationService.class));

        org.junit.jupiter.api.Assertions.assertTrue(service.stopForBenchmarkRestart("GPU-a"));

        verify(mining).stopExternalWorker("GPU-a", "Benchmark: worker restart / Worker-Neustart");
        verify(power, never()).restoreConfiguredPowerLimit("GPU-a");
    }

    @Test
    void assigningQuanPoolToOneGpuDoesNotChangeAnotherGpu() throws Exception {
        var first = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "First GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        var second = new LocalGpuPowerService.Gpu("NVIDIA", 1, "GPU-b", "Second GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        AgentControlSettingsService controls = new AgentControlSettingsService(new ObjectMapper(),
                directory.resolve("controls.json").toString());
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(first, second));
        when(power.snapshot()).thenReturn(List.of(first, second));
        MiningService mining = mock(MiningService.class);
        when(mining.getStats(anyList())).thenReturn(MinerStats.DEFAULT);
        MinerCatalogService catalog = mock(MinerCatalogService.class);
        MinerCatalogService.MinerOption quan = new MinerCatalogService.MinerOption("quanpool-miner", "quantus",
                "QuanPool Miner", "GPU", "QPoW", 2.0, "fee", "https://quanpool.com", List.of(), List.of(),
                "https://quanpool.com", true, true, "READY", "", true, null);
        when(catalog.options("quantus")).thenReturn(List.of(quan));
        de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolMinerService quanMiner =
                mock(de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolMinerService.class);
        de.verdox.solarminer.pcagent.miner.MinerFactory factory = new de.verdox.solarminer.pcagent.miner.MinerFactory(
                mock(XmrMinerService.class), mock(PearlMinerService.class),
                mock(GpuCoinMinerService.class), power, quanMiner, catalog, controls);
        WorkerAssignmentService service = new WorkerAssignmentService(controls, mining, power, catalog, factory,
                mock(PayoutDefaultsService.class), mock(ProxyConfigurationService.class));

        service.assign(first.deviceId(), new WorkerAssignmentService.Assignment("quantus", "quanpool-miner"));

        assertEquals("quantus", controls.get().coinFor(first.deviceId()));
        assertEquals("quanpool-miner", controls.get().minerFor(first.deviceId(), "srbminer-multi"));
        assertEquals("none", controls.get().coinFor(second.deviceId()));
        assertEquals("srbminer-multi", controls.get().minerFor(second.deviceId(), "srbminer-multi"));
    }

    @Test
    void startsEachQuantusGpuWithItsOwnAssignedMiner() {
        var first = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "First GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        var second = new LocalGpuPowerService.Gpu("NVIDIA", 1, "GPU-b", "Second GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        AgentControlSettingsService controls = new AgentControlSettingsService(new ObjectMapper(),
                directory.resolve("routing-controls.json").toString());
        org.junit.jupiter.api.Assertions.assertTrue(controls.setWorkerAssignment(first.deviceId(), "quantus", "quanpool-miner"));
        org.junit.jupiter.api.Assertions.assertTrue(controls.setWorkerAssignment(second.deviceId(), "quantus", "srbminer-multi"));
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(first, second));
        MinerCatalogService catalog = mock(MinerCatalogService.class);
        MinerCatalogService.MinerOption defaultOption = gpuOption("srbminer-multi", "quantus");
        when(catalog.selected("quantus")).thenReturn(defaultOption);
        when(catalog.options("quantus")).thenReturn(List.of(
                gpuOption("quanpool-miner", "quantus"), defaultOption));
        GpuCoinMinerService srb = mock(GpuCoinMinerService.class);
        when(srb.configuration("quantus")).thenReturn(new MinerConfig("stratum+tcp://pool:9854",
                "stratum+tcp://127.0.0.1:3339", "wallet", "worker", "NVIDIA:1"));
        when(srb.resumeGpu("quantus", "NVIDIA", 1)).thenReturn(true);
        de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolMinerService quan =
                mock(de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolMinerService.class);
        when(quan.configuration()).thenReturn(new MinerConfig("stratum+tcp://pool:9854",
                "stratum+tcp://127.0.0.1:3339", "wallet", "worker", "NVIDIA:0"));
        when(quan.resumeGpu("NVIDIA", 0)).thenReturn(true);
        de.verdox.solarminer.pcagent.miner.MinerFactory factory = new de.verdox.solarminer.pcagent.miner.MinerFactory(
                mock(XmrMinerService.class), mock(PearlMinerService.class),
                srb, power, quan, catalog, controls);
        WorkerAssignmentService service = new WorkerAssignmentService(controls, mock(MiningService.class), power,
                catalog, factory, mock(PayoutDefaultsService.class), mock(ProxyConfigurationService.class));

        org.junit.jupiter.api.Assertions.assertTrue(service.start(first.deviceId()));
        org.junit.jupiter.api.Assertions.assertTrue(service.start(second.deviceId()));

        verify(quan).resumeGpu("NVIDIA", 0);
        verify(srb).resumeGpu("quantus", "NVIDIA", 1);
        verify(quan, never()).resumeGpu("NVIDIA", 1);
        verify(srb, never()).resumeGpu("quantus", "NVIDIA", 0);
    }
    @Test
    void startsAssignedGpuWithVerifiedHousePayoutWhenNoWalletWasConfigured() throws Exception {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false,
                Map.of(), Map.of(gpu.deviceId(), "ravencoin")));
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(gpu));
        GpuCoinMinerService coins = mock(GpuCoinMinerService.class);
        when(coins.resumeGpu("ravencoin", "NVIDIA", 0)).thenReturn(true);
        PayoutDefaultsService payouts = mock(PayoutDefaultsService.class);
        when(payouts.resolveCached("ravencoin")).thenReturn(java.util.Optional.of(
                new PayoutDefaultsService.DefaultPayout("ravencoin", "house", "stratum+tcp://pool.example:3333", "wallet.worker", "x")));
        ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
        when(proxy.coinUrl(de.verdox.solarminer.pcagent.coin.Coin.RAVENCOIN)).thenReturn("stratum+tcp://127.0.0.1:3336");
        MinerCatalogService catalog = mock(MinerCatalogService.class);
        when(catalog.options("ravencoin")).thenReturn(List.of(gpuOption("srbminer-multi", "ravencoin")));
        WorkerAssignmentService service = new WorkerAssignmentService(controls, mock(MiningService.class), power,
                catalog, newFactory(mock(PearlMinerService.class), coins, power), payouts, proxy);

        org.junit.jupiter.api.Assertions.assertTrue(service.start(gpu.deviceId()));
        org.mockito.Mockito.verify(coins).configure(org.mockito.ArgumentMatchers.eq("ravencoin"), any(MinerConfig.class));
        verify(payouts).markDefault("ravencoin", true);
    }

    @Test
    void benchmarkPreparesAnInstalledGpuMinerWithoutWorkerAssignmentOrOwnWallet() throws Exception {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(gpu));
        PearlMinerService pearl = mock(PearlMinerService.class);
        when(pearl.binaryAvailable()).thenReturn(true);
        PayoutDefaultsService payouts = mock(PayoutDefaultsService.class);
        when(payouts.resolveCached("pearl")).thenReturn(java.util.Optional.of(new PayoutDefaultsService.DefaultPayout(
                "pearl", "house", "stratum+tcp://pool.example:3333", "prl1abcdefghijklmnopqrstuvwxyz234567.worker", "x")));
        ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
        when(proxy.coinUrl(de.verdox.solarminer.pcagent.coin.Coin.PEARL)).thenReturn("stratum+tcp://127.0.0.1:3335");
        MinerCatalogService catalog = mock(MinerCatalogService.class);
        when(catalog.options("pearl")).thenReturn(List.of(gpuOption("srbminer-multi", "pearl")));
        WorkerAssignmentService service = new WorkerAssignmentService(mock(AgentControlSettingsService.class), mock(MiningService.class),
                power, catalog, newFactory(pearl, mock(GpuCoinMinerService.class), power), payouts, proxy);

        assertEquals(Map.of(), service.prepareBenchmarkDefaults());
        verify(pearl).applyConfig(any(MinerConfig.class));
        verify(payouts).markDefault("pearl", true);
    }
    @Test
    void gpuWorkersUseTheMinerAlgorithmForLiveStatus() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.snapshot()).thenReturn(List.of(gpu));
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        MiningService mining = mock(MiningService.class);
        GpuCoinMinerService gpuCoins = mock(GpuCoinMinerService.class);
        when(gpuCoins.configuration(anyString())).thenReturn(new MinerConfig(
                "stratum+tcp://pool.example:3333", "stratum+tcp://127.0.0.1:3339", "wallet", "pc", "NVIDIA:0"));
        WorkerAssignmentService service = new WorkerAssignmentService(controls, mining, power,
                mock(MinerCatalogService.class), newFactory(power),
                mock(PayoutDefaultsService.class), mock(ProxyConfigurationService.class));

        for (String coin : List.of("ravencoin", "ethereumclassic", "decred", "quantus")) {
            String algorithm = GpuCoinMinerService.algorithm(coin);
            when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false,
                    Map.of(), Map.of(gpu.deviceId(), coin)));
            var live = new MinerStats.Worker(MinerStats.MinerStatus.MINING, "SRBMiner", algorithm,
                    0, 0, 0, 0, 0, 0, 0, List.of(), "GPU", gpu.model(), gpu.deviceId(),
                    null, null, MinerStats.PoolTelemetry.unavailable());
            when(mining.getStats(anyList())).thenReturn(new MinerStats(MinerStats.DEFAULT.minerIdentity(),
                    "Test", MinerStats.MinerStatus.MINING, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(live)));

            WorkerAssignmentService.WorkerView worker = service.workers().get(1);
            assertEquals(MinerStats.MinerStatus.MINING, worker.status(), coin);
            assertEquals(live, worker.telemetry(), coin);
        }
    }

    @Test
    void cpuAssignmentSelectsInstalledSoftwareWithoutStartingIt() throws Exception {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        MiningService mining = mock(MiningService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        MinerCatalogService catalog = mock(MinerCatalogService.class);
        AtomicReference<AgentControlSettingsService.Settings> state = new AtomicReference<>(
                new AgentControlSettingsService.Settings(true, false, Map.of(), Map.of("cpu", "none")));
        when(controls.get()).thenAnswer(ignored -> state.get());
        when(controls.setWorkerAssignment("cpu", "monero", "xmrig")).thenAnswer(ignored -> {
            state.set(new AgentControlSettingsService.Settings(true, false, Map.of(), Map.of("cpu", "monero")));
            return true;
        });
        MinerCatalogService.MinerOption xmrig = new MinerCatalogService.MinerOption("xmrig", "monero", "XMRig",
                "CPU", "RandomX", 1.0, "XMRig Entwickler-Spende", "https://xmrig.com", List.of(), List.of(),
                "https://xmrig.com", false,
                true, "READY", "", true, null);
        when(catalog.options("monero")).thenReturn(List.of(xmrig));
        when(catalog.selected("monero")).thenReturn(xmrig);
        when(power.snapshot()).thenReturn(List.of());
        when(mining.getStats(anyList())).thenReturn(MinerStats.DEFAULT);
        WorkerAssignmentService service = new WorkerAssignmentService(controls, mining, power, catalog,
                newFactory(power), mock(PayoutDefaultsService.class),
                mock(ProxyConfigurationService.class));
        WorkerAssignmentService.WorkerView result = service.assign("cpu",
                new WorkerAssignmentService.Assignment("monero", "xmrig"));

        assertEquals("monero", result.coin());
        assertEquals("xmrig", result.minerSoftwareId());
        verify(catalog, never()).select("monero", "xmrig");
        verify(controls).setWorkerAssignment("cpu", "monero", "xmrig");
        verify(mining, never()).stopExternalWorker("cpu");
    }

    @Test
    void rejectsGpuCoinForCpuBeforeChangingState() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false, Map.of(), Map.of("cpu", "none")));
        WorkerAssignmentService service = new WorkerAssignmentService(controls, mock(MiningService.class),
                mock(LocalGpuPowerService.class), mock(MinerCatalogService.class),
                newFactory(mock(LocalGpuPowerService.class)), mock(PayoutDefaultsService.class),
                mock(ProxyConfigurationService.class));

        assertThrows(IllegalArgumentException.class, () -> service.assign("cpu",
                new WorkerAssignmentService.Assignment("pearl", "srbminer-multi")));
    }

    private static de.verdox.solarminer.pcagent.miner.MinerFactory newFactory(LocalGpuPowerService power) {
        return newFactory(mock(PearlMinerService.class), mock(GpuCoinMinerService.class), power);
    }

    private static MinerCatalogService.MinerOption gpuOption(String id, String coin) {
        return new MinerCatalogService.MinerOption(id, coin, id, "GPU", "test", 0.0, "", "",
                List.of(), List.of(), "", false, true, "READY", "", true, null);
    }

    private static de.verdox.solarminer.pcagent.miner.MinerFactory newFactory(PearlMinerService pearl,
            GpuCoinMinerService coins, LocalGpuPowerService power) {
        return new de.verdox.solarminer.pcagent.miner.MinerFactory(
                mock(XmrMinerService.class), pearl, coins, power);
    }
}
