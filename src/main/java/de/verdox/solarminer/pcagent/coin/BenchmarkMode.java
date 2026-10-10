package de.verdox.solarminer.pcagent.coin;

import java.util.Locale;
import java.util.Optional;

/** Benchmark run modes as offered by the operator UI and the local REST contract. */
public enum BenchmarkMode {
    /** Measure the miners that are currently running, without touching their state. */
    LIVE("LIVE"),
    /** Install/verify configured miners, measure each, then restore the previous state. */
    INSTALLED("INSTALLED");

    private final String wireName;

    BenchmarkMode(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static Optional<BenchmarkMode> from(String value) {
        if (value == null) return Optional.empty();
        for (BenchmarkMode mode : values())
            if (mode.wireName.equalsIgnoreCase(value.strip())) return Optional.of(mode);
        return Optional.empty();
    }
}
