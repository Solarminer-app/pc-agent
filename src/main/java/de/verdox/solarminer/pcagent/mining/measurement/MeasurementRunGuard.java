package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Shared session mechanics for local hardware-measurement runs (refactoring Phase 3, A5;
 * Phase 5d moved the guard and the sample helpers into the dedicated measurement sub-package).
 * The benchmark session and the efficiency sweep each own exactly one single-threaded run
 * executor, one cancel flag and one {@link LocalRunLock} acquisition; that bookkeeping used
 * to be duplicated verbatim in both services and now exists once here. Each run keeps its
 * own session model, phase plan and measurement loop — only the guard mechanics are shared.
 *
 * <p>Lock discipline is unchanged: {@link #begin(String)} must pair with {@link #end()} in
 * EVERY terminal path of a run, including early-return skips, or the lock leaks forever
 * (the same rule the phase-3 skill record demands).</p>
 */
public final class MeasurementRunGuard {
    private final LocalRunLock lock;
    private final ExecutorService executor;
    private volatile boolean cancel;

    public MeasurementRunGuard(LocalRunLock lock, String threadName) {
        this.lock = lock;
        this.executor = Executors.newSingleThreadExecutor(
                r -> Thread.ofPlatform().name(threadName).daemon(true).unstarted(r));
    }

    /** Claims the shared hardware lock for this run; false when another measurement owns it. */
    public boolean begin(String runName) { return lock.tryBegin(runName); }

    /** Releases the shared hardware lock; call from every terminal path of a run. */
    public void end() { lock.end(); }

    /** True while any local measurement run (this one or the sibling service) owns the hardware. */
    public boolean lockBusy() { return lock.busy(); }

    /** Clears the cancel flag when a new run starts. */
    public void resetCancel() { cancel = false; }

    /** Requests cancellation of the current run; the run loop decides where to observe it. */
    public void requestCancel() { cancel = true; }

    public boolean cancelRequested() { return cancel; }

    public void submit(Runnable task) { executor.submit(task); }

    public boolean awaitTermination(long seconds) {
        try {
            return executor.awaitTermination(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Cancels and shuts the run executor down (service @PreDestroy path). */
    public void close() {
        cancel = true;
        executor.shutdownNow();
    }
}
