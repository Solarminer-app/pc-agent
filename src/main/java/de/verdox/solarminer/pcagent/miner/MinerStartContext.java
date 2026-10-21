package de.verdox.solarminer.pcagent.miner;

import java.util.function.Supplier;

/** Distinguishes a new operator command from automatic target renewal and crash recovery. */
public final class MinerStartContext {
    private enum Reason { OPERATOR, RECOVERY }
    private static final ThreadLocal<Reason> REASON = new ThreadLocal<>();
    private MinerStartContext() { }

    public static boolean operatorRequested() { return REASON.get() == Reason.OPERATOR; }
    public static boolean mayRetryFailure() { return REASON.get() != null; }
    public static <T> T operator(Supplier<T> action) { return with(Reason.OPERATOR, action); }
    public static <T> T recovery(Supplier<T> action) { return with(Reason.RECOVERY, action); }

    private static <T> T with(Reason reason, Supplier<T> action) {
        Reason previous = REASON.get();
        REASON.set(reason);
        try { return action.get(); }
        finally {
            if (previous == null) REASON.remove();
            else REASON.set(previous);
        }
    }
}
