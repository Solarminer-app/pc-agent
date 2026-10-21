package de.verdox.solarminer.pcagent.miner;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.engine.AbstractMinerEngine;
import de.verdox.solarminer.pcagent.miner.engine.WorkerRunState;
import de.verdox.solarminer.pcagent.miner.peak.*;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.miner.quanpool.*;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.miner.wildrig.*;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerConfigurationIsolationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProxyConfigurationService proxy = mock(ProxyConfigurationService.class);
    private final LocalGpuPowerService power = mock(LocalGpuPowerService.class);
    private final MinerConsoleService console = mock(MinerConsoleService.class);
    private final PayoutDefaultsService payouts = mock(PayoutDefaultsService.class);
    private final LocalRunLock lock = new LocalRunLock();

    @ParameterizedTest @ValueSource(strings = {"pearl", "srb", "peak", "quanpool", "wildrig"})
    void selectionChangesNeverStopOrRestartRetainedGpu(String adapter) throws Exception {
        GpuCoinMiner miner = miner(adapter);
        MinerConfig config = config(miner.coin(), "NVIDIA:0,NVIDIA:1");
        miner.applyConfig(config);
        WorkerRunState removed = live(miner, 0), retained = live(miner, 1);
        miner.applyConfig(config(miner.coin(), "NVIDIA:1"));
        assertFalse(removed.running());
        assertFalse(removed.restartAllowed);
        assertTrue(retained.running());
        assertTrue(retained.restartAllowed);
        assertEquals(MinerStats.MinerStatus.MINING, retained.status);
        assertEquals(123, retained.hashrate);
        verify(retained.process, never()).destroy();
        verify(retained.process, never()).destroyForcibly();
        miner.applyConfig(config(miner.coin(), "NVIDIA:0,NVIDIA:1"));
        assertTrue(retained.running());
        assertEquals(123, retained.hashrate);
        assertEquals("NVIDIA:0,NVIDIA:1", miner.configuration().devices());
    }

    @ParameterizedTest @ValueSource(strings = {"pearl", "srb", "peak", "quanpool", "wildrig"})
    void routeValidationCannotStopAnyGpuAndOperatorPolicyAlsoGatesRestoration(String adapter) throws Exception {
        GpuCoinMiner miner = miner(adapter);
        MinerConfig original = config(miner.coin(), "NVIDIA:0,NVIDIA:1");
        miner.applyConfig(original);
        WorkerRunState first = live(miner, 0), second = live(miner, 1);
        assertThrows(IllegalArgumentException.class, () -> miner.applyConfig(new MinerConfig(
                "invalid", original.proxyUrl(), original.wallet(), original.worker(), original.devices())));
        assertTrue(first.running()); assertTrue(second.running());
        Object engine = engine(miner);
        ((AbstractMinerEngine) engine).setWorkerStartPolicy(id -> !"GPU-isolation-0".equals(id));
        assertTrue(miner.stopGpu("NVIDIA", 0));
        assertFalse(miner.resumeGpu("NVIDIA", 0));
        assertEquals("worker_manually_stopped", miner.lastError());
        assertTrue(second.running());
    }

    @ParameterizedTest @ValueSource(strings = {"pearl", "srb", "peak", "quanpool", "wildrig"})
    void apiResponseArrivingAfterStopCannotPublishTelemetryOrTriggerRecovery(String adapter) throws Exception {
        GpuCoinMiner miner = miner(adapter);
        miner.applyConfig(config(miner.coin(), "NVIDIA:0,NVIDIA:1"));
        WorkerRunState state = live(miner, 0);
        var requested = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                byte[] payload = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start(); state.apiPort = server.getAddress().getPort();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                if (adapter.equals("pearl") || adapter.equals("srb"))
                    ReflectionTestUtils.invokeMethod(currentEngine, "monitorLoop", state, state.process,
                            de.verdox.solarminer.pcagent.miner.engine.SrbMonitorPolicy.gpuCoin(),
                            (java.util.function.BooleanSupplier) () -> true,
                            (java.util.function.Predicate<WorkerRunState>) candidate -> true);
                else ReflectionTestUtils.invokeMethod(currentEngine, "monitor", state, state.process);
            });
            try {
                assertTrue(requested.await(5, java.util.concurrent.TimeUnit.SECONDS));
                state.requestStop("operator");
            } finally { release.countDown(); }
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(MinerStats.MinerStatus.PAUSED, state.status);
            assertEquals(0, state.hashrate);
            assertNull(state.error);
            assertFalse(state.restartEligible());
        } finally { release.countDown(); server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"pearl", "srb", "peak", "quanpool", "wildrig"})
    void stopUsesCapturedIdentityWhenDriverInventoryDisappears(String adapter) throws Exception {
        GpuCoinMiner miner = miner(adapter);
        miner.applyConfig(config(miner.coin(), "NVIDIA:0,NVIDIA:1"));
        WorkerRunState first = live(miner, 0), sibling = live(miner, 1);
        when(power.discover()).thenReturn(List.of());
        when(power.snapshot()).thenReturn(List.of());
        assertTrue(miner.stopDevice("GPU-isolation-0"));
        assertFalse(first.running());
        assertFalse(first.restartAllowed);
        assertTrue(sibling.running());
        assertTrue(sibling.restartAllowed);
    }

    @ParameterizedTest @ValueSource(strings = {"pearl", "srb", "peak", "quanpool", "wildrig"})
    void automaticRenewalCannotRefillOrBypassAnExhaustedCrashBudget(String adapter) throws Exception {
        GpuCoinMiner miner = miner(adapter);
        miner.applyConfig(config(miner.coin(), "NVIDIA:0,NVIDIA:1"));
        WorkerRunState failed = live(miner, 0);
        failed.process.toHandle().destroy();
        failed.status = MinerStats.MinerStatus.ERROR;
        failed.restartAttempts = AbstractMinerEngine.MAX_CRASH_RESTARTS;
        assertFalse(miner.resumeGpu("NVIDIA", 0));
        assertEquals(AbstractMinerEngine.MAX_CRASH_RESTARTS, failed.restartAttempts);
        assertTrue(failed.restartAllowed);
        // A fresh explicit operator command starts a new bounded recovery cycle.
        MinerStartContext.operator(() -> miner.resumeGpu("NVIDIA", 0));
        assertEquals(0, failed.restartAttempts);
    }

    @SuppressWarnings("unchecked")
    private WorkerRunState live(GpuCoinMiner miner, int index) {
        var gpu = gpu(index);
        WorkerRunState state = new WorkerRunState(miner.coin().id(), gpu, "console-" + index, "hashing");
        Process process = mock(Process.class); ProcessHandle handle = mock(ProcessHandle.class);
        AtomicBoolean alive = new AtomicBoolean(true);
        when(process.isAlive()).thenAnswer(ignored -> alive.get());
        when(process.toHandle()).thenReturn(handle);
        when(handle.pid()).thenReturn(90000L + index);
        when(handle.isAlive()).thenAnswer(ignored -> alive.get());
        when(handle.descendants()).thenAnswer(ignored -> java.util.stream.Stream.empty());
        doAnswer(ignored -> { alive.set(false); return true; }).when(handle).destroy();
        state.process = process; state.hashrate = 123; state.status = MinerStats.MinerStatus.MINING;
        Map<String, WorkerRunState> runs = (Map<String, WorkerRunState>) ReflectionTestUtils.getField(engine(miner), "runs");
        String key = engine(miner) instanceof GpuCoinMinerService ? state.key : "NVIDIA:" + index;
        runs.put(key, state);
        return state;
    }

    private Object currentEngine;
    private Object engine(GpuCoinMiner miner) { return currentEngine; }
    private GpuCoinMiner miner(String adapter) {
        when(power.discover()).thenReturn(List.of(gpu(0), gpu(1)));
        when(proxy.matches(anyString(), anyString())).thenReturn(true);
        when(proxy.matches(anyString(), any(Coin.class))).thenReturn(true);
        PearlMinerService pearl = new PearlMinerService(mapper, proxy, power, console, payouts, lock,
                directory.resolve("SRBMiner-MULTI").toString(), directory.resolve("pearl.json").toString());
        GpuCoinMinerService srb = new GpuCoinMinerService(mapper, proxy, pearl, power, console, payouts, lock,
                directory.resolve("srb").toString());
        GpuCoinMiner miner = switch (adapter) {
            case "pearl" -> pearl;
            case "peak" -> new PeakMinerService(mapper, proxy, pearl, power, console, payouts, lock,
                    mock(PeakDownloadService.class), directory.resolve("peak.json").toString());
            case "quanpool" -> new QuanPoolMinerService(mapper, proxy, srb, pearl, power, console, payouts, lock,
                    mock(QuanPoolDownloadService.class), directory.resolve("quanpool.json").toString());
            case "wildrig" -> new WildRigMinerService(mapper, proxy, srb, pearl, power, console, payouts, lock,
                    mock(WildRigDownloadService.class), directory.resolve("wildrig.json").toString());
            default -> new MinerFactory(mock(de.verdox.solarminer.pcagent.miner.xmr.XmrMinerService.class), pearl, srb, power)
                    .gpuMiner(Coin.ETHEREUMCLASSIC);
        };
        currentEngine = adapter.equals("srb") ? srb : miner;
        return miner;
    }

    private static MinerConfig config(Coin coin, String devices) {
        String wallet = coin == Coin.PEARL ? "prl1" + "a".repeat(40)
                : coin == Coin.QUANTUS ? "qz" + "a".repeat(40) : "0x" + "a".repeat(40);
        return new MinerConfig("stratum+tcp://pool.example:3333", "stratum+tcp://proxy.example:3334", wallet, "worker", devices);
    }
    private static LocalGpuPowerService.Gpu gpu(int index) {
        return new LocalGpuPowerService.Gpu("NVIDIA", index, "GPU-isolation-" + index,
                "RTX 2080", 100, 250, 100, 250, 200, 150d, "measured", true, null);
    }
}
