package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.ProxyConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GpuCoinMinerServiceTest {
    @Test
    void readsCurrentSrbminerHashrateFieldAndBoundsTransientGaps() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(42_000, GpuCoinMinerService.reportedHashrate(mapper.readTree("""
                {"hashrate":{"gpu":{"total":0},"1m":42000}}
                """)));
        assertEquals(41_000, GpuCoinMinerService.reportedHashrate(mapper.readTree("""
                {"hashrate":{"gpu":{"total":41000},"1m":42000}}
                """)));
        assertEquals(40_000, GpuCoinMinerService.reportedHashrate(mapper.readTree("""
                {"hashrate":{"1min":40000}}
                """)));

        Instant sample = Instant.parse("2026-10-07T10:00:00Z");
        assertEquals(42_000, GpuCoinMinerService.displayedHashrate(0, 42_000, sample, sample.plusSeconds(10)));
        assertEquals(0, GpuCoinMinerService.displayedHashrate(0, 42_000, sample, sample.plusSeconds(21)));
        assertEquals(0, GpuCoinMinerService.displayedHashrate(0, 42_000, null, sample.plusSeconds(1)));
        assertEquals(43_000, GpuCoinMinerService.displayedHashrate(43_000, 42_000, sample, sample.plusSeconds(21)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runningQuantusWorkerIsMiningWhilePoolHealthIsStillUnknown() throws Exception {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-a", "Test GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        var service = new GpuCoinMinerService(new ObjectMapper(), mock(ProxyConfigurationService.class),
                mock(PearlMinerService.class), mock(LocalGpuPowerService.class), mock(MinerConsoleService.class),
                "/tmp/unused-gpu-status-test");
        Class<?> runType = Class.forName(GpuCoinMinerService.class.getName() + "$Run");
        Constructor<?> constructor = runType.getDeclaredConstructor(String.class, LocalGpuPowerService.Gpu.class);
        constructor.setAccessible(true);
        Object run = constructor.newInstance("quantus", gpu);
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        ReflectionTestUtils.setField(run, "process", process);
        ReflectionTestUtils.setField(run, "status", MinerStats.MinerStatus.MINING);
        ReflectionTestUtils.setField(run, "healthy", false);
        ((Map<String, Object>) ReflectionTestUtils.getField(service, "runs")).put("quantus:NVIDIA:0", run);

        assertEquals(MinerStats.MinerStatus.MINING, service.status("quantus"));
        var state = service.gpuStates("quantus", List.of(gpu)).getFirst();
        assertEquals(MinerStats.MinerStatus.MINING, state.status());
        assertTrue(state.running());
        assertFalse(state.poolHealthy());
    }

    @Test
    void validatesWalletAndProxyRouteForBothAlgorithms() {
        String raven = "RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG";
        String etc = "0x" + "a".repeat(40);
        assertDoesNotThrow(() -> GpuCoinMinerService.validate("ravencoin",
                new GpuCoinMinerService.Config("stratum+tcp://pool.example:3333",
                        "stratum+tcp://127.0.0.1:3336", raven, "pc", "NVIDIA:0")));
        assertDoesNotThrow(() -> GpuCoinMinerService.validate("ethereumclassic",
                new GpuCoinMinerService.Config("stratum+ssl://pool.example:5555",
                        "stratum+tcp://127.0.0.1:3337", etc, "pc", "NVIDIA:0,AMD:1")));
        assertThrows(IllegalArgumentException.class, () -> GpuCoinMinerService.validate("ravencoin",
                new GpuCoinMinerService.Config("stratum+tcp://pool.example:3333",
                        "stratum+tcp://127.0.0.1:3336", etc, "pc", "NVIDIA:0")));
        assertFalse(GpuCoinMinerService.validRavencoinAddress(raven.substring(0, raven.length() - 1) + "H"));
        assertThrows(IllegalArgumentException.class, () -> GpuCoinMinerService.validate("ethereumclassic",
                new GpuCoinMinerService.Config("stratum+tcp://pool.example:3333",
                        "stratum+tcp://127.0.0.1:3337", etc, "pc", "all")));
    }

    @Test
    void solarMinerHouseAddressesPassChainFormatChecks() {
        assertTrue(GpuCoinMinerService.validRavencoinAddress("RHaGK3iARQdKgZ6VPDP4N5chP3aVgUUfz7"));
        assertDoesNotThrow(() -> GpuCoinMinerService.validate("ethereumclassic",
                new GpuCoinMinerService.Config("stratum+tcp://etc.2miners.com:1010",
                        "stratum+tcp://127.0.0.1:3337",
                        "0x21211c699D409Ca3802D955caD80Ccc034004993", "solarminer", "NVIDIA:0")));
    }

    @Test
    void encodesPoolInWalletLoginUsedBySrbminer() {
        var config = new GpuCoinMinerService.Config("stratum+ssl://pool.example:5555",
                "stratum+tcp://127.0.0.1:3337", "0x" + "a".repeat(40), "pc", "NVIDIA:0");
        String login = GpuCoinMinerService.encodedLogin(config, "pc-n0");
        String[] fields = login.split("\\.", 4);
        assertEquals(config.wallet(), fields[0]);
        assertEquals("sm1", fields[1]);
        assertEquals(config.poolUrl(), new String(Base64.getUrlDecoder().decode(fields[2]), StandardCharsets.UTF_8));
        assertEquals("pc-n0", fields[3]);
    }

    @Test
    void startsDagAlgorithmsThroughSrbminersGpuOnlyParameter() {
        var config = new GpuCoinMinerService.Config("stratum+tcp://pool.example:5555",
                "stratum+tcp://127.0.0.1:3336", "RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG",
                "pc", "NVIDIA:0");

        List<String> command = GpuCoinMinerService.buildCommand(
                Path.of("SRBMiner-MULTI"), "ravencoin", config, "encoded-login", 13000, 0);

        assertEquals("--algorithm-gpu", command.get(2));
        assertEquals("kawpow", command.get(3));
        assertFalse(command.contains("--algorithm"));
        assertEquals("true", command.get(command.indexOf("--nicehash") + 1));

        List<String> etcCommand = GpuCoinMinerService.buildCommand(
                Path.of("SRBMiner-MULTI"), "ethereumclassic", config, "encoded-login", 14000, 0);
        assertEquals("--algorithm-gpu", etcCommand.get(2));
        assertEquals("etchash", etcCommand.get(3));
        assertFalse(etcCommand.contains("--algorithm"));
        assertEquals("2", etcCommand.get(etcCommand.indexOf("--esm") + 1));
    }

    @Test
    void keepsAnActiveKawpowWorkerHealthyWhenTheCurrentJobIsOlder() {
        assertTrue(GpuCoinMinerService.hasUsablePoolJob(true, false, 31_000_000));
        assertTrue(GpuCoinMinerService.hasUsablePoolJob(true, true, 0));
        assertFalse(GpuCoinMinerService.hasUsablePoolJob(false, false, 31_000_000));
    }
}
