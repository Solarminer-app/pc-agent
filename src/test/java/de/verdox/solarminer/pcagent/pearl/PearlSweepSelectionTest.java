package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PearlSweepSelectionTest {
    @TempDir Path directory;

    @Test
    void sweepTemporarilyExtendsSavedPearlDevicesWithoutChangingDisk() throws Exception {
        var savedGpu = gpu(0);
        var measuredGpu = gpu(1);
        var power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(savedGpu, measuredGpu));
        var mapper = new ObjectMapper();
        var saved = new MinerConfig("stratum+tcp://pool.example:3333", "stratum+tcp://proxy.example:3334",
                "prl1pnctccpanlhgxjgyg2m7np2r4ccjzd78hn4r8gc3skkdywk8nxt0qdzg4l0", "worker", "NVIDIA:0");
        Path file = directory.resolve("pearl.json");
        mapper.writeValue(file.toFile(), saved);
        var pearl = new PearlMinerService(mapper, mock(ProxyConfigurationService.class), power,
                mock(MinerConsoleService.class), mock(PayoutDefaultsService.class), new LocalRunLock(),
                directory.resolve("SRBMiner-MULTI").toString(), file.toString());

        assertEquals(List.of(savedGpu), pearl.selectedGpus());
        pearl.setSweepOverride(new MinerConfig(saved.poolUrl(), saved.proxyUrl(), saved.wallet(), saved.worker(),
                "NVIDIA:0,NVIDIA:1"));
        // Normal starts (including a released sibling) use the saved route/device list.
        assertEquals(List.of(savedGpu), pearl.selectedGpus());
        assertEquals(List.of(savedGpu, measuredGpu), de.verdox.solarminer.pcagent.miner.MinerStartContext.measurement(
                "gpu-1", () -> true, () -> pearl.selectedGpus()));
        pearl.clearSweepOverride();
        assertEquals(List.of(savedGpu), pearl.selectedGpus());
        assertEquals(saved, mapper.readValue(file.toFile(), MinerConfig.class));
    }

    private static LocalGpuPowerService.Gpu gpu(int index) {
        return new LocalGpuPowerService.Gpu("NVIDIA", index, "gpu-" + index, "RTX 2080", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
    }
}
