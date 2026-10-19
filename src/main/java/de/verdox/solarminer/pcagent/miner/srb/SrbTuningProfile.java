package de.verdox.solarminer.pcagent.miner.srb;

import de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore;

/** Values represented by SRBMiner's documented NVIDIA OC command line parameters. */
public record SrbTuningProfile(Integer coreMhz, Integer memoryMhz, Integer memoryOffsetMhz,
                               Integer powerLimitWatts) {
    public static SrbTuningProfile from(AutoTuningProfileStore.Profile profile) {
        return new SrbTuningProfile(profile.coreMhz(), profile.memoryMhz(), profile.memoryOffsetMhz(),
                profile.powerLimitWatts());
    }
}
