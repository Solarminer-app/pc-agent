package de.verdox.solarminer.pcagent.mining;

import java.util.function.Supplier;

/** Carries the initiating control path to the process-stop boundary and preserves nested callers. */
public final class MinerStopContext {
    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();

    private MinerStopContext() { }

    public static <T> T with(String source, Supplier<T> action) {
        String previous = SOURCE.get();
        SOURCE.set(source);
        try { return action.get(); }
        finally {
            if (previous == null) SOURCE.remove(); else SOURCE.set(previous);
        }
    }

    public static <T> T withDefault(String source, Supplier<T> action) {
        return SOURCE.get() == null ? with(source, action) : action.get();
    }

    public static String source() { return SOURCE.get() == null ? "Unspecified local operation" : SOURCE.get(); }
}
