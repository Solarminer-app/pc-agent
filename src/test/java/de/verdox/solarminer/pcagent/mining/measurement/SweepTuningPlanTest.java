package de.verdox.solarminer.pcagent.mining.measurement;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SweepTuningPlanTest {
    private GpuEfficiencyStore.StepResult step(int cap, double rate, double watts) {
        return new GpuEfficiencyStore.StepResult(cap, rate, watts, 60d, true, null);
    }
    private GpuEfficiencyStore.Profile curve(List<GpuEfficiencyStore.StepResult> steps) {
        return new GpuEfficiencyStore.Profile("gpu", "Test", "pearl", "PearlHash", 100,
                1000d, 90d, 1000d / 90, Instant.now(), steps, "srbminer-multi");
    }
    private BayesianGpuOptimizer.Observation observation(int core, int cap, double rate, double watts) {
        var candidate = new BayesianGpuOptimizer.Candidate(core, null, cap, .5, .5, cap / 200d, -100, 2);
        return new BayesianGpuOptimizer.Observation(candidate, rate / watts, true, null,
                new AutoTuningSessionService.SampleSummary(true, rate, watts, 16, 60d, core, 5000, null, false));
    }
    @Test void seedsEfficiencyThroughputAndIntermediateFrontierInsteadOfFixedHardwareExtremes() {
        var plan = SweepTuningPlan.from(curve(List.of(step(80, 100, 70), step(100, 1000, 90),
                step(130, 1200, 120), step(160, 1300, 150), step(200, 1400, 190))), 60, 250, 75);
        assertEquals(List.of(100, 200, 160), plan.anchors());
        assertEquals(80, plan.minimumWatts());
        assertEquals(200, plan.maximumWatts());
        assertEquals(10, plan.neighborStepWatts());
        assertFalse(plan.anchors().contains(60));
        assertFalse(plan.anchors().contains(250));
    }
    @Test void excludesUnstableOverheatedMissingAndOutOfRangeEvidence() {
        var plan = SweepTuningPlan.from(curve(List.of(step(100, 1000, 90), step(140, 1200, 130), step(180, 1300, 170),
                step(250, 9000, 240), new GpuEfficiencyStore.StepResult(160, 5000d, 150d, 76d, true, null),
                new GpuEfficiencyStore.StepResult(170, 7000d, 160d, null, true, null),
                new GpuEfficiencyStore.StepResult(150, 6000d, 140d, 60d, false, "unstable"))), 100, 180, 75);
        assertEquals(Set.of(100, 140, 180), new HashSet<>(plan.anchors()));
        assertEquals(180, plan.maximumWatts());
    }
    @Test void sparseCurveUsesOnlyInteriorFallbackAndNarrowOrAbsentEvidenceIsRejected() {
        var plan = SweepTuningPlan.from(curve(List.of(step(120, 1000, 110), step(160, 1200, 150))), 80, 200, 75);
        assertEquals(Set.of(120, 140, 160), new HashSet<>(plan.anchors()));
        assertThrows(IllegalStateException.class, () -> SweepTuningPlan.from(curve(List.of(step(120, 1000, 110))), 80, 200, 75));
        assertEquals("benchmark_optimization_no_safe_curve_points", assertThrows(IllegalStateException.class,
                () -> SweepTuningPlan.from(curve(List.of()), 80, 200, 75)).getMessage());
    }
    @Test void rechecksBothSidesOfEfficientFastAndIntermediateTunedPointsKeepingExactMemoryMode() {
        var plan = new SweepTuningPlan(List.of(100, 150, 200), 80, 220, 5);
        var rows = List.of(observation(1000, 100, 1000, 90), observation(1200, 150, 1200, 140), observation(1400, 200, 1400, 190));
        var supported = rows.stream().map(BayesianGpuOptimizer.Observation::candidate).toList();
        var neighbors = plan.neighbors(rows, supported, 60, 250);
        assertEquals(Set.of(95, 105, 145, 155, 195, 205), neighbors.stream().map(BayesianGpuOptimizer.Candidate::powerWatts).collect(java.util.stream.Collectors.toSet()));
        assertTrue(neighbors.stream().allMatch(row -> row.memoryMhz() == null && row.memoryOffsetMhz() == -100 && row.memoryOffsetPstate() == 2));
        assertTrue(neighbors.stream().allMatch(row -> Math.abs(row.powerPosition() - (row.powerWatts() - 60) / 190d) < 1e-9));
    }
    @Test void neighborsNeverExceedMeasuredEnvelopeOrRepeatAnAlreadyAttemptedSetting() {
        var plan = new SweepTuningPlan(List.of(100, 150, 200), 100, 200, 10);
        var rows = List.of(observation(1000, 100, 1000, 90), observation(1400, 200, 1400, 190), observation(1000, 110, 1100, 100));
        var neighbors = plan.neighbors(rows, rows.stream().map(BayesianGpuOptimizer.Observation::candidate).toList(), 80, 250);
        assertTrue(neighbors.stream().allMatch(row -> row.powerWatts() >= 100 && row.powerWatts() <= 200));
        assertFalse(neighbors.stream().anyMatch(row -> row.powerWatts() == 110 && row.coreMhz() == 1000));
        assertTrue(plan.neighbors(rows, List.of(), 80, 250).isEmpty());
    }
    @Test void confirmationsRetainAnchorCoverageAndNewGlobalWinnersWithinNineWindows() {
        var plan = new SweepTuningPlan(List.of(100, 150, 200), 80, 220, 5);
        List<BayesianGpuOptimizer.Observation> rows = new ArrayList<>(List.of(
                observation(1000, 100, 1000, 90), observation(1200, 150, 1200, 140), observation(1400, 200, 1400, 190),
                observation(1000, 95, 1100, 85), observation(1400, 205, 1600, 195)));
        var supported = rows.stream().map(BayesianGpuOptimizer.Observation::candidate).toList();
        var finalists = plan.finalists(rows, supported);
        assertTrue(finalists.size() <= 9);
        assertTrue(finalists.stream().map(BayesianGpuOptimizer.Candidate::powerWatts).toList().containsAll(List.of(100, 150, 200, 95, 205)));
        assertEquals(finalists.size(), new HashSet<>(finalists).size());
        assertTrue(plan.finalists(rows, List.of()).isEmpty());
    }
}
