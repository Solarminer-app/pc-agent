package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

/** Stops locally managed miners if the bundled proxy loses its fee route. */
@Service
public class StandaloneFeeGuard {
    private static final Logger LOGGER = Logger.getLogger(StandaloneFeeGuard.class.getName());
    private final ProxyConfigurationService proxy;
    private final XmrMinerService xmr;
    private final PearlMinerService pearl;
    private final GpuCoinMinerService gpuCoins;

    public StandaloneFeeGuard(ProxyConfigurationService proxy, XmrMinerService xmr, PearlMinerService pearl,
                              GpuCoinMinerService gpuCoins) {
        this.proxy = proxy;
        this.xmr = xmr;
        this.pearl = pearl;
        this.gpuCoins = gpuCoins;
    }

    @Scheduled(fixedDelay = 10_000)
    public void enforce() {
        if (!proxy.standalone()) return;
        if (xmr.isMiningProcessAlive() && !proxy.miningReady("monero")) {
            LOGGER.warning("Stopping XMRig: standalone proxy or Monero fee target unavailable");
            xmr.hardStopMining();
        }
        if (pearl.running() && !proxy.miningReady("pearl")) {
            LOGGER.warning("Stopping SRBMiner-MULTI: standalone proxy or Pearl fee target unavailable");
            pearl.stop();
        }
        for (String coin : java.util.List.of("ravencoin", "ethereumclassic", "decred", "quantus")) {
            if (gpuCoins.running(coin) && !proxy.miningReady(coin)) {
                LOGGER.warning("Stopping SRBMiner-MULTI: " + coin + " fee route unavailable");
                gpuCoins.stop(coin);
            }
        }
    }
}
