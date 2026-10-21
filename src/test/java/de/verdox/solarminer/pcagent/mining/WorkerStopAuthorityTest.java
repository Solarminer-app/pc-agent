package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.lowlevel.*;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.mining.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerStopAuthorityTest {
    @TempDir Path directory;

    @Test void stopIsIndependentOfBootPreferenceAndSurvivesReload() {
        Path file = directory.resolve("settings.json");
        var controls = new AgentControlSettingsService(new ObjectMapper(), file.toString());
        assertTrue(controls.setWorkerManuallyStopped("GPU-a", true));
        assertFalse(controls.get().startsOnBoot("GPU-a"));
        assertFalse(controls.mayStart("GPU-a"));
        assertTrue(controls.mayStart("GPU-b"));
        assertTrue(controls.setWorkerStartOnBoot("GPU-a", true));
        assertFalse(controls.mayStart("GPU-a"));
        var reloaded = new AgentControlSettingsService(new ObjectMapper(), file.toString());
        assertFalse(reloaded.mayStart("GPU-a"));
        assertTrue(reloaded.setWorkerManuallyStopped("GPU-a", false));
        assertTrue(reloaded.mayStart("GPU-a"));
    }

    @Test void storageFailureStillBlocksStartsForThisAgentLifetime() throws Exception {
        Path parent = directory.resolve("unwritable"); Files.writeString(parent, "file, not directory");
        var controls = new AgentControlSettingsService(new ObjectMapper(), parent.resolve("settings.json").toString());
        assertFalse(controls.setWorkerManuallyStopped("GPU-a", true));
        assertFalse(controls.mayStart("GPU-a"));
        assertTrue(controls.mayStart("GPU-b"));
        assertFalse(controls.setWorkerManuallyStopped("GPU-a", false));
        assertFalse(controls.mayStart("GPU-a"));
    }

    @Test void physicalStopDisarmsFailedAdaptersAndContinuesAfterOneThrows() {
        var factory = mock(MinerFactory.class);
        when(factory.miner(Coin.MONERO)).thenReturn(mock(CpuMiner.class));
        for (Coin coin : Coin.gpuCoins()) when(factory.gpuMiner(coin)).thenReturn(mock(GpuCoinMiner.class));
        var active = mock(GpuCoinMiner.class); var failed = mock(GpuCoinMiner.class);
        when(factory.gpuMinerAdapters(Coin.PEARL)).thenReturn(List.of(new MinerFactory.GpuMinerAdapter("active", active),
                new MinerFactory.GpuMinerAdapter("failed", failed)));
        when(active.stopDevice("GPU-a")).thenThrow(new IllegalStateException("test stop failure"));
        when(failed.stopDevice("GPU-a")).thenReturn(true);
        var power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a",
                "RTX 2080", 100, 250, 100, 250, 200, 150d, "measured", true, null)));
        var controls = new AgentControlSettingsService(new ObjectMapper(), directory.resolve("controls.json").toString());
        var hardware = mock(HardwareIdentityService.class);
        when(hardware.getDeterministicUuid()).thenReturn(UUID.randomUUID());
        var mining = new MiningService(factory, power, hardware, controls, mock(AgentIdentityService.class),
                directory.resolve("coin.txt").toString());
        assertThrows(IllegalStateException.class, () -> mining.stopExternalWorker("GPU-a", "operator"));
        verify(active).stopDevice("GPU-a");
        verify(failed).stopDevice("GPU-a");
        verify(failed, never()).stopDevice("GPU-b");
        verify(active, never()).stopAll();
    }
}
