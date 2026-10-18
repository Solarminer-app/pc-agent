package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.miner.srb.SrbDownloadService;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.miner.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.miner.xmr.download.XmrDownloadService;
import de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolDownloadService;
import de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolMinerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinerCatalogServiceTest {
    @TempDir Path directory;

    @Test
    void explainsQuanPoolWindowsBlockerAndNamesInstallableQuantusAlternatives() {
        assertEquals("QuanPool Miner has no Windows build. For Quantus on Windows, use WildRig NG or SRBMiner-MULTI.",
                MinerCatalogService.quanPoolUnsupportedReason("Windows 11"));
        assertEquals("QuanPool Miner 6.4.0 is available only for Linux x86-64",
                MinerCatalogService.quanPoolUnsupportedReason("Mac OS X"));
    }

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
        // REST audit R5: installation state is a snapshot; probe it once like the scheduler does.
        catalog.refreshInstallState();

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
        restarted.refreshInstallState();
        assertEquals("xmrig", restarted.selected("monero").id());
        assertEquals(1, restarted.options("monero").size());
        assertFalse(restarted.options("ravencoin").stream().filter(value -> value.id().equals("teamredminer"))
                .findFirst().orElseThrow().selectable());
        MinerCatalogService.MinerOption unsupported = restarted.options("ravencoin").stream()
                .filter(value -> value.id().equals("teamredminer")).findFirst().orElseThrow();
        assertFalse(unsupported.installed());
        assertEquals("NOT_INTEGRATED", unsupported.downloadStatus());
    }

    @Test
    void exposesAnExplicitUpdateStateForAnOlderSrbMinerInstallation() {
        XmrDownloadService xmrig = mock(XmrDownloadService.class);
        SrbDownloadService srb = mock(SrbDownloadService.class);
        XmrMinerService xmrMiner = mock(XmrMinerService.class);
        PearlMinerService pearlMiner = mock(PearlMinerService.class);
        when(pearlMiner.binaryPresent()).thenReturn(true);
        when(pearlMiner.binaryAvailable()).thenReturn(false);
        when(srb.status()).thenReturn("PENDING");

        MinerCatalogService catalog = new MinerCatalogService(xmrig, srb, xmrMiner, pearlMiner,
                directory.resolve("selections.properties").toString());
        catalog.refreshInstallState();

        MinerCatalogService.MinerOption pearl = catalog.selected("pearl");
        assertFalse(pearl.installed());
        assertEquals("UPDATE_REQUIRED", pearl.downloadStatus());
        assertTrue(pearl.downloadDetail().isBlank());
    }

    @Test
    void exposesQuanPoolAsAnIndependentSelectableQuantusMinerOnBeta() {
        XmrDownloadService xmrig = mock(XmrDownloadService.class);
        SrbDownloadService srb = mock(SrbDownloadService.class);
        XmrMinerService xmrMiner = mock(XmrMinerService.class);
        PearlMinerService pearlMiner = mock(PearlMinerService.class);
        QuanPoolDownloadService quanPool = mock(QuanPoolDownloadService.class);
        QuanPoolMinerService quanPoolMiner = mock(QuanPoolMinerService.class);
        when(quanPool.binaryAvailable()).thenReturn(true);
        when(quanPool.status()).thenReturn("READY");
        when(quanPool.retry()).thenReturn(true);

        MinerCatalogService catalog = new MinerCatalogService(xmrig, srb, xmrMiner, pearlMiner,
                quanPool, quanPoolMiner, directory.resolve("selections.properties").toString());
        catalog.refreshInstallState();

        MinerCatalogService.MinerOption option = catalog.options("quantus").stream()
                .filter(candidate -> candidate.id().equals("quanpool-miner")).findFirst().orElseThrow();
        assertTrue(option.installed());
        assertTrue(option.installable());
        assertTrue(option.selectable());
        assertEquals(2.0, option.developerFeePercent());
        assertTrue(catalog.select("quantus", "quanpool-miner"));
        assertEquals("quanpool-miner", catalog.selected("quantus").id());
        assertTrue(catalog.download("quantus", "quanpool-miner"));
        verify(quanPool).retry();
    }
}
