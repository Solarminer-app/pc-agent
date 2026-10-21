package de.verdox.solarminer.pcagent.mining.measurement;

import java.util.*;

/** Work budgets change coverage, never the safety or sample acceptance thresholds. */
public enum BenchmarkPreset {
    QUICK(18 * 60, 3, 3, 3), MEDIUM(55 * 60, 6, 12, 6), SLOW(0, Integer.MAX_VALUE, 24, 9);
    private final int seconds, points, search, confirmations;
    BenchmarkPreset(int seconds, int points, int search, int confirmations) {
        this.seconds = seconds; this.points = points; this.search = search; this.confirmations = confirmations;
    }
    public int seconds() { return seconds; }
    int searchCandidates() { return search; }
    int searchSamples() { return this == QUICK ? 8 : this == MEDIUM ? 12 : 16; }
    int confirmationCandidates() { return confirmations; }
    public static BenchmarkPreset parse(String value) {
        if (value == null) return SLOW; // Existing clients retain the exhaustive path.
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("lab_preset_invalid"); }
    }
    List<Integer> limits(List<Integer> full) {
        if (full.size() <= points) return full;
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < points; i++) selected.add(full.get((int) Math.round(i * (full.size() - 1d) / (points - 1))));
        return List.copyOf(selected);
    }
}
