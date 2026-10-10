package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.miner.CoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

/** Stops locally managed miners if the bundled proxy loses its fee route. */
@Service
public class StandaloneFeeGuard {
    private static final Logger LOGGER = Logger.getLogger(StandaloneFeeGuard.class.getName());
    private final ProxyConfigurationService proxy;
    private final MinerFactory miners;

    public StandaloneFeeGuard(ProxyConfigurationService proxy, MinerFactory miners) {
        this.proxy = proxy;
        this.miners = miners;
    }

    @Scheduled(fixedDelay = 10_000)
    public void enforce() {
        if (!proxy.standalone()) return;
        for (Coin coin : Coin.miningCoins()) {
            CoinMiner miner = miners.miner(coin);
            if (miner.running() && !proxy.miningReady(coin.id())) {
                LOGGER.warning("Stopping miner for " + coin.id() + ": standalone proxy or fee target unavailable");
                miner.stopAll();
            }
        }
    }
}
