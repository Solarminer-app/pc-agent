package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.dto.MinerStats;

/** The CPU worker adds capability data that only a thread-scaled CPU miner can supply. */
public interface CpuMiner extends CoinMiner {
    boolean readyForStart();

    /**
     * The operator's saved payout route as the coin-independent {@link MinerConfig}
     * (poolUrl = local proxy route, wallet/worker split from the miner login), or null
     * when no valid route is saved. Parsing the coin-specific login format stays here.
     */
    MinerConfig savedRoute();

    long getMinimumControllablePowerWatts();

    long getEstimatedMaxCpuWattage();

    MinerStats.Worker getWorkerStats();
}
