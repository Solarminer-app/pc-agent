package de.verdox.solarminer.pcagent.miner.pearl;

import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PearlMinerConsoleIdTest {
    @Test
    void pearlWorkerUsesTheDashboardConsoleIdFormat() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);

        String consoleId = PearlMinerService.workerConsoleId(gpu);

        assertEquals("pearl-NVIDIA-0", consoleId);
        assertTrue(MinerConsoleService.isGpuConsoleId(consoleId));
    }
}
