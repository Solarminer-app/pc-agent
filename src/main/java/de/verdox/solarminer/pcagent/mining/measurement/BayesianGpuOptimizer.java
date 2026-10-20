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
        // Supported-clock listings are driver ordered, not efficiency ordered.  Start from a
        // conservative interior point instead of trusting that order (which commonly starts at
        // the highest clock), then deliberately cover a distant point before refining.
        if (attempted.isEmpty()) return efficientInterior(available);
        // Cover the space before trusting any single noisy winner. Keep one in four
        // later trials for exploration even when a promising neighbourhood exists.
        if (attempted.size() < 3 || attempted.size() % 4 == 3) return farthest(available, attempted);
        if (attempted.size() % 4 == 2) {
            Candidate midpoint = cliffMidpoint(available, attempted);
            if (midpoint != null) return midpoint;
        }
        if (attempted.size() % 2 == 0) {
            Candidate refinement = refineRegions(available, attempted);
            if (refinement != null) return refinement;
        }
        if (observations.isEmpty()) return efficientInterior(available);
        if (observations.size() == 1) return farthest(available, observations);
        double scale = observations.stream().mapToDouble(row -> Math.abs(row.score())).max().orElse(1);
        if (scale == 0) scale = 1;
        final double scoreScale = scale;
        double[][] covariance = new double[observations.size()][observations.size()];
        double[] y = new double[observations.size()];
        for (int i = 0; i < observations.size(); i++) {
            y[i] = observations.get(i).score() / scale;
            for (int j = 0; j < observations.size(); j++)
                covariance[i][j] = kernel(observations.get(i).candidate(), observations.get(j).candidate())
                        + (i == j ? NOISE : 0);
        }
        double[] alpha = solve(covariance, y);
        double best = observations.stream().mapToDouble(row -> row.score() / scoreScale).max().orElse(0);
        Candidate choice = null; double bestEi = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : available) {
            double[] k = new double[observations.size()];
            double mean = 0;
            for (int i = 0; i < k.length; i++) { k[i] = kernel(candidate, observations.get(i).candidate()); mean += k[i] * alpha[i]; }
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

    /**
     * Seed around the lower interior of each tunable dimension.  The first live reading is
     * therefore useful for efficiency tuning while still leaving enough range for the next
     * exploration point to detect a performance cliff.
     */
    private static Candidate efficientInterior(List<Candidate> candidates) {
        return candidates.stream().min(java.util.Comparator.comparingDouble(candidate ->
                squaredDistance(candidate.vector(), new double[]{.35, .35, .5}))).orElse(null);
    }

    /** Retain up to three spatially distinct good regions, not three adjacent winners. */
    static List<Observation> regions(List<Observation> attempted) {
        List<Observation> regions = new ArrayList<>();
        attempted.stream().filter(Observation::stable)
                .sorted(java.util.Comparator.comparingDouble(Observation::score).reversed())
                .forEach(row -> {
                    if (regions.size() < 3 && regions.stream().noneMatch(other ->
                            sameMemoryControl(row.candidate(), other.candidate())
                            && distance(row.candidate().vector(), other.candidate().vector()) < .04))
                        regions.add(row);
                });
        return regions;
    }

    private static Candidate refineRegions(List<Candidate> candidates, List<Observation> attempted) {
        List<Observation> regions = regions(attempted);
        if (regions.isEmpty()) return null;
        int turn = Math.max(0, (attempted.size() - 4) / 2);
        Candidate center = regions.get(turn % regions.size()).candidate();
        double step = Math.max(.03, .35 / Math.pow(2, turn / regions.size()));
        return candidates.stream().filter(c -> sameMemoryControl(c, center))
                .filter(c -> distance(c.vector(), center.vector()) <= .36)
                .min(java.util.Comparator.comparingDouble(c ->
                        Math.abs(Math.sqrt(distance(c.vector(), center.vector())) - step)))
                .orElse(null);
    }

    /** Probe a cliff's midpoint without assuming monotonicity or discarding either half. */
    static Candidate cliffMidpoint(List<Candidate> candidates, List<Observation> attempted) {
        Candidate choice = null;
        double largestGap = 0;
        for (int i = 0; i < attempted.size(); i++) for (int j = i + 1; j < attempted.size(); j++) {
            Observation left = attempted.get(i), right = attempted.get(j);
            if (!left.stable() || !right.stable() || !sameMemoryControl(left.candidate(), right.candidate())
                    || Math.min(left.score(), right.score()) > .8 * Math.max(left.score(), right.score())) continue;
            double[] a = left.candidate().vector(), b = right.candidate().vector();
            int axis = -1, changes = 0;
            for (int k = 0; k < a.length; k++) if (Math.abs(a[k] - b[k]) > 1e-9) { axis = k; changes++; }
            if (changes != 1) continue;
            final int dimension = axis;
            double low = Math.min(a[axis], b[axis]), high = Math.max(a[axis], b[axis]);
            double gap = high - low;
            Candidate midpoint = candidates.stream().filter(c -> sameMemoryControl(c, left.candidate()))
                    .filter(c -> {
                        double[] v = c.vector();
                        if (v[dimension] <= low || v[dimension] >= high) return false;
                        for (int k = 0; k < v.length; k++)
                            if (k != dimension && Math.abs(v[k] - a[k]) > 1e-9) return false;
                        return true;
                    }).min(java.util.Comparator.comparingDouble(c -> Math.abs(c.vector()[dimension] - (low + high) / 2)))
                    .orElse(null);
            if (midpoint != null && gap > largestGap) { choice = midpoint; largestGap = gap; }
        }
        return choice;
    }

    private static boolean sameMemoryControl(Candidate a, Candidate b) {
        return (a.memoryOffsetMhz() != null) == (b.memoryOffsetMhz() != null)
                && java.util.Objects.equals(a.memoryOffsetPstate(), b.memoryOffsetPstate());
    }

    private static double kernel(Candidate a, Candidate b) {
        return sameMemoryControl(a, b)
                ? Math.exp(-0.5 * distance(a.vector(), b.vector()) / (LENGTH_SCALE * LENGTH_SCALE)) : 0;
    }
    private static double distance(double[] a, double[] b) { return squaredDistance(a, b); }
    private static double squaredDistance(double[] a, double[] b) { double sum = 0; for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; sum += d * d; } return sum; }
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
                     double corePosition, double memoryPosition, double powerPosition,
                     Integer memoryOffsetMhz, Integer memoryOffsetPstate) {
        Candidate(Integer coreMhz, Integer memoryMhz, int powerWatts,
                  double corePosition, double memoryPosition, double powerPosition) {
            this(coreMhz, memoryMhz, powerWatts, corePosition, memoryPosition, powerPosition, null, null);
        }
        double[] vector() { return new double[]{corePosition, memoryPosition, powerPosition}; }
    }
    record Observation(Candidate candidate, double score, boolean stable, String detail,
                       AutoTuningSessionService.SampleSummary sample) {
        Observation(Candidate candidate, double score, boolean stable, String detail) {
            this(candidate, score, stable, detail, null);
        }
    }
}
