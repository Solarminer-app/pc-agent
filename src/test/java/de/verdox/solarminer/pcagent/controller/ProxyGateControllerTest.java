package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ManagedProxyService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
import de.verdox.solarminer.pcagent.mining.fee.FeeTransparencyService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyDiscoveryService;
import de.verdox.solarminer.pcagent.mining.fee.ReferralConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.WalletBalanceService;
import de.verdox.solarminer.pcagent.mining.WindowsDefenderExclusionService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.miner.srb.SrbDownloadService;
import de.verdox.solarminer.pcagent.miner.xmr.XmrConfigService;
import de.verdox.solarminer.pcagent.miner.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.miner.xmr.download.XmrDownloadService;
import de.verdox.solarminer.pcagent.lowlevel.sensor.WindowsLhmBootstrapService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;

/**
 * Phase 5d (owner decision O3): the boot gate merged from ProxyGateController into the
 * single proxy resource on MiningController; the route moved to /proxy/gate and the log
 * chunk wire shape is unchanged.
 */
class ProxyGateControllerTest {
    @Test
    void servesTheManagedProxyLogIncrementally() throws Exception {
        ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
        when(proxy.managedProxyLog(eq(12L)))
                .thenReturn(new ManagedProxyService.ProxyLogChunk(18, "proxy started\n", false));
        MiningController miningController = new MiningController(mock(MiningService.class),
                mock(XmrConfigService.class), mock(PearlMinerService.class), mock(GpuCoinMinerService.class),
                mock(LocalGpuPowerService.class), mock(XmrMinerService.class), proxy,
                mock(SrbDownloadService.class), mock(XmrDownloadService.class),
                mock(WindowsLhmBootstrapService.class), mock(ProxyDiscoveryService.class),
                mock(MarketDataService.class), mock(PayoutDefaultsService.class),
                mock(ReferralConfigurationService.class), mock(FeeTransparencyService.class),
                mock(WalletBalanceService.class), mock(WindowsDefenderExclusionService.class),
                mock(MinerCatalogService.class), new LocalRunLock(), mock(MinerFactory.class));
        MockMvc api = standaloneSetup(miningController).build();

        api.perform(get("/api/agent/local/proxy/gate/log").param("offset", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextOffset").value(18))
                .andExpect(jsonPath("$.data").value("proxy started\n"));
    }
}
