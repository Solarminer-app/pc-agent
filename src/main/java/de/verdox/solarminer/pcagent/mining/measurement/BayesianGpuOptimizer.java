package de.verdox.solarminer.pcagent.mining.measurement;

import java.util.ArrayList;
import java.util.List;

/** Small bounded Gaussian-process expected-improvement chooser for discrete driver-safe candidates. */
final class BayesianGpuOptimizer {
    private static final double LENGTH_SCALE = 0.38, NOISE = 1e-6;
    private BayesianGpuOptimizer() { }

    static Candidate next(List<Candidate> candidates, List<Observation> observations) {
        return next(candidates, observations, observations);
    }

    /** Prior measurements inform the model; only candidates attempted in this run are excluded. */
    static Candidate next(List<Candidate> candidates, List<Observation> observations, List<Observation> attempted) {
        List<Candidate> available = candidates.stream()
                .filter(candidate -> attempted.stream().noneMatch(row -> row.candidate().equals(candidate))).toList();
        if (available.isEmpty()) return null;
        if (observations.isEmpty()) return available.getFirst();
        if (observations.size() == 1) return farthest(available, observations);
        double scale = Math.max(1, observations.stream().mapToDouble(row -> Math.abs(row.score())).max().orElse(0));
        double[][] covariance = new double[observations.size()][observations.size()];
        double[] y = new double[observations.size()];
        for (int i = 0; i < observations.size(); i++) {
            y[i] = observations.get(i).score() / scale;
            for (int j = 0; j < observations.size(); j++)
                covariance[i][j] = kernel(observations.get(i).candidate().vector(), observations.get(j).candidate().vector())
                        + (i == j ? NOISE : 0);
        }
        double[] alpha = solve(covariance, y);
        double best = observations.stream().mapToDouble(row -> row.score() / scale).max().orElse(0);
        Candidate choice = null; double bestEi = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : available) {
            double[] k = new double[observations.size()];
            double mean = 0;
            for (int i = 0; i < k.length; i++) { k[i] = kernel(candidate.vector(), observations.get(i).candidate().vector()); mean += k[i] * alpha[i]; }
            double[] projected = solve(covariance, k);
            double variance = Math.max(1e-9, 1 - dot(k, projected));
            double sd = Math.sqrt(variance), z = (mean - best) / sd;
            double ei = (mean - best) * cdf(z) + sd * density(z);
            if (ei > bestEi) { bestEi = ei; choice = candidate; }
        }
        return choice;
    }

    private static Candidate farthest(List<Candidate> candidates, List<Observation> rows) {
        return candidates.stream()
                .max(java.util.Comparator.comparingDouble(c -> rows.stream().mapToDouble(o -> distance(c.vector(), o.candidate().vector())).min().orElse(0)))
                .orElse(null);
    }

    private static double kernel(double[] a, double[] b) { return Math.exp(-0.5 * distance(a, b) / (LENGTH_SCALE * LENGTH_SCALE)); }
    private static double distance(double[] a, double[] b) { double sum = 0; for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; sum += d * d; } return sum; }
    private static double dot(double[] a, double[] b) { double sum = 0; for (int i = 0; i < a.length; i++) sum += a[i] * b[i]; return sum; }

    /** Stable Gaussian elimination with diagonal jitter; sample batches stay small (&lt;20). */
    private static double[] solve(double[][] matrix, double[] vector) {
        int n = vector.length; double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) { System.arraycopy(matrix[i], 0, a[i], 0, n); a[i][n] = vector[i]; }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            double[] swap = a[col]; a[col] = a[pivot]; a[pivot] = swap;
            if (Math.abs(a[col][col]) < 1e-12) a[col][col] += 1e-9;
            double scale = a[col][col];
            for (int j = col; j <= n; j++) a[col][j] /= scale;
            for (int row = 0; row < n; row++) if (row != col) {
                double factor = a[row][col];
                for (int j = col; j <= n; j++) a[row][j] -= factor * a[col][j];
            }
        }
        double[] result = new double[n]; for (int i = 0; i < n; i++) result[i] = a[i][n]; return result;
    }

    private static double density(double z) { return Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI); }
    private static double cdf(double z) {
        double x = Math.abs(z), t = 1 / (1 + 0.2316419 * x);
        double p = 1 - density(x) * (0.319381530 * t - 0.356563782 * t * t + 1.781477937 * Math.pow(t, 3)
                - 1.821255978 * Math.pow(t, 4) + 1.330274429 * Math.pow(t, 5));
        return z < 0 ? 1 - p : p;
    }

    record Candidate(Integer coreMhz, Integer memoryMhz, int powerWatts,
                     double corePosition, double memoryPosition, double powerPosition) {
        double[] vector() { return new double[]{corePosition, memoryPosition, powerPosition}; }
    }
    record Observation(Candidate candidate, double score, boolean stable, String detail,
                       AutoTuningSessionService.SampleSummary sample) {
        Observation(Candidate candidate, double score, boolean stable, String detail) {
            this(candidate, score, stable, detail, null);
        }
    }
}
