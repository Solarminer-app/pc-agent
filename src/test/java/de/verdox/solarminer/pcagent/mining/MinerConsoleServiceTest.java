package de.verdox.solarminer.pcagent.mining;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinerConsoleServiceTest {
    @TempDir Path directory;

    @Test
    void newMinerRunTruncatesPriorOutputAndChangesRunId() throws Exception {
        MinerConsoleService consoles = new MinerConsoleService(directory);
        consoles.append("monero", "old output");
        String firstRun = consoles.read("monero", 0).runId();

        consoles.started("monero");
        MinerConsoleService.ConsoleChunk current = consoles.read("monero", 0);
        String output = new String(Base64.getDecoder().decode(current.data()), StandardCharsets.UTF_8);

        assertNotEquals(firstRun, current.runId());
        assertTrue(output.contains("New miner start"));
        assertFalse(output.contains("old output"));
    }

    @Test
    void gpuCoinConsolesCanStartAndReadAggregateAndDeviceOutput() throws Exception {
        MinerConsoleService consoles = new MinerConsoleService(directory);
        for (String coin : new String[]{"ravencoin", "ethereumclassic"}) {
            for (String name : new String[]{coin, coin + "-NVIDIA-0"}) {
                consoles.started(name);
                consoles.append(name, "job received");
                String output = new String(Base64.getDecoder().decode(consoles.read(name, 0).data()), StandardCharsets.UTF_8);
                assertTrue(output.contains("job received"));
            }
        }
    }
}
