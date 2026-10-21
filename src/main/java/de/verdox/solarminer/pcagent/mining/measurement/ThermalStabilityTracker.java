package de.verdox.solarminer.pcagent.mining.measurement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/** Per-setting, continuous loaded-temperature evidence; elapsed time alone is insufficient. */
public final class ThermalStabilityTracker {
    public static final long MIN_LOAD_SECONDS = 180, WINDOW_SECONDS = 90, MAX_LOAD_SECONDS = 300;
    public static final double HEADROOM_C = 3, MAX_RANGE_C = 2, MAX_TREND_C_PER_MINUTE = 0.2;
    private record Reading(long nanos, double temperature) { }
    private final Deque<Reading> readings = new ArrayDeque<>();
    private long started, last;
    private double peak;
    private boolean active;

    public void reset() { readings.clear(); active = false; peak = 0; }

    /** Call only for a valid loaded sample at unchanged cap/core/memory settings. */
    public void add(long now, double temperature) {
        if (!Double.isFinite(temperature) || temperature <= 0) { reset(); return; }
        if (active && (now <= last || now - last > TimeUnit.SECONDS.toNanos(15))) reset();
        if (!active) { started = now; active = true; }
        last = now;
        peak = Math.max(peak, temperature);
        readings.addLast(new Reading(now, temperature));
        long earliest = now - TimeUnit.SECONDS.toNanos(WINDOW_SECONDS);
        // Retain the reading just before the window boundary to prove coverage.
        while (readings.size() > 2) {
            var iterator = readings.iterator(); iterator.next();
            if (iterator.next().nanos() > earliest) break;
            readings.removeFirst();
        }
    }

    public Evidence evidence(double ceiling) {
        if (!active || readings.size() < 2) return null;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        long first = readings.getFirst().nanos();
        for (Reading reading : readings) {
            double minutes = (reading.nanos() - first) / 60_000_000_000d;
            min = Math.min(min, reading.temperature()); max = Math.max(max, reading.temperature());
            sx += minutes; sy += reading.temperature(); sxx += minutes * minutes; sxy += minutes * reading.temperature();
        }
        double denominator = readings.size() * sxx - sx * sx;
        double trend = denominator <= 0 ? Double.POSITIVE_INFINITY : (readings.size() * sxy - sx * sy) / denominator;
        var result = new Evidence(1, TimeUnit.NANOSECONDS.toSeconds(last - started),
                TimeUnit.NANOSECONDS.toSeconds(last - first), min, max, trend, peak);
        return result.safeFor(ceiling) ? result : null;
    }

    /** Versioned persisted acceptance proof. Missing/older proof is history, not automatic eligibility. */
    public record Evidence(int version, long loadedSeconds, long windowSeconds,
                           double minTemperatureC, double maxTemperatureC, double trendCPerMinute, double peakTemperatureC) {
        public Evidence(int version, long loadedSeconds, long windowSeconds, double minTemperatureC,
                        double maxTemperatureC, double trendCPerMinute) {
            this(version, loadedSeconds, windowSeconds, minTemperatureC, maxTemperatureC, trendCPerMinute, maxTemperatureC);
        }
        public boolean safeFor(double ceiling) {
            return version == 1 && loadedSeconds >= MIN_LOAD_SECONDS && windowSeconds >= WINDOW_SECONDS && windowSeconds <= loadedSeconds
                    && Double.isFinite(ceiling) && Double.isFinite(minTemperatureC) && minTemperatureC > 0
                    && Double.isFinite(maxTemperatureC) && maxTemperatureC >= minTemperatureC
                    && maxTemperatureC - minTemperatureC <= MAX_RANGE_C
                    && maxTemperatureC <= ceiling - HEADROOM_C
                    && Double.isFinite(peakTemperatureC) && peakTemperatureC >= maxTemperatureC && peakTemperatureC < ceiling
                    && Double.isFinite(trendCPerMinute) && Math.abs(trendCPerMinute) <= MAX_TREND_C_PER_MINUTE;
        }
    }
}
