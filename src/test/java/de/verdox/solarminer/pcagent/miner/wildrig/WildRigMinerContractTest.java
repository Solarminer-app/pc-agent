package de.verdox.solarminer.pcagent.miner.wildrig;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WildRigMinerContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void parsesObservedApiWithoutInventingAConnectedPool() throws Exception {
        WildRigStats stats = WildRigStats.parse(json.readTree("""
                {"algo":"quantus","version":"1.0.0k","donate_level":2,
                 "connection":{"pool":"127.0.0.1:1","uptime":0,"ping":0},
                 "hashrate":{"threads":[[0,0,0]]},"hwmon":{"busID":[33],"temp":[63]},
                 "results":{"diff_current":0,"shares_accepted":[0],"shares_rejected":[0],"shares_ignored":[0]}}
                """));
        assertFalse(stats.hasActiveJob());
        assertEquals(0, stats.hashrateHs());
        assertEquals(2, stats.feePercent());
        assertEquals(0L, stats.shares().accepted());
    }

    @Test void commandUsesQuantusRelayAndDisablesUnmanagedWatchdog() {
        MinerConfig config = new MinerConfig("stratum+ssl://pool.example:443",
                "stratum+tcp://127.0.0.1:3339", "qz11111111111111111111111111111111111111",
                "rig", "NVIDIA:2");
        String login = WildRigMinerService.encodedLogin(config, "rig-n2");
        var command = WildRigMinerService.buildCommand(Path.of("/opt/wildrig-ng"),
                "stratum+tcp://127.0.0.1:41000", login, 42112);
        assertTrue(command.containsAll(java.util.List.of("--algo", "quantus", "--gpu-list", "0", "--no-watchdog")));
        assertFalse(command.contains(config.poolUrl()));
        assertTrue(login.startsWith(config.wallet() + ".sm1."));
    }
}
