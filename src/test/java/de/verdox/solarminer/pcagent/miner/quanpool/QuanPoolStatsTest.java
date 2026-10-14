package de.verdox.solarminer.pcagent.miner.quanpool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuanPoolStatsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesHiveStatsWithUnitsSharesAndPoolQuality() throws Exception {
        QuanPoolStats stats = QuanPoolStats.parse(json.readTree("""
                {"hs":[812345.5],"hs_units":"khs","temp":[63],"ar":[17,2],
                 "ver":"6.4.0","algo":"qpow","fee_percent":2.0,"transport":"stratum+tcp",
                 "active_jobs":1,"share_difficulty":2048.5,"best_share_difficulty":9001,
                 "pool_rtt_ms":41,"share_roundtrip_ms":52}
                """));

        assertEquals(812_345_500d, stats.hashrateHs());
        assertEquals(63d, stats.temperatureCelsius());
        assertEquals(17L, stats.shares().accepted());
        assertEquals(2L, stats.shares().rejected());
        assertEquals(2048.5, stats.shares().difficulty());
        assertEquals(9001d, stats.shares().bestShare());
        assertEquals(52L, stats.shares().latencyMs());
        assertTrue(stats.hasActiveJob());
        assertEquals(2d, stats.feePercent());
    }

    @Test
    void acceptsCapturedDisconnectedFixtureWithoutInventingShares() throws Exception {
        QuanPoolStats stats = QuanPoolStats.parse(json.readTree("""
                {"hs":[],"hs_units":"khs","temp":[],"uptime":0,"ver":"6.4.0","ar":[0,0],
                 "algo":"qpow","fee_percent":2.0,"source":"Server","transport":"stratum+tcp",
                 "pool_rtt_ms":0,"share_roundtrip_ms":0,"active_jobs":0}
                """));
        assertEquals(0, stats.hashrateHs());
        assertFalse(stats.hasActiveJob());
        assertEquals(0L, stats.shares().accepted());
        assertNull(stats.shares().difficulty());
    }

    @Test
    void commandPinsGpuOnlyProxyModeAndDoesNotExposeThePoolUrl() {
        String pool = "stratum+ssl://pool.example:443";
        MinerConfig config = new MinerConfig(pool, "stratum+tcp://127.0.0.1:3339",
                "qz11111111111111111111111111111111111111", "rig", "NVIDIA:0");
        String login = QuanPoolMinerService.encodedLogin(config, "rig-n0");
        var command = QuanPoolMinerService.buildCommand(Path.of("/opt/quanpool-miner"),
                "stratum+tcp://127.0.0.1:41000", login, 19000);

        assertEquals("serve", command.get(1));
        assertTrue(command.containsAll(java.util.List.of("--cpu-workers", "0", "--gpu-devices", "1",
                "--metrics-port", "19000")));
        assertFalse(command.contains(pool));
        assertTrue(login.startsWith(config.wallet() + ".sm1."));
    }

    @Test
    void isolatesAnySelectedNvidiaGpuByItsStableDriverUuid() {
        LocalGpuPowerService.Gpu gpu = gpu(7, "GPU-12345678-1234-5678-9abc-def012345678");
        ProcessBuilder builder = new ProcessBuilder("quanpool-miner");

        QuanPoolMinerService.isolateGpu(builder, gpu);

        assertEquals(gpu.deviceId(), builder.environment().get("CUDA_VISIBLE_DEVICES"));
    }

    @Test
    void rejectsNonNvidiaVisibilitySelection() {
        ProcessBuilder builder = new ProcessBuilder("quanpool-miner");
        assertThrows(IllegalArgumentException.class,
                () -> QuanPoolMinerService.isolateGpu(builder, new LocalGpuPowerService.Gpu(
                        "AMD", 2, "0000:03:00.0", "AMD", 100, 300, 100, 300,
                        200, 150d, "measured", true, null)));
    }

    @Test
    void acceptsArbitraryAndMultipleNvidiaWorkerIndices() {
        assertDoesNotThrow(() -> QuanPoolMinerService.validateDeviceSelection("NVIDIA:2,NVIDIA:7"));
        assertThrows(IllegalArgumentException.class,
                () -> QuanPoolMinerService.validateDeviceSelection("NVIDIA:2,NVIDIA:2"));
        assertThrows(IllegalArgumentException.class,
                () -> QuanPoolMinerService.validateDeviceSelection("NVIDIA:2,AMD:0"));
    }

    @Test
    void resolvesEveryConfiguredNvidiaWorkerWithoutAssumingContiguousIndices() {
        GpuCoinMinerService configurations = mock(GpuCoinMinerService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        MinerConfig config = new MinerConfig("stratum+tcp://global.quanpool.com:9854",
                "stratum+tcp://127.0.0.1:3339", "qz11111111111111111111111111111111111111",
                "rig", "NVIDIA:2,NVIDIA:7");
        when(configurations.configuration("quantus")).thenReturn(config);
        when(power.discover()).thenReturn(java.util.List.of(gpu(0, "GPU-0"), gpu(2, "GPU-2"), gpu(7, "GPU-7")));
        QuanPoolMinerService service = new QuanPoolMinerService(json, mock(ProxyConfigurationService.class),
                configurations, mock(PearlMinerService.class), power, mock(MinerConsoleService.class),
                mock(PayoutDefaultsService.class), mock(LocalRunLock.class), mock(QuanPoolDownloadService.class));

        assertEquals(java.util.List.of(2, 7), service.selectedGpus().stream()
                .map(LocalGpuPowerService.Gpu::index).toList());
    }

    private static LocalGpuPowerService.Gpu gpu(int index, String uuid) {
        return new LocalGpuPowerService.Gpu("NVIDIA", index, uuid, "RTX Test", 100, 400,
                100, 400, 250, 200d, "measured", true, null);
    }

    @Test
    void releaseMetadataMatchesTheOfficialPinnedArtifact() {
        assertEquals("6.4.0", QuanPoolDownloadService.VERSION);
        assertEquals(15_054_128L, QuanPoolDownloadService.EXPECTED_SIZE);
        assertEquals(64, QuanPoolDownloadService.SHA256.length());
        assertEquals("https", QuanPoolDownloadService.DOWNLOAD.getScheme());
        assertEquals("download.quanpool.com", QuanPoolDownloadService.DOWNLOAD.getHost());
    }
}
