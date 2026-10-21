package de.verdox.solarminer.pcagent.miner.engine;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerLifecycleTest {
    private final LocalGpuPowerService.Gpu gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "GPU-test",
            "RTX 2080", 100, 250, 100, 250, 200, 150d, "measured", true, null);

    @Test void oldCallbacksCannotIdentifyTheReplacementAsTheirRun() {
        WorkerRunState old = new WorkerRunState("pearl", gpu, "console", "");
        old.process = mock(Process.class);
        old.status = MinerStats.MinerStatus.ERROR;
        old.restartAttempts = 2;
        WorkerRunState next = WorkerRunState.nextAttempt(old, "pearl", gpu, "console", "");
        next.process = mock(Process.class);
        next.status = MinerStats.MinerStatus.MINING;
        next.hashrate = 123;
        assertNotSame(old, next);
        assertEquals(2, next.restartAttempts);
        assertFalse(old.monitors(old.process));
        assertFalse(old.restartEligible());
        assertFalse(next.monitors(old.process));
        assertTrue(next.monitors(next.process));
        old.status = MinerStats.MinerStatus.ERROR; // delayed callback still holds the old object
        old.hashrate = 0;
        assertEquals(MinerStats.MinerStatus.MINING, next.status);
        assertEquals(123, next.hashrate);
    }

    @Test void failedStopIsVisibleButCannotTriggerRecoveryOrReplaceALiveProcess() {
        WorkerRunState run = new WorkerRunState("pearl", gpu, "console", "");
        run.process = mock(Process.class);
        when(run.process.isAlive()).thenReturn(true);
        run.status = MinerStats.MinerStatus.MINING;
        run.requestStop("pause");
        assertFalse(run.finishStop(false));
        assertEquals(MinerStats.MinerStatus.ERROR, run.status);
        assertEquals("worker_stop_failed", run.error);
        assertFalse(run.restartEligible());
        assertFalse(run.monitors(run.process));
        assertSame(run, WorkerRunState.nextAttempt(run, "pearl", gpu, "console", ""));
        when(run.process.isAlive()).thenReturn(false);
        assertFalse(run.restartEligible());
    }

    @Test void recoveryIsBoundedAndBacksOffWithoutAffectingASibling() {
        WorkerRunState run = new WorkerRunState("pearl", gpu, "console", "");
        WorkerRunState sibling = new WorkerRunState("pearl", gpu, "other-console", "");
        run.status = sibling.status = MinerStats.MinerStatus.ERROR;
        assertTrue(run.restartEligible());
        run.scheduleRecovery();
        assertFalse(run.restartEligible());
        assertTrue(sibling.restartEligible());
        run.requestStop("operator");
        assertFalse(run.restartEligible());
        WorkerRunState retry = WorkerRunState.nextAttempt(run, "pearl", gpu, "console", "");
        retry.status = MinerStats.MinerStatus.ERROR;
        retry.restartAttempts = AbstractMinerEngine.MAX_CRASH_RESTARTS;
        assertFalse(retry.restartEligible());
    }
}
