package de.verdox.solarminer.pcagent.console;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.miner.xmr.XmrConfigService;
import de.verdox.solarminer.pcagent.miner.xmr.download.XmrDownloadService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsolePayoutServiceTest {
    final MinerFactory miners=mock(MinerFactory.class);
    final ProxyConfigurationService proxy=mock(ProxyConfigurationService.class);
    final PayoutDefaultsService defaults=mock(PayoutDefaultsService.class);
    final XmrConfigService xmr=mock(XmrConfigService.class);
    final LocalGpuPowerService power=mock(LocalGpuPowerService.class);
    final LocalRunLock lock=new LocalRunLock();
    final ConsolePayoutService service=new ConsolePayoutService(miners,proxy,defaults,xmr,power,lock);
    final String wallet="4"+"A".repeat(94);
    @Test void invalidPoolOrWalletDoesNotStopOrRewriteCpu() {
        when(proxy.coinUrl(Coin.MONERO)).thenReturn("stratum+tcp://127.0.0.1:3335");
        assertThatThrownBy(()->service.save(new ConsolePayoutService.Route("monero","http://pool.example:3333",wallet,"rig"))).hasMessage("tool_pool_invalid");
        assertThatThrownBy(()->service.save(new ConsolePayoutService.Route("monero","stratum+ssl://pool.example:443","bad","rig"))).hasMessage("tool_wallet_invalid");
        verifyNoInteractions(miners,xmr); assertThat(lock.busy()).isFalse();
    }
    @Test void cpuDefaultUsesTheCompleteAuthoritativePoolAndLogin() throws Exception {
        when(proxy.coinUrl(Coin.MONERO)).thenReturn("stratum+tcp://127.0.0.1:3335");
        when(defaults.resolveCached("monero")).thenReturn(Optional.of(new PayoutDefaultsService.DefaultPayout("monero","house","stratum+ssl://house.example:443",wallet+".house","x")));
        CpuMiner cpu=mock(CpuMiner.class); when(miners.miner(Coin.MONERO)).thenReturn(cpu); when(cpu.stopAll()).thenReturn(true);
        service.save(new ConsolePayoutService.Route("monero","stratum+tcp://ignored.example:1","","ignored"));
        verify(xmr).configureXmrig(XmrDownloadService.CONFIG_PATH,"stratum+tcp://127.0.0.1:3335","stratum+ssl://house.example:443;"+wallet+".house;x",false);
        verify(defaults).markDefault("monero",true); verify(cpu,never()).startAll(); assertThat(lock.busy()).isFalse();
    }
    @Test void missingDefaultAndMeasurementLockNeverTouchExistingRoutes() {
        when(proxy.coinUrl(Coin.MONERO)).thenReturn("stratum+tcp://127.0.0.1:3335"); when(defaults.resolveCached("monero")).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.save(new ConsolePayoutService.Route("monero",null,"","rig"))).hasMessage("console_default_payout_unavailable");
        assertThat(lock.tryBegin("sweep")).isTrue();
        assertThatThrownBy(()->service.save(new ConsolePayoutService.Route("monero",null,wallet,"rig"))).hasMessage("power_mode_busy");
        verifyNoInteractions(miners,xmr);
    }
    @Test void failedCpuSaveDoesNotChangeDefaultPayoutMarker() throws Exception {
        when(proxy.coinUrl(Coin.MONERO)).thenReturn("stratum+tcp://127.0.0.1:3335");
        CpuMiner cpu=mock(CpuMiner.class); when(miners.miner(Coin.MONERO)).thenReturn(cpu); when(cpu.stopAll()).thenReturn(true);
        doThrow(new IOException("disk unavailable")).when(xmr).configureXmrig(any(),anyString(),anyString(),anyBoolean());
        assertThatThrownBy(()->service.save(new ConsolePayoutService.Route("monero","stratum+ssl://pool.example:443",wallet,"rig"))).isInstanceOf(IOException.class);
        verify(defaults,never()).markDefault(anyString(),anyBoolean()); assertThat(lock.busy()).isFalse();
    }
    @Test void allGpuRoutesAreValidatedBeforeAnyMinerIsStopped() throws Exception {
        when(proxy.coinUrl(Coin.PEARL)).thenReturn("stratum+tcp://127.0.0.1:3334");
        var first=mock(GpuCoinMiner.class);var second=mock(GpuCoinMiner.class);
        var gpu0=mock(LocalGpuPowerService.Gpu.class);var gpu1=mock(LocalGpuPowerService.Gpu.class);
        when(gpu0.deviceId()).thenReturn("gpu0");when(gpu0.vendor()).thenReturn("NVIDIA");
        when(gpu1.deviceId()).thenReturn("gpu1");when(gpu1.vendor()).thenReturn("AMD");when(gpu1.index()).thenReturn(1);
        when(power.snapshot()).thenReturn(List.of(gpu0,gpu1));
        when(miners.gpuMinerAdapters(Coin.PEARL)).thenReturn(List.of(new MinerFactory.GpuMinerAdapter("peakminer",first),new MinerFactory.GpuMinerAdapter("srbminer-multi",second)));
        when(miners.workerUses(Coin.PEARL,"gpu0","peakminer")).thenReturn(true);
        when(miners.workerUses(Coin.PEARL,"gpu1","srbminer-multi")).thenReturn(true);
        doThrow(new IllegalArgumentException("invalid route")).when(second).validateConfig(any());
        assertThatThrownBy(()->service.save(new ConsolePayoutService.Route("pearl","stratum+ssl://pool.example:443","prl1"+"q".repeat(40),"rig"))).hasMessage("invalid route");
        verify(first).validateConfig(any());verify(first,never()).stopAll();verify(second,never()).stopAll();
        verify(first,never()).applyConfig(any());verify(defaults,never()).markDefault(anyString(),anyBoolean());
        assertThat(lock.busy()).isFalse();
    }

}
