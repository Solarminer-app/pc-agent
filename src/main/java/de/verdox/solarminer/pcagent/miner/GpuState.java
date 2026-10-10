package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.dto.MinerStats;

/** Per-GPU view of one coin's miner, uniform across every GPU adapter. */
public record GpuState(String vendor, int index, String model, boolean selected,
                       MinerStats.MinerStatus status, boolean running, boolean poolHealthy,
                       boolean manuallyPaused, String connectionDetail, String lastError) { }
