package de.verdox.solarminer.pcagent.support;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bounded graceful/forced termination of an explicitly owned set of process trees. */
public final class ProcessTermination {
    private ProcessTermination() { }

    /**
     * Snapshot descendants before signalling their parent: once a parent exits the OS may
     * reparent its children, making a later descendants() query lose them. Each phase has
     * one shared deadline for the entire batch. Success includes children and force kills.
     */
    public static boolean terminate(Collection<ProcessHandle> processes, Duration grace) {
        if (grace.isNegative()) throw new IllegalArgumentException("Negative process termination grace");
        var targets = new LinkedHashMap<Long, ProcessHandle>();
        for (ProcessHandle root : processes) {
            root.descendants().forEach(child -> targets.putIfAbsent(child.pid(), child));
            targets.putIfAbsent(root.pid(), root);
        }
        List<ProcessHandle> snapshot = List.copyOf(targets.values());
        snapshot.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        if (awaitExit(snapshot, grace)) return true;
        // Include any children created while the original graceful signal was pending.
        for (ProcessHandle root : snapshot)
            root.descendants().forEach(child -> targets.putIfAbsent(child.pid(), child));
        snapshot = List.copyOf(targets.values());
        snapshot.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        return awaitExit(snapshot, grace);
    }

    private static boolean awaitExit(List<ProcessHandle> targets, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (targets.stream().anyMatch(ProcessHandle::isAlive)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) return false;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
