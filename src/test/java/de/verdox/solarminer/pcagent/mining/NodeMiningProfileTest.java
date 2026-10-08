package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.HardwareIdentityService;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeMiningProfileTest {
    @TempDir Path directory;
    private AgentControlSettingsService controls() {
        return new AgentControlSettingsService(new ObjectMapper(), directory.resolve("controls.json").toString());
    }

    private AgentIdentityService identity() {
        return new AgentIdentityService(new ObjectMapper(), directory.resolve("agent-identity.json").toString());
    }

    @Test void newInstallDoesNotEnrollHardwareAutomatically() {
        assertFalse(controls().workerEnabled("cpu"));
        assertFalse(controls().workerEnabled("GPU-new"));
    }

    @Test void migratesExistingWorkerPermissionsWithoutDroppingOtherDevices() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("controls.json"),
                "{\"dynamicPowerScalingEnabled\":true,\"externalControlEnabled\":true,\"workerExternalControl\":{\"cpu\":false}}");
        var controls = controls();
        assertFalse(controls.workerEnabled("cpu"));
        assertEquals("pearl", controls.get().coinFor("GPU-a"));
        controls.setWorkerCoin("GPU-a", "none");
        assertFalse(controls().workerEnabled("GPU-a"));
        assertEquals("pearl", controls().get().coinFor("GPU-b"));
        assertFalse(controls().workerEnabled("cpu"));
    }

    @Test void persistsBenchmarkOnlyChoiceAndPreservesItWhenOldSettingsClientSaves() {
        var controls = controls();
        assertTrue(controls.setWorkerCoin("cpu", "none"));
        assertTrue(controls.update(new AgentControlSettingsService.Settings(true, true, Map.of())));
        assertFalse(controls().workerEnabled("cpu"));
        assertEquals("none", controls().get().coinFor("cpu"));
        assertFalse(controls.setWorkerCoin("cpu", "pearl"));
        assertFalse(controls.setWorkerCoin("GPU-a", "monero"));
    }

    @Test void nodeStartsAssignedCoinsRegardlessOfCurrentlyViewedOrPreferredCoin() {
        var controls = controls(); controls.setWorkerCoin("cpu", "none"); controls.setWorkerCoin("GPU-a", "ravencoin");
        var cpu = mock(XmrMinerService.class); var pearl = mock(PearlMinerService.class);
        var coins = mock(GpuCoinMinerService.class); var power = mock(LocalGpuPowerService.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test GPU", 100, 250, 100, 250, 200, 150.0, "measured", true, null);
        when(power.discover()).thenReturn(List.of(gpu));
        when(coins.selected("ravencoin")).thenReturn(List.of(gpu));
        when(coins.eligible("ravencoin")).thenReturn(List.of(gpu));
        when(pearl.stopGpu("NVIDIA", 0)).thenReturn(true);
        when(coins.stopGpu("ethereumclassic", "NVIDIA", 0)).thenReturn(true);
        when(coins.stopGpu("decred", "NVIDIA", 0)).thenReturn(true);
        when(coins.stopGpu("quantus", "NVIDIA", 0)).thenReturn(true);
        when(coins.startGpu("ravencoin", "NVIDIA", 0)).thenReturn(true);
        when(power.setTotalPowerTarget(anyLong(), anyList())).thenReturn(true);
        var mining = new MiningService(cpu, pearl, coins, power, mock(HardwareIdentityService.class), controls, identity(), directory.resolve("coin.txt").toString());
        assertTrue(mining.switchCoin("monero"));
        assertTrue(mining.resumeExternally());
        verify(cpu, never()).startMining();
        verify(coins).startGpu("ravencoin", "NVIDIA", 0);
        assertEquals(250, mining.calculateMaxPowerTargetFromComponents());
        assertTrue(mining.setExternalTarget(200));
        assertEquals(0, mining.desiredCpuPowerTarget());
        verify(cpu, never()).setDesiredPowerUsage(anyLong());
        assertTrue(mining.switchCoin("pearl"));
        assertEquals(250, mining.calculateMaxPowerTargetFromComponents());
    }

    @Test void excludedCpuAndOtherAlgorithmsDoNotAppearInNodeStats() {
        var controls = controls(); controls.setWorkerCoin("cpu", "none"); controls.setWorkerCoin("GPU-a", "ravencoin");
        var cpu = mock(XmrMinerService.class); var pearl = mock(PearlMinerService.class);
        var coins = mock(GpuCoinMinerService.class); var power = mock(LocalGpuPowerService.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test GPU", 100, 250, 100, 250, 200, 150.0, "measured", true, null);
        when(power.discover()).thenReturn(List.of(gpu)); when(coins.selected("ravencoin")).thenReturn(List.of(gpu));
        when(cpu.getWorkerStats()).thenReturn(worker("cpu", "RandomX"));
        when(pearl.workerStats(anyList())).thenReturn(List.of(worker("GPU-a", "PearlHash")));
        when(coins.workerStats(eq("ravencoin"), anyList())).thenReturn(List.of(worker("GPU-a", "kawpow")));
        var mining = new MiningService(cpu, pearl, coins, power, mock(HardwareIdentityService.class), controls, identity(), directory.resolve("coin.txt").toString());
        var workers = mining.getExternallyVisibleWorkerStats();
        assertEquals(1, workers.size()); assertEquals("kawpow", workers.getFirst().currentAlgorithm());
        controls.setWorkerCoin("GPU-a", "none");
        assertTrue(mining.getExternallyVisibleWorkerStats().isEmpty());
        assertEquals(0, mining.calculateMaxPowerTargetFromComponents());
    }

    private static MinerStats.Worker worker(String id, String algorithm) {
        return new MinerStats.Worker(MinerStats.MinerStatus.MINING, id, algorithm, 1, 50, 200, 100, 200, 250, 200, List.of(), "GPU", "Test", id, null, null, MinerStats.PoolTelemetry.unavailable());
    }
}
