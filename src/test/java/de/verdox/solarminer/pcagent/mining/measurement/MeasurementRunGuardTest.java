package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the shared measurement-run mechanics (Phase 3, A5) that benchmark and sweep now share. */
class MeasurementRunGuardTest {

    @Test
    void medianHandlesEmptyOddAndEvenSamplesWithoutMutatingInput() {
        assertEquals(0, SampleCollector.median(new double[0]));
        assertEquals(3, SampleCollector.median(new double[]{5, 1, 3}));
        assertEquals(2.5, SampleCollector.median(new double[]{4, 1, 2, 3}));
        double[] input = {5, 1};
        SampleCollector.median(input);
        assertEquals(5, input[0]);
        assertEquals(1, input[1]);
        assertEquals(2, SampleCollector.median(List.of(3.0, 1.0, 2.0)));
    }

    @Test
    void validHashrateRuleRejectsNaNZeroAndNegative() {
        assertTrue(SampleCollector.hasValidHashrate(worker(0.001)));
        assertFalse(SampleCollector.hasValidHashrate(worker(0)));
        assertFalse(SampleCollector.hasValidHashrate(worker(-1)));
        assertFalse(SampleCollector.hasValidHashrate(worker(Double.NaN)));
    }

    @Test
    void oneGuardBlocksTheSiblingGuardUntilEnd() {
        LocalRunLock lock = new LocalRunLock();
        MeasurementRunGuard benchmark = new MeasurementRunGuard(lock, "test-benchmark");
        MeasurementRunGuard sweep = new MeasurementRunGuard(lock, "test-sweep");

        assertTrue(benchmark.begin("benchmark"));
        assertTrue(benchmark.lockBusy());
        assertFalse(sweep.begin("efficiency-sweep"));
        benchmark.end();
        assertFalse(benchmark.lockBusy());
        assertTrue(sweep.begin("efficiency-sweep"));
        assertEquals("efficiency-sweep", lock.holder());
    }

    @Test
    void cancelFlagIsObservedByTheRunLoopAndResetOnNextStart() throws Exception {
        LocalRunLock lock = new LocalRunLock();
        MeasurementRunGuard guard = new MeasurementRunGuard(lock, "test-run");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicBoolean sawCancel = new AtomicBoolean();

        assertTrue(guard.begin("benchmark"));
        guard.resetCancel();
        guard.submit(() -> {
            started.countDown();
            while (!guard.cancelRequested()) {
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
            sawCancel.set(true);
            guard.end();
            ended.countDown();
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        guard.requestCancel();
        assertTrue(ended.await(5, TimeUnit.SECONDS));
        assertTrue(sawCancel.get());
        assertFalse(guard.lockBusy());
        // a following run starts with a clear cancel flag
        guard.resetCancel();
        assertFalse(guard.cancelRequested());
    }

    private static MinerStats.Worker worker(double hashrateTh) {
        return new MinerStats.Worker(MinerStats.MinerStatus.MINING, "w", "RandomX", hashrateTh,
                0, 0, 0, 0, 0, 0, List.of(), "CPU", "test", "cpu", null, null,
                MinerStats.PoolTelemetry.unavailable());
    }
}
