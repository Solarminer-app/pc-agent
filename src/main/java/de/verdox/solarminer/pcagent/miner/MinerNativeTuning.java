package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore;

/**
 * A miner-owned tuning path. Implementations apply the measured profile using the
 * miner's documented command line interface and restart only the affected worker
 * when it is already running.
 */
public interface MinerNativeTuning {
    boolean applyNativeTuning(LocalGpuPowerService.Gpu gpu, AutoTuningProfileStore.Profile profile);

    boolean clearNativeTuning(LocalGpuPowerService.Gpu gpu);

    /**
     * The miner may try documented fixed memory clocks from the driver-reported device list
     * even where the driver's direct memory-lock write is unavailable. Effective-clock
     * readback remains mandatory before the candidate can be accepted.
     */
    default boolean mayProbeFixedMemoryClockWithoutDriverLock() { return false; }
}
