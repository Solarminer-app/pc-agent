package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.lowlevel.sensor.WindowsLhmBootstrapService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.EarningsForecastService;
import de.verdox.solarminer.pcagent.mining.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.mining.ReferralConfigurationService;
import de.verdox.solarminer.pcagent.mining.FeeTransparencyService;
import de.verdox.solarminer.pcagent.mining.WalletBalanceService;
import de.verdox.solarminer.pcagent.mining.WindowsDefenderExclusionService;
import de.verdox.solarminer.pcagent.mining.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.ProxyDiscoveryService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.pearl.SrbDownloadService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrConfigService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.xmr.download.XmrDownloadService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MiningControllerValidationTest {
    private final WindowsLhmBootstrapService sensors = mock(WindowsLhmBootstrapService.class);
    private final PearlMinerService pearl = mock(PearlMinerService.class);
    private final GpuCoinMinerService gpuCoins = mock(GpuCoinMinerService.class);
    private final XmrConfigService xmrConfig = mock(XmrConfigService.class);
    private final ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
    private final PayoutDefaultsService payouts = mock(PayoutDefaultsService.class);
    private final XmrMinerService cpu = mock(XmrMinerService.class);
    private final LocalGpuPowerService gpuPower = mock(LocalGpuPowerService.class);
    private final MiningService mining = mock(MiningService.class);
    private final EarningsForecastService earnings = mock(EarningsForecastService.class);
    private final MinerCatalogService minerCatalog = mock(MinerCatalogService.class);

    MiningControllerValidationTest() {
        when(sensors.readyForAgent()).thenReturn(true);
        for (String coin : java.util.List.of("monero", "pearl", "ravencoin", "ethereumclassic", "decred", "quantus")) {
            MinerCatalogService.MinerOption option = new MinerCatalogService.MinerOption(
                    coin.equals("monero") ? "xmrig" : "srbminer-multi", coin, "test", coin.equals("monero") ? "CPU" : "GPU",
                    "test", null, java.util.List.of(), java.util.List.of(), "https://example.test", coin.equals("ravencoin") || coin.equals("ethereumclassic"), true, "READY", "ready", true, null);
            when(minerCatalog.selected(coin)).thenReturn(option);
            when(minerCatalog.options(coin)).thenReturn(java.util.List.of(option));
        }
    }

    private MockMvc controller() {
        return standaloneSetup(new MiningController(mining, xmrConfig, pearl, gpuCoins,
                gpuPower, cpu, proxy, mock(SrbDownloadService.class),
                mock(XmrDownloadService.class), sensors, mock(ProxyDiscoveryService.class),
                earnings, payouts, mock(ReferralConfigurationService.class),
                mock(FeeTransparencyService.class), mock(WalletBalanceService.class),
                mock(WindowsDefenderExclusionService.class), minerCatalog)).build();
    }

    @Test
    void emptyRavencoinWalletUsesTheMarkedHouseRoute() throws Exception {
        String houseWallet = "RHaGK3iARQdKgZ6VPDP4N5chP3aVgUUfz7";
        when(payouts.resolve("ravencoin")).thenReturn(Optional.of(new PayoutDefaultsService.DefaultPayout(
                "ravencoin", "solarminer-rvn-kawpow", "stratum+tcp://rvn.2miners.com:6060",
                houseWallet + ".solarminer", "x")));
        controller().perform(post("/api/agent/local/ravencoin/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolUrl":"","proxyUrl":"stratum+tcp://127.0.0.1:3336",
                                 "wallet":"","worker":"pc","devices":"NVIDIA:0"}
                                """))
                .andExpect(status().isOk());
        ArgumentCaptor<GpuCoinMinerService.Config> saved = ArgumentCaptor.forClass(GpuCoinMinerService.Config.class);
        verify(gpuCoins).configure(eq("ravencoin"), saved.capture());
        assertEquals("stratum+tcp://rvn.2miners.com:6060", saved.getValue().poolUrl());
        assertEquals(houseWallet, saved.getValue().wallet());
        assertEquals("solarminer", saved.getValue().worker());
        verify(payouts).markDefault("ravencoin", true);
    }

    @Test
    void emptyEtcWalletWithoutHouseRouteIsRejected() throws Exception {
        when(payouts.resolve("ethereumclassic")).thenReturn(Optional.empty());
        controller().perform(post("/api/agent/local/ethereumclassic/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolUrl":"","proxyUrl":"stratum+tcp://127.0.0.1:3337",
                                 "wallet":"","worker":"pc","devices":"NVIDIA:0"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Standard-Auszahlungsziel")));
        verifyNoInteractions(gpuCoins);
    }

    @Test
    void ownEtcWalletKeepsKryptexPoolAndDoesNotSelectHousePayout() throws Exception {
        controller().perform(post("/api/agent/local/ethereumclassic/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolUrl":"stratum+tcp://etc.kryptex.network:7033",
                                 "proxyUrl":"stratum+tcp://127.0.0.1:3337",
                                 "wallet":"0xa6e43E5D497ce1f4d28b4270630E97308eDA8b3e",
                                 "worker":"pc","devices":"NVIDIA:0"}
                                """))
                .andExpect(status().isOk());
        ArgumentCaptor<GpuCoinMinerService.Config> saved = ArgumentCaptor.forClass(GpuCoinMinerService.Config.class);
        verify(gpuCoins).configure(eq("ethereumclassic"), saved.capture());
        assertEquals("stratum+tcp://etc.kryptex.network:7033", saved.getValue().poolUrl());
        verify(payouts).markDefault("ethereumclassic", false);
    }

    @Test
    void installationCatalogOnlyChecksLocalFilesAndReflectsRemoval() throws Exception {
        MockMvc api = controller();
        api.perform(get("/api/agent/local/miner-catalog")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("monero"))
                .andExpect(jsonPath("$[0].binaryAvailable").value(true))
                .andExpect(jsonPath("$[1].binaryAvailable").value(true))
                .andExpect(jsonPath("$[2].experimental").value(true));
        api.perform(get("/api/agent/local/miner-catalog")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].selectedMinerId").value("xmrig"))
                .andExpect(jsonPath("$[1].miners[0].id").value("srbminer-multi"));
        verifyNoInteractions(mining, gpuPower, earnings, proxy, payouts, xmrConfig, cpu, pearl);
    }

    @Test
    void installsTheRequestedMinerWithoutUsingTheCoinDefault() throws Exception {
        when(minerCatalog.download("ravencoin", "srbminer-multi")).thenReturn(true);

        controller().perform(post("/api/agent/local/ravencoin/miners/srbminer-multi/download"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value(true));

        verify(minerCatalog).download("ravencoin", "srbminer-multi");
        verifyNoInteractions(gpuCoins, mining, gpuPower, earnings, proxy, payouts, xmrConfig, cpu, pearl);
    }

    @Test
    void invalidPearlWalletReturnsReadableBadRequest() throws Exception {
        controller().perform(post("/api/agent/local/pearl/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolUrl":"stratum+ssl://prl.kryptex.network:8048",
                                 "proxyUrl":"stratum+tcp://127.0.0.1:3334",
                                 "wallet":"invalid","worker":"pc","devices":"all"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A Pearl prl1 payout address is required"));
    }

    @Test
    void missingPearlProxyReturnsReadableBadRequest() throws Exception {
        String wallet = "prl1" + "q".repeat(30);
        controller().perform(post("/api/agent/local/pearl/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolUrl":"stratum+ssl://prl.kryptex.network:8048",
                                 "proxyUrl":"stratum+tcp://127.0.0.1:3334",
                                 "wallet":"%s","worker":"pc","devices":"all"}
                                """.formatted(wallet)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SolarMiner-Proxy")));
    }

    @Test
    void emptyPearlWalletWithoutFeeBackendPayoutIsRefusedWithAReason() throws Exception {
        when(payouts.resolve("pearl")).thenReturn(Optional.empty());
        controller().perform(post("/api/agent/local/pearl/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolUrl":"","proxyUrl":"stratum+tcp://127.0.0.1:3334",
                                 "wallet":"","worker":"pc","devices":"all"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Standard-Auszahlungsziel")));
    }

    @Test
    void emptyPearlWalletStoresTheFeeBackendRouteTogether() throws Exception {
        String houseWallet = "prl1" + "q".repeat(30);
        when(payouts.resolve("pearl")).thenReturn(Optional.of(new PayoutDefaultsService.DefaultPayout("pearl",
                "solarminer-prl-pearlhash", "stratum+ssl://prl.kryptex.network:8048", houseWallet + "/solarminer", "")));
        when(proxy.matches("stratum+tcp://127.0.0.1:3334", "pearl")).thenReturn(true);
        controller().perform(post("/api/agent/local/pearl/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolUrl":"","proxyUrl":"stratum+tcp://127.0.0.1:3334",
                                 "wallet":"","worker":"pc","devices":"all"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));

        ArgumentCaptor<PearlMinerService.Config> saved = ArgumentCaptor.forClass(PearlMinerService.Config.class);
        verify(pearl).configure(saved.capture());
        assertEquals("stratum+ssl://prl.kryptex.network:8048", saved.getValue().poolUrl());
        assertEquals(houseWallet, saved.getValue().wallet());
        assertEquals("solarminer", saved.getValue().worker());
        verify(payouts).markDefault("pearl", true);
    }

    @Test
    void emptyMoneroWalletRoutesToTheFeeBackendPoolAndWorker() throws Exception {
        when(proxy.moneroUrl()).thenReturn("stratum+tcp://127.0.0.1:3335");
        when(payouts.resolve("monero")).thenReturn(Optional.of(new PayoutDefaultsService.DefaultPayout("monero",
                "solarminer-xmr-randomx", "stratum+tcp://xmr.kryptex.network:7029", "4HouseWallet.solarminer", "")));
        controller().perform(post("/api/agent/local/monero/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolUrl":"","wallet":"","worker":"pc"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));

        verify(xmrConfig).configureXmrig(eq(XmrDownloadService.CONFIG_PATH), eq("stratum+tcp://127.0.0.1:3335"),
                eq("stratum+tcp://xmr.kryptex.network:7029;4HouseWallet.solarminer;x"), eq(false));
        verify(payouts).markDefault("monero", true);
    }

    @Test
    void ownMoneroWalletStillKeepsItsOwnPoolAndWorker() throws Exception {
        when(proxy.moneroUrl()).thenReturn("stratum+tcp://127.0.0.1:3335");
        controller().perform(post("/api/agent/local/monero/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolUrl":"stratum+tcp://xmr-eu.kryptex.network:7029",
                                 "wallet":"4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2684Rge",
                                 "worker":"pc"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));

        verify(xmrConfig).configureXmrig(any(), eq("stratum+tcp://127.0.0.1:3335"),
                eq("stratum+tcp://xmr-eu.kryptex.network:7029;4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2684Rge.pc;x"),
                eq(false));
        verify(payouts).markDefault("monero", false);
    }
}
