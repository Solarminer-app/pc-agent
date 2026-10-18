package de.verdox.solarminer.pcagent.miner.peak;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PeakMinerContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void parsesObservedApiAndPinsOneGpuToDriverUuid() throws Exception {
        PeakStats stats = PeakStats.parse(json.readTree("""
                {"version":"2.19.1","algo":"pearl","pool":{"connected":true,"difficulty":128,"ping_ms":14},
                 "dev_fee_percent":2.0,"hashrate":812.5,"accepted_shares":7,"invalid_shares":1,
                 "gpus":[{"id":0,"pci_bus_id":"21:00.0","hashrate":812.5,"temperature_c":63}]}
                """));
        assertEquals(812_500, stats.hashrateHs());
        assertEquals(63, stats.temperatureCelsius());
        assertEquals(7L, stats.shares().accepted());
        assertEquals(1L, stats.shares().rejected());
        assertTrue(stats.hasActiveJob());
        ProcessBuilder builder = new ProcessBuilder("peakminer");
        PeakMinerService.isolateGpu(builder, new LocalGpuPowerService.Gpu("NVIDIA", 1,
                "GPU-f87d1d02-9eda-19ac-c35b-f0396c021ee2", "TITAN RTX", 100, 350,
                100, 350, 250, 200d, "measured", true, null));
        assertEquals("GPU-f87d1d02-9eda-19ac-c35b-f0396c021ee2", builder.environment().get("CUDA_VISIBLE_DEVICES"));
    }

    @Test void sendsSemicolonRouteOnlyToThePearlProxy() {
        String pool = "stratum+ssl://prl.kryptex.network:8048";
        var command = PeakMinerService.buildCommand(Path.of("/opt/peakminer"),
                "stratum+tcp://127.0.0.1:3334", pool, "prl1user", "rig-n1", 42111);
        assertTrue(command.contains("--devices"));
        assertTrue(command.contains("0"));
        assertEquals(pool + ";prl1user/rig-n1;x", command.get(command.indexOf("--user") + 1));
        assertFalse(command.contains("--worker"));
        assertEquals("stratum+tcp://127.0.0.1:3334", command.get(command.indexOf("--url") + 1));
    }
}
