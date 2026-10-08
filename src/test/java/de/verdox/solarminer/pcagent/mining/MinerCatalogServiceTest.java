package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.pearl.SrbDownloadService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.xmr.download.XmrDownloadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinerCatalogServiceTest {
    @TempDir Path directory;

    @Test
    void exposesPerCoinMetadataAndPersistsOnlyKnownSelections() {
        XmrDownloadService xmrig = mock(XmrDownloadService.class);
        SrbDownloadService srb = mock(SrbDownloadService.class);
        XmrMinerService xmrMiner = mock(XmrMinerService.class);
        PearlMinerService pearlMiner = mock(PearlMinerService.class);
        when(xmrMiner.binaryAvailable()).thenReturn(true);
        when(xmrig.status()).thenReturn("READY");
        when(xmrig.detail()).thenReturn("ready");
        when(xmrig.retry()).thenReturn(true);
        when(pearlMiner.binaryAvailable()).thenReturn(true);
        when(srb.status()).thenReturn("READY");
        when(srb.detail()).thenReturn("ready");
        Path file = directory.resolve("selections.properties");

        MinerCatalogService catalog = new MinerCatalogService(xmrig, srb, xmrMiner, pearlMiner, file.toString());

        MinerCatalogService.MinerOption option = catalog.selected("monero");
        assertEquals("xmrig", option.id());
        assertEquals(1.0, option.developerFeePercent());
        assertTrue(option.selectable());
        assertFalse(option.advantages().isEmpty());
        assertTrue(catalog.select("monero", "xmrig"));
        assertTrue(catalog.downloadSelected("monero"));
        verify(xmrig).retry();
        assertFalse(catalog.select("monero", "not-a-miner"));
        assertFalse(catalog.select("ravencoin", "teamredminer"));
        assertTrue(catalog.options("quantus").stream().findFirst().orElseThrow().selectable());

        assertTrue(catalog.download("monero", "xmrig"));

        MinerCatalogService restarted = new MinerCatalogService(xmrig, srb, xmrMiner, pearlMiner, file.toString());
        assertEquals("xmrig", restarted.selected("monero").id());
        assertEquals(1, restarted.options("monero").size());
        assertFalse(restarted.options("ravencoin").stream().filter(value -> value.id().equals("teamredminer"))
                .findFirst().orElseThrow().selectable());
    }
}
