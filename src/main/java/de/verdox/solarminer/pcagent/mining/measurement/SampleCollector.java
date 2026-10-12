package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.dto.MinerStats;

import java.util.Arrays;
import java.util.List;

/**
 * Shared sample math for local measurement runs (refactoring Phase 3, A5). The median used
 * to be written out literally in the benchmark summaries and again in the efficiency sweep;
 * the valid-hashrate predicate existed in both sample loops with the same shape. The
 * collection loops themselves stay service-specific on purpose — the benchmark samples
 * workers per phase while the sweep samples rate/watt pairs per power step — only the
 * arithmetic and validity rules are common.
 */
public final class SampleCollector {
    private SampleCollector() { }

    /** Median of the given values; the input array is not modified. Empty input yields 0. */
    public static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return medianOfSorted(sorted);
    }

    public static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return medianOfSorted(sorted);
    }

    private static double medianOfSorted(double[] sorted) {
        if (sorted.length == 0) return 0;
        return sorted.length % 2 == 1 ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }

    /** A sample counts only with a finite, positive hashrate (both measurement loops agree). */
    public static boolean hasValidHashrate(MinerStats.Worker worker) {
        return Double.isFinite(worker.terahashPerSecond()) && worker.terahashPerSecond() > 0;
    }
}
