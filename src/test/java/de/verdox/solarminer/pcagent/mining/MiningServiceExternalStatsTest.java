package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.HardwareIdentityService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.CpuMiner;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MiningServiceExternalStatsTest {
    @TempDir
    Path tempDirectory;

    @Test
    void reportsLocalWorkerPowerWhenExternalControlIsDisabled() {
        MinerFactory miners = mock(MinerFactory.class);
        CpuMiner cpu = mock(CpuMiner.class);
        when(miners.miner(Coin.MONERO)).thenReturn(cpu);
        when(cpu.getWorkerStats()).thenReturn(new MinerStats.Worker(
                MinerStats.MinerStatus.MINING, "CPU", "RandomX", 0.000001, 60,
                120, 80, 120, 180, 117, List.of(), "CPU", "Test CPU", "cpu",
                null, null, MinerStats.PoolTelemetry.unavailable()));
        for (Coin coin : Coin.gpuCoins()) {
            GpuCoinMiner gpuMiner = mock(GpuCoinMiner.class);
            when(miners.gpuMiner(coin)).thenReturn(gpuMiner);
            when(gpuMiner.workerStats(anyList())).thenReturn(List.of());
        }

        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(
                true, false, Map.of(), Map.of()));
        HardwareIdentityService hardware = mock(HardwareIdentityService.class);
        when(hardware.getDeterministicUuid()).thenReturn(UUID.randomUUID());
        when(hardware.getMacAddress()).thenReturn("00:11:22:33:44:55");
        AgentIdentityService identity = mock(AgentIdentityService.class);
        when(identity.displayName()).thenReturn("Observed PC");
        LocalGpuPowerService gpuPower = mock(LocalGpuPowerService.class);
        when(gpuPower.snapshot()).thenReturn(List.of());

        MiningService service = new MiningService(miners, gpuPower, hardware, controls, identity,
                tempDirectory.resolve("active-coin.txt").toString());
        MinerStats stats = service.getExternalStats();

        assertEquals("Observed PC", stats.name());
        assertEquals(MinerStats.MinerStatus.MINING, stats.miningStatus());
        assertEquals(117, stats.approximatedPowerUsageWatts());
        assertEquals(1, stats.workers().size());
    }
}
