package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.MinerProcessRegistry;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PearlMinerExternalProcessTest {
    @TempDir Path directory;

    @Test
    void externalSrbMinerDoesNotCountAsSuccessfulPearlStart() {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "gpu-a", "Test GPU", 100, 300,
                100, 300, 200, 150.0, "measured", true, null);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(gpu));
        when(power.enforceConfiguredPowerLimit(gpu)).thenReturn(true);
        var pearl = new PearlMinerService(new ObjectMapper(), mock(ProxyConfigurationService.class), power,
                mock(MinerConsoleService.class), mock(PayoutDefaultsService.class), new LocalRunLock(),
                directory.resolve("SRBMiner-MULTI").toString(), directory.resolve("config.json").toString());
        pearl.setBenchmarkOverride(new MinerConfig("stratum+tcp://pool:1", "stratum+tcp://proxy:2",
                "wallet", "worker", "NVIDIA:0"));
        ProcessHandle external = mock(ProcessHandle.class);
        when(external.pid()).thenReturn(123L);

        try (var registry = mockStatic(MinerProcessRegistry.class)) {
            registry.when(() -> MinerProcessRegistry.running("srbminer")).thenReturn(List.of(external));
            registry.when(() -> MinerProcessRegistry.unmanagedGpuCoin(external)).thenReturn(true);
            assertFalse(pearl.startGpu("NVIDIA", 0));
        }

        assertEquals("pearl_external_miner_blocks_start", pearl.lastError());
    }
}
