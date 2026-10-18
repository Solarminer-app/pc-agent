package de.verdox.solarminer.pcagent.miner.quanpool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuanPoolSweepSelectionTest {
    @TempDir Path directory;

    @Test
    void sweepOverrideTemporarilyExtendsSavedDevices() throws Exception {
        var mapper = new ObjectMapper();
        var saved = new MinerConfig("stratum+tcp://pool.example:9854", "stratum+tcp://proxy.example:3339",
                "qz11111111111111111111111111111111111111", "worker", "NVIDIA:0");
        Path file = directory.resolve("quanpool.json");
        mapper.writeValue(file.toFile(), saved);
        var first = gpu(0);
        var second = gpu(1);
        var power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(first, second));
        var miner = new QuanPoolMinerService(mapper, mock(ProxyConfigurationService.class),
                mock(GpuCoinMinerService.class), mock(PearlMinerService.class), power,
                mock(MinerConsoleService.class), mock(PayoutDefaultsService.class), new LocalRunLock(),
                mock(QuanPoolDownloadService.class), file.toString());

        assertEquals(List.of(first), miner.selectedGpus());
        miner.setSweepOverride(new MinerConfig(saved.poolUrl(), saved.proxyUrl(), saved.wallet(), saved.worker(),
                "NVIDIA:0,NVIDIA:1"));
        assertEquals(List.of(first, second), miner.selectedGpus());
        miner.clearSweepOverride();
        assertEquals(List.of(first), miner.selectedGpus());
        assertEquals(saved, mapper.readValue(file.toFile(), MinerConfig.class));
    }

    private static LocalGpuPowerService.Gpu gpu(int index) {
        return new LocalGpuPowerService.Gpu("NVIDIA", index, "gpu-" + index, "RTX 3080", 100, 300,
                100, 300, 200, 150.0, "measured", true, null);
    }
}
