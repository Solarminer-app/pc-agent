package de.verdox.solarminer.pcagent.miner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;

/**
 * Pins the owner-approved Phase 3 drifts (O1 crash restart, O2b GPU-coin payout refresh)
 * and the shared watchdog policy introduced with {@code AbstractMinerEngine}.
 */
class MinerEngineDriftTest {

    private static WorkerRunState erroredRun(String coin, String vendor, int index, int attempts) {
        var gpu = new LocalGpuPowerService.Gpu(vendor, index, "GPU-" + index, "Test GPU", 100, 250,
                100, 250, 200, 150.0, "measured", true, null);
        WorkerRunState run = new WorkerRunState(coin, gpu, coin + "-" + vendor + "-" + index, "x");
        run.status = MinerStats.MinerStatus.ERROR;
        run.restartAttempts = attempts;
        return run;
    }

    @Test
    @SuppressWarnings("unchecked")
    void o1_watchdog_skipsWhileMeasurementRunHoldsAndRespectsTheBoundedBudget() {
        LocalRunLock lock = new LocalRunLock();
        GpuCoinMinerService service = new GpuCoinMinerService(new ObjectMapper(),
                mock(ProxyConfigurationService.class), mock(PearlMinerService.class),
                mock(LocalGpuPowerService.class), mock(MinerConsoleService.class),
                mock(PayoutDefaultsService.class), lock, "/tmp/unused-engine-drift-test");
        Map<String, Object> runs = (Map<String, Object>) ReflectionTestUtils.getField(service, "runs");
        runs.put("ravencoin:NVIDIA:0", erroredRun("ravencoin", "NVIDIA", 0, 0));

        // While a benchmark/sweep owns the hardware no restart may fire.
        lock.tryBegin("benchmark");
        service.restartAfterUnexpectedExit();
        WorkerRunState run = (WorkerRunState) runs.get("ravencoin:NVIDIA:0");
        assertEquals(0, run.restartAttempts);

        // Outside a measurement run the attempt is consumed; the selected coin's config is
        // missing here, so the start attempt fails but the budget accounting still applies.
        lock.end();
        service.restartAfterUnexpectedExit();
        assertEquals(1, run.restartAttempts);

        // The budget is hard-bounded exactly like the XMRig watchdog (3 attempts).
        run.restartAttempts = AbstractMinerEngine.MAX_CRASH_RESTARTS;
        service.restartAfterUnexpectedExit();
        assertEquals(AbstractMinerEngine.MAX_CRASH_RESTARTS, run.restartAttempts);
    }

    @Test
    @SuppressWarnings("unchecked")
    void o1_watchdog_skipsWorkersThatAreHealthyOrStillRunning() {
        GpuCoinMinerService service = new GpuCoinMinerService(new ObjectMapper(),
                mock(ProxyConfigurationService.class), mock(PearlMinerService.class),
                mock(LocalGpuPowerService.class), mock(MinerConsoleService.class),
                mock(PayoutDefaultsService.class), new LocalRunLock(), "/tmp/unused-engine-drift-test");
        Map<String, Object> runs = (Map<String, Object>) ReflectionTestUtils.getField(service, "runs");
        WorkerRunState paused = erroredRun("ethereumclassic", "AMD", 1, 0);
        paused.status = MinerStats.MinerStatus.PAUSED;
        runs.put(paused.key, paused);
        WorkerRunState mining = erroredRun("ethereumclassic", "AMD", 2, 0);
        Process alive = mock(Process.class);
        when(alive.isAlive()).thenReturn(true);
        mining.process = alive;
        runs.put(mining.key, mining);
        service.restartAfterUnexpectedExit();
        assertEquals(0, paused.restartAttempts);
        assertEquals(0, mining.restartAttempts);
    }

    @Test
    void o2b_gpuCoinsRefreshSavedFeeBackendPayoutOnStart() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PayoutDefaultsService payouts = mock(PayoutDefaultsService.class);
        when(payouts.usesDefault("ravencoin")).thenReturn(true);
        when(payouts.resolve("ravencoin")).thenReturn(Optional.of(
                new PayoutDefaultsService.DefaultPayout("ravencoin", "t1",
                        "stratum+tcp://new.house.pool:3333", "RHaGK3iARQdKgZ6VPDP4N5chP3aVgUUfz7.solarminer", null)));
        GpuCoinMinerService service = new GpuCoinMinerService(mapper,
                mock(ProxyConfigurationService.class), mock(PearlMinerService.class),
                mock(LocalGpuPowerService.class), mock(MinerConsoleService.class),
                payouts, new LocalRunLock(), "/tmp/unused-engine-drift-test");
        // Seed a stale saved default route (old house pool) through the public config path.
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("engine-drift");
        ReflectionTestUtils.setField(service, "configDirectory", dir);
        Map<String, Object> configs = (Map<String, Object>) ReflectionTestUtils.getField(service, "configs");
        configs.put("ravencoin", new de.verdox.solarminer.pcagent.miner.MinerConfig(
                "stratum+tcp://old.house.pool:3333", "stratum+tcp://127.0.0.1:3336",
                "RHaGK3iARQdKgZ6VPDP4N5chP3aVgUUfz7", "solarminer", "NVIDIA:0"));

        ReflectionTestUtils.invokeMethod(service, "refreshDefaultPayout", "ravencoin");

        de.verdox.solarminer.pcagent.miner.MinerConfig refreshed = service.configuration("ravencoin");
        assertEquals("stratum+tcp://new.house.pool:3333", refreshed.poolUrl());
        // The refreshed route is persisted next to the in-memory copy.
        assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("solarminer-ravencoin.json")));
    }

    @Test
    void o2b_operatorOwnedRoutesAreNeverTouched() {
        PayoutDefaultsService payouts = mock(PayoutDefaultsService.class);
        when(payouts.usesDefault("ravencoin")).thenReturn(false);
        GpuCoinMinerService service = new GpuCoinMinerService(new ObjectMapper(),
                mock(ProxyConfigurationService.class), mock(PearlMinerService.class),
                mock(LocalGpuPowerService.class), mock(MinerConsoleService.class),
                payouts, new LocalRunLock(), "/tmp/unused-engine-drift-test");
        @SuppressWarnings("unchecked")
        Map<String, Object> configs = (Map<String, Object>) ReflectionTestUtils.getField(service, "configs");
        de.verdox.solarminer.pcagent.miner.MinerConfig operator =
                new de.verdox.solarminer.pcagent.miner.MinerConfig("stratum+tcp://my.pool:3333",
                        "stratum+tcp://127.0.0.1:3336", "RHaGK3iARQdKgZ6VPDP4N5chP3aVgUUfz7", "me", "NVIDIA:0");
        configs.put("ravencoin", operator);
        ReflectionTestUtils.invokeMethod(service, "refreshDefaultPayout", "ravencoin");
        assertSame(operator, service.configuration("ravencoin"));
        verify(payouts, never()).resolve(anyString());
    }

    @Test
    void sharedRestartBudgetPinsTheXmrigAndSrbBoundAndDrainForwardsEveryLine() throws Exception {
        // The XMRig watchdog and the SRB engines (O1) share one bound.
        assertTrue(AbstractMinerEngine.restartBudgetLeft(0));
        assertTrue(AbstractMinerEngine.restartBudgetLeft(AbstractMinerEngine.MAX_CRASH_RESTARTS - 1));
        assertFalse(AbstractMinerEngine.restartBudgetLeft(AbstractMinerEngine.MAX_CRASH_RESTARTS));

        // The shared drain loop exists once for all engines: every stdout line reaches the
        // callback and the thread ends when the process does.
        var engine = new TestEngine();
        Process process = new ProcessBuilder("sh", "-c", "echo first; echo second").start();
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread drain = engine.drain(process, lines::add, failure::set);
        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        drain.join(5_000);
        assertEquals(List.of("first", "second"), lines);
        assertNull(failure.get());
    }

    /** Minimal engine exposing the shared base mechanics without any miner-specific state. */
    private static final class TestEngine extends AbstractMinerEngine {
        @Override protected MinerConsoleService console() { return mock(MinerConsoleService.class); }
        @Override protected ObjectMapper mapper() { return new ObjectMapper(); }
        @Override protected java.util.logging.Logger logger() { return java.util.logging.Logger.getLogger("test-engine"); }
        Thread drain(Process process, java.util.function.Consumer<String> onLine, java.util.function.Consumer<Exception> onError) {
            return drainProcessOutput(process, "test-drain", onLine, onError);
        }
    }

    @Test
    void policiesKeepTheHistoricalPerEngineWatchdogNumbers() {
        SrbMonitorPolicy gpu = SrbMonitorPolicy.gpuCoin();
        SrbMonitorPolicy pearl = SrbMonitorPolicy.pearl();
        // GPU coins: hashrate watchdog 300 s startup / 120 s stall, fee-route check on.
        assertEquals(300, gpu.hashrateStartupSeconds());
        assertEquals(120, gpu.hashrateStallSeconds());
        assertTrue(gpu.feeRouteCheck());
        assertFalse(gpu.jobFreshnessStall());
        // Pearl: job-only watchdog (no hashrate abort), fee route never checked,
        // stall clock keyed on fresh jobs, English detail texts.
        assertEquals(0, pearl.hashrateStartupSeconds());
        assertFalse(pearl.feeRouteCheck());
        assertTrue(pearl.jobFreshnessStall());
        assertTrue(pearl.reportHttpErrorsSeparately());
        // O2a: both engines got the hashrate grace and the usable-job rescue.
        assertTrue(gpu.hashrateGrace());
        assertTrue(pearl.hashrateGrace());
        assertTrue(gpu.usableJobFromHashrate());
        assertTrue(pearl.usableJobFromHashrate());
        // Pearl keeps its job-based pool health; GPU coins require a live hashrate.
        assertFalse(pearl.healthyRequiresHashrate());
        assertTrue(gpu.healthyRequiresHashrate());
        // Pearl's pool watchdog message keeps its historical English shape.
        assertEquals("SRBMiner on NVIDIA:0 has not received a mining job within 90 seconds: tail",
                pearl.poolFailMessage().build(true, "NVIDIA:0", "tail"));
        assertEquals("Pool-Job oder Fee-Route nicht verfügbar",
                gpu.poolFailMessage().build(false, "NVIDIA:0", ""));
    }
}
