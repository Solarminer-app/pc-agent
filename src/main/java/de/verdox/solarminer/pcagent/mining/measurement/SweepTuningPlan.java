package de.verdox.solarminer.pcagent.mining.measurement;

import java.util.*;

/** A bounded search seeded by a measured curve; baseline hash rates never train tuned-clock scores. */
record SweepTuningPlan(List<Integer> anchors, int minimumWatts, int maximumWatts, int neighborStepWatts) {
    static SweepTuningPlan from(GpuEfficiencyStore.Profile curve, int minimum, int maximum, double ceiling) {
        List<GpuEfficiencyStore.StepResult> safe = curve == null || curve.steps() == null ? List.of() : curve.steps().stream()
                .filter(row -> row.stable() && row.limitWatts() >= minimum && row.limitWatts() <= maximum
                        && positive(row.medianHashrateHs()) && positive(row.avgPowerWatts())
                        && positive(row.maxTemperatureC()) && row.maxTemperatureC() < ceiling)
                .sorted(Comparator.comparingInt(GpuEfficiencyStore.StepResult::limitWatts)).toList();
        if (safe.isEmpty()) throw new IllegalStateException("benchmark_optimization_no_safe_curve_points");
        int low = safe.getFirst().limitWatts(), high = safe.getLast().limitWatts();
        if (high - low < 2) throw new IllegalStateException("auto_tuning_power_range_too_narrow");
        var efficient = safe.stream().max(Comparator.comparingDouble(row -> row.medianHashrateHs() / row.avgPowerWatts())).orElseThrow();
        var fastest = safe.stream().max(Comparator.comparingDouble(GpuEfficiencyStore.StepResult::medianHashrateHs)
                .thenComparing(Comparator.comparingDouble(GpuEfficiencyStore.StepResult::avgPowerWatts).reversed())).orElseThrow();
        Set<Integer> anchors = new LinkedHashSet<>(List.of(efficient.limitWatts(), fastest.limitWatts()));
        // Keep an intermediate non-dominated point, then fill coverage from measured points.
        List<GpuEfficiencyStore.StepResult> frontier = safe.stream().filter(row -> safe.stream().noneMatch(other ->
                other.avgPowerWatts() <= row.avgPowerWatts() && other.medianHashrateHs() >= row.medianHashrateHs()
                        && (other.avgPowerWatts() < row.avgPowerWatts() || other.medianHashrateHs() > row.medianHashrateHs()))).toList();
        double middle = (efficient.limitWatts() + fastest.limitWatts()) / 2d;
        frontier.stream().filter(row -> !anchors.contains(row.limitWatts()))
                .min(Comparator.comparingDouble(row -> Math.abs(row.limitWatts() - middle)))
                .ifPresent(row -> anchors.add(row.limitWatts()));
        safe.stream().sorted(Comparator.comparingDouble(row -> Math.abs(row.limitWatts() - (low + high) / 2d)))
                .map(GpuEfficiencyStore.StepResult::limitWatts).forEach(watts -> { if (anchors.size() < 3) anchors.add(watts); });
        // Sparse curves may need a bounded interior probe to retain the three-tier confirmation contract.
        for (int watts : List.of(low, high, low + (high - low) / 2)) if (anchors.size() < 3) anchors.add(watts);
        List<Integer> gaps = new ArrayList<>();
        for (int index = 1; index < safe.size(); index++) {
            int gap = safe.get(index).limitWatts() - safe.get(index - 1).limitWatts();
            if (gap > 0) gaps.add(gap);
        }
        gaps.sort(Integer::compare);
        int step = Math.clamp(gaps.isEmpty() ? 1 : gaps.get(gaps.size() / 2) / 2, 1, 10);
        return new SweepTuningPlan(List.copyOf(anchors), low, high, step);
    }

    List<BayesianGpuOptimizer.Candidate> neighbors(List<BayesianGpuOptimizer.Observation> observations,
                                                  List<BayesianGpuOptimizer.Candidate> supported,
                                                  int normalizationMinimum, int normalizationMaximum) {
        var stable = stable(observations, supported);
        if (stable.isEmpty()) return List.of();
        Set<BayesianGpuOptimizer.Observation> centers = new LinkedHashSet<>();
        stable.stream().max(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score)).ifPresent(centers::add);
        stable.stream().max(Comparator.comparingDouble(row -> row.sample().hashrateHs())).ifPresent(centers::add);
        var frontier = frontier(stable);
        double low = stable.stream().mapToDouble(row -> row.sample().watts()).min().orElseThrow();
        double high = stable.stream().mapToDouble(row -> row.sample().watts()).max().orElseThrow();
        frontier.stream().filter(row -> !centers.contains(row))
                .min(Comparator.comparingDouble(row -> Math.abs(row.sample().watts() - (low + high) / 2)))
                .ifPresent(centers::add);
        Set<BayesianGpuOptimizer.Candidate> points = new LinkedHashSet<>();
        for (var center : centers) for (int direction : List.of(-1, 1)) {
            var old = center.candidate();
            int watts = old.powerWatts() + direction * neighborStepWatts;
            if (watts < minimumWatts || watts > maximumWatts) continue;
            var next = new BayesianGpuOptimizer.Candidate(old.coreMhz(), old.memoryMhz(), watts,
                    old.corePosition(), old.memoryPosition(), (watts - normalizationMinimum) / (double) (normalizationMaximum - normalizationMinimum),
                    old.memoryOffsetMhz(), old.memoryOffsetPstate());
            if (observations.stream().noneMatch(row -> sameSettings(row.candidate(), next))) points.add(next);
        }
        return List.copyOf(points);
    }

    List<BayesianGpuOptimizer.Candidate> finalists(List<BayesianGpuOptimizer.Observation> observations,
                                                   List<BayesianGpuOptimizer.Candidate> supported) {
        var stable = stable(observations, supported);
        Set<BayesianGpuOptimizer.Candidate> finalists = new LinkedHashSet<>();
        for (int watts : anchors) stable.stream().filter(row -> row.candidate().powerWatts() == watts)
                .max(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score)).map(BayesianGpuOptimizer.Observation::candidate)
                .ifPresent(finalists::add);
        stable.stream().max(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score))
                .map(BayesianGpuOptimizer.Observation::candidate).ifPresent(finalists::add);
        stable.stream().max(Comparator.comparingDouble(row -> row.sample().hashrateHs()))
                .map(BayesianGpuOptimizer.Observation::candidate).ifPresent(finalists::add);
        for (int watts : anchors) stable.stream().filter(row -> row.candidate().powerWatts() == watts)
                .max(Comparator.comparingDouble(row -> row.sample().hashrateHs())).map(BayesianGpuOptimizer.Observation::candidate)
                .ifPresent(finalists::add);
        // Remaining windows confirm the measured trade-offs, including newly probed power limits.
        frontier(stable).stream().sorted(Comparator.comparingInt(row -> row.candidate().powerWatts()))
                .map(BayesianGpuOptimizer.Observation::candidate).forEach(row -> { if (finalists.size() < 9) finalists.add(row); });
        stable.stream().sorted(Comparator.comparingDouble(BayesianGpuOptimizer.Observation::score).reversed())
                .map(BayesianGpuOptimizer.Observation::candidate).forEach(row -> { if (finalists.size() < 9) finalists.add(row); });
        return List.copyOf(finalists);
    }

    private static List<BayesianGpuOptimizer.Observation> stable(List<BayesianGpuOptimizer.Observation> observations,
                                                                List<BayesianGpuOptimizer.Candidate> supported) {
        return observations.stream().filter(row -> row.stable() && supported.contains(row.candidate())
                && row.sample() != null && row.sample().stable() && positive(row.sample().hashrateHs())
                && positive(row.sample().watts())).toList();
    }
    private static List<BayesianGpuOptimizer.Observation> frontier(List<BayesianGpuOptimizer.Observation> rows) {
        return rows.stream().filter(row -> rows.stream().noneMatch(other ->
                other.sample().watts() <= row.sample().watts() && other.sample().hashrateHs() >= row.sample().hashrateHs()
                        && (other.sample().watts() < row.sample().watts() || other.sample().hashrateHs() > row.sample().hashrateHs()))).toList();
    }
    private static boolean sameSettings(BayesianGpuOptimizer.Candidate a, BayesianGpuOptimizer.Candidate b) {
        return a.powerWatts() == b.powerWatts() && Objects.equals(a.coreMhz(), b.coreMhz())
                && Objects.equals(a.memoryMhz(), b.memoryMhz()) && Objects.equals(a.memoryOffsetMhz(), b.memoryOffsetMhz())
                && Objects.equals(a.memoryOffsetPstate(), b.memoryOffsetPstate());
    }
    private static boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }
}
