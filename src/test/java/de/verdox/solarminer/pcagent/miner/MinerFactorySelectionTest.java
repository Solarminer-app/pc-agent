package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolMinerService;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;
import java.util.Map;

class MinerFactorySelectionTest {
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
}
