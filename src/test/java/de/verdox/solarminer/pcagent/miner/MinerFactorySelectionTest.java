package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolMinerService;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.miner.xmr.XmrMinerService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import java.util.Map;

class MinerFactorySelectionTest {
    @Test
    void measurementUsesAssignedWorkersMinerBeforeCoinWideDefault() {
        MinerCatalogService catalog = mock(MinerCatalogService.class);
        MinerCatalogService.MinerOption selected = mock(MinerCatalogService.MinerOption.class);
        when(selected.id()).thenReturn("quanpool-miner");
        when(catalog.selected(Coin.QUANTUS.id())).thenReturn(selected);
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false, Map.of(),
                Map.of("GPU-a", "quantus", "GPU-b", "pearl"), Map.of(), true, false,
                Map.of("GPU-a", "srbminer-multi", "GPU-b", "srbminer-multi")));
        MinerFactory factory = new MinerFactory(mock(XmrMinerService.class), mock(PearlMinerService.class),
                mock(GpuCoinMinerService.class), mock(LocalGpuPowerService.class),
                mock(QuanPoolMinerService.class), catalog, controls);

        assertEquals("srbminer-multi", factory.minerSoftwareForMeasurement(Coin.QUANTUS, "GPU-a"));
        assertEquals("quanpool-miner", factory.minerSoftwareForMeasurement(Coin.QUANTUS, "GPU-b"));
    }

    @Test
    void dispatchesQuantusToTheSelectedQuanPoolAdapter() {
        MinerCatalogService catalog = mock(MinerCatalogService.class);
        MinerCatalogService.MinerOption selected = mock(MinerCatalogService.MinerOption.class);
        when(selected.id()).thenReturn("quanpool-miner");
        when(catalog.selected(Coin.QUANTUS.id())).thenReturn(selected);
        QuanPoolMinerService quanPool = mock(QuanPoolMinerService.class);
        MinerFactory factory = new MinerFactory(mock(XmrMinerService.class), mock(PearlMinerService.class),
                mock(GpuCoinMinerService.class), mock(LocalGpuPowerService.class), quanPool, catalog);

        assertSame(quanPool, factory.gpuMiner(Coin.QUANTUS));
        assertSame(quanPool, factory.miner(Coin.QUANTUS));
    }

    @Test
    void dispatchesTheSameCoinToDifferentWorkerAdapters() {
        MinerCatalogService catalog = mock(MinerCatalogService.class);
        MinerCatalogService.MinerOption selected = mock(MinerCatalogService.MinerOption.class);
        when(selected.id()).thenReturn("srbminer-multi");
        when(catalog.selected(Coin.QUANTUS.id())).thenReturn(selected);
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false, Map.of(),
                Map.of("GPU-a", "quantus", "GPU-b", "quantus"), Map.of(), true, false,
                Map.of("GPU-a", "quanpool-miner", "GPU-b", "srbminer-multi")));
        QuanPoolMinerService quanPool = mock(QuanPoolMinerService.class);
        GpuCoinMinerService srb = mock(GpuCoinMinerService.class);
        MinerFactory factory = new MinerFactory(mock(XmrMinerService.class), mock(PearlMinerService.class),
                srb, mock(LocalGpuPowerService.class), quanPool, catalog, controls);

        assertSame(quanPool, factory.gpuMinerForWorker("quantus", "GPU-a").orElseThrow());
        org.junit.jupiter.api.Assertions.assertInstanceOf(SrbGpuCoinView.class,
                factory.gpuMinerForWorker("quantus", "GPU-b").orElseThrow());
    }
    @Test
    void temporaryMeasurementChangesRuntimeAdapterButPreservesSavedAssignment() {
        var catalog = mock(MinerCatalogService.class);
        var controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false, Map.of(),
                Map.of("GPU-a", "quantus"), Map.of(), true, false, Map.of("GPU-a", "srbminer-multi")));
        var factory = new MinerFactory(mock(XmrMinerService.class), mock(PearlMinerService.class),
                mock(GpuCoinMinerService.class), mock(LocalGpuPowerService.class), mock(QuanPoolMinerService.class), catalog, controls);
        factory.setMeasurementContext("GPU-a", "quantus", "quanpool-miner", "POWER_SWEEP");
        assertEquals("quanpool-miner", factory.minerSoftwareForWorker(Coin.QUANTUS, "GPU-a"));
        assertEquals("srbminer-multi", factory.configuredMinerSoftwareForWorker(Coin.QUANTUS, "GPU-a"));
        assertEquals("POWER_SWEEP", factory.measurementContext("GPU-a").phase());
        factory.clearBenchmarkSoftware("GPU-a");
        assertEquals("srbminer-multi", factory.minerSoftwareForWorker(Coin.QUANTUS, "GPU-a"));
        org.junit.jupiter.api.Assertions.assertNull(factory.measurementContext("GPU-a"));
    }

}
