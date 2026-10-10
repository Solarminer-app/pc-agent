package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** GPU miners add per-device control and configuration management to the common contract. */
public interface GpuCoinMiner extends CoinMiner {

    List<LocalGpuPowerService.Gpu> selectedGpus();

    /** Selected GPUs that are not manually paused. */
    List<LocalGpuPowerService.Gpu> eligibleGpus();

    List<GpuState> gpuStates(List<LocalGpuPowerService.Gpu> cards);

    boolean startGpu(String vendor, int index);

    boolean stopGpu(String vendor, int index);

    boolean pauseGpu(String vendor, int index);

    boolean resumeGpu(String vendor, int index);

    /** Start every eligible (not manually paused) GPU for a budget plan. */
    boolean startForBudget();

    /** Device ids of this coin's currently running managed processes. */
    Set<String> runningGpuDeviceIds();

    Set<String> manuallyPausedGpuKeys();

    /** Rebuild the exact pause/run state a measurement run captured beforehand. */
    boolean restoreWorkerState(Set<String> pausedBefore, Set<String> runningBefore);

    /** Ephemeral fee-backend config used only by the efficiency sweep; never persisted. */
    void setSweepOverride(MinerConfig config);

    void clearSweepOverride();

    /** The operator configuration, or null when the coin has none. */
    MinerConfig configuration();

    void applyConfig(MinerConfig config) throws IOException;

    /** Coin-specific validation (wallet format, device syntax) of a candidate config. */
    void validateConfig(MinerConfig config);

    /**
     * Algorithm label this miner reports in worker telemetry. Profile and cohort keys are built
     * from it so persisted measurements keep matching across releases.
     */
    String reportedAlgorithm();
}
