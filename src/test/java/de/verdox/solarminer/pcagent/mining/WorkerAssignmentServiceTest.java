package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import org.junit.jupiter.api.Test;

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

class WorkerAssignmentServiceTest {
    @Test
    void gpuWorkersUseTheMinerAlgorithmForLiveStatus() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(gpu));
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        MiningService mining = mock(MiningService.class);
        GpuCoinMinerService gpuCoins = mock(GpuCoinMinerService.class);
        when(gpuCoins.configuration(anyString())).thenReturn(new GpuCoinMinerService.Config(
                "stratum+tcp://pool.example:3333", "stratum+tcp://127.0.0.1:3339", "wallet", "pc", "NVIDIA:0"));
        WorkerAssignmentService service = new WorkerAssignmentService(controls, mining, power,
                mock(MinerCatalogService.class), mock(PearlMinerService.class), gpuCoins);

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
        when(controls.setWorkerCoin("cpu", "monero")).thenAnswer(ignored -> {
            state.set(new AgentControlSettingsService.Settings(true, false, Map.of(), Map.of("cpu", "monero")));
            return true;
        });
        when(controls.setWorkerEnabled("cpu", false)).thenReturn(true);
        MinerCatalogService.MinerOption xmrig = new MinerCatalogService.MinerOption("xmrig", "monero", "XMRig",
                "CPU", "RandomX", 1.0, List.of(), List.of(), "https://xmrig.com", false,
                true, "READY", "", true, null);
        when(catalog.options("monero")).thenReturn(List.of(xmrig));
        when(catalog.select("monero", "xmrig")).thenReturn(true);
        when(catalog.selected("monero")).thenReturn(xmrig);
        when(power.discover()).thenReturn(List.of());
        when(mining.getStats(anyList())).thenReturn(MinerStats.DEFAULT);
        WorkerAssignmentService service = new WorkerAssignmentService(controls, mining, power, catalog,
                mock(PearlMinerService.class), mock(GpuCoinMinerService.class));
        WorkerAssignmentService.WorkerView result = service.assign("cpu",
                new WorkerAssignmentService.Assignment("monero", "xmrig", false));

        assertEquals("monero", result.coin());
        assertEquals("xmrig", result.minerSoftwareId());
        verify(catalog).select("monero", "xmrig");
        verify(controls).setWorkerCoin("cpu", "monero");
        verify(mining, never()).stopExternalWorker("cpu");
    }

    @Test
    void rejectsGpuCoinForCpuBeforeChangingState() {
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false, Map.of(), Map.of("cpu", "none")));
        WorkerAssignmentService service = new WorkerAssignmentService(controls, mock(MiningService.class),
                mock(LocalGpuPowerService.class), mock(MinerCatalogService.class),
                mock(PearlMinerService.class), mock(GpuCoinMinerService.class));

        assertThrows(IllegalArgumentException.class, () -> service.assign("cpu",
                new WorkerAssignmentService.Assignment("pearl", "srbminer-multi", false)));
    }
}
