package de.verdox.solarminer.pcagent.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The one escalation sequence for stopping managed child processes: request a graceful
 * shutdown of the process and its descendants, wait until a shared deadline, then force-kill
 * whatever survived together with its descendants. Used by the miner process registry and
 * the managed proxy child management; the per-call deadline is shared across the whole
 * batch so a batch never waits N times the grace period.
 */
public final class ProcessTermination {

    private ProcessTermination() { }

    /** True when no process in {@code processes} is alive after the escalation. */
    public static boolean terminate(Collection<ProcessHandle> processes, Duration grace) {
        List<ProcessHandle> targets = new ArrayList<>(processes);
        for (ProcessHandle target : targets) {
            target.descendants().forEach(ProcessHandle::destroy);
            target.destroy();
        }
        long deadline = System.nanoTime() + grace.toNanos();
        for (ProcessHandle target : targets) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (target.isAlive()) target.destroyForcibly();
            target.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }
        return targets.stream().noneMatch(ProcessHandle::isAlive);
    }
}
