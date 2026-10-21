package de.verdox.solarminer.pcagent.miner;

import java.util.function.Supplier;

/** Distinguishes a new operator command from automatic target renewal and crash recovery. */
public final class MinerStartContext {
    private enum Reason { OPERATOR, RECOVERY, MEASUREMENT }
    private static final ThreadLocal<Reason> REASON = new ThreadLocal<>();
    private record Measurement(String deviceId, java.util.function.BooleanSupplier valid) { }
    private static final ThreadLocal<Measurement> MEASUREMENT = new ThreadLocal<>();
    private MinerStartContext() { }

    public static boolean operatorRequested() { return REASON.get() == Reason.OPERATOR; }
    public static boolean mayRetryFailure() { return REASON.get() != null; }
    public static <T> T operator(Supplier<T> action) { return with(Reason.OPERATOR, action); }
    public static <T> T recovery(Supplier<T> action) { return with(Reason.RECOVERY, action); }

    /** Temporary per-device authority; never changes the persisted operator pause. */
    public static boolean measurementAuthorized(String deviceId) {
        Measurement current = MEASUREMENT.get();
        return current != null && current.deviceId().equals(deviceId) && current.valid().getAsBoolean();
    }

    public static <T> T measurement(String deviceId, java.util.function.BooleanSupplier valid, Supplier<T> action) {
        Measurement previous = MEASUREMENT.get();
        MEASUREMENT.set(new Measurement(deviceId, valid));
        try { return with(Reason.MEASUREMENT, action); }
        finally {
            if (previous == null) MEASUREMENT.remove(); else MEASUREMENT.set(previous);
        }
    }

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
