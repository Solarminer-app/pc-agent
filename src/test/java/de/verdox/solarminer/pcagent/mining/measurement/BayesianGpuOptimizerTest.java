package de.verdox.solarminer.pcagent.mining.measurement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BayesianGpuOptimizerTest {
    @Test
    void expectedImprovementSelectsOnlyUntestedCandidateAndExhaustsFiniteSearch() {
        var low = new BayesianGpuOptimizer.Candidate(900, 500, 100, 0, 0, 0);
        var middle = new BayesianGpuOptimizer.Candidate(1200, 800, 150, .5, .5, .5);
        var high = new BayesianGpuOptimizer.Candidate(1500, 1100, 200, 1, 1, 1);
        List<BayesianGpuOptimizer.Candidate> candidates = List.of(low, middle, high);
        var observations = new java.util.ArrayList<BayesianGpuOptimizer.Observation>();

        assertSame(low, BayesianGpuOptimizer.next(candidates, observations));
        observations.add(new BayesianGpuOptimizer.Observation(low, 1.0, true, null));
        assertNotSame(low, BayesianGpuOptimizer.next(candidates, observations));
        observations.add(new BayesianGpuOptimizer.Observation(middle, 1.4, true, null));
        var last = BayesianGpuOptimizer.next(candidates, observations);
        assertNotNull(last);
        assertNotEquals(low, last);
        assertNotEquals(middle, last);
        observations.add(new BayesianGpuOptimizer.Observation(last, 1.3, true, null));
        assertNull(BayesianGpuOptimizer.next(candidates, observations));
    }

    @Test
    void optimizerExploresAwayFromAnUnstableInitialPoint() {
        var a = new BayesianGpuOptimizer.Candidate(900, 500, 100, 0, 0, 0);
        var b = new BayesianGpuOptimizer.Candidate(1500, 1100, 200, 1, 1, 1);
        var observations = List.of(new BayesianGpuOptimizer.Observation(a, 0, false, "unstable"));
        assertEquals(b, BayesianGpuOptimizer.next(List.of(a, b), observations));
    }

    @Test
    void historicalPointInformsSelectionWithoutPreventingRemeasurement() {
        var candidate = new BayesianGpuOptimizer.Candidate(1400, 6800, 260, .7, .8, .5);
        var prior = new BayesianGpuOptimizer.Observation(candidate, 1_700_000, true, null);

        assertEquals(candidate, BayesianGpuOptimizer.next(List.of(candidate), List.of(prior), List.of()));
        assertNull(BayesianGpuOptimizer.next(List.of(candidate), List.of(prior), List.of(prior)));
    }

    @Test
    void expectedImprovementDoesNotDependOnHashrateScoreUnits() {
        var a = new BayesianGpuOptimizer.Candidate(800, 5000, 100, 0, 0, 0);
        var b = new BayesianGpuOptimizer.Candidate(1100, 6000, 100, .33, .33, 0);
        var c = new BayesianGpuOptimizer.Candidate(1400, 6800, 100, .67, .67, 0);
        var d = new BayesianGpuOptimizer.Candidate(1600, 7000, 100, 1, 1, 0);
        var lowScores = List.of(new BayesianGpuOptimizer.Observation(a, .5, true, null),
                new BayesianGpuOptimizer.Observation(c, 1.5, true, null));
        var highScores = List.of(new BayesianGpuOptimizer.Observation(a, 500_000, true, null),
                new BayesianGpuOptimizer.Observation(c, 1_500_000, true, null));

        assertEquals(BayesianGpuOptimizer.next(List.of(a, b, c, d), lowScores),
                BayesianGpuOptimizer.next(List.of(a, b, c, d), highScores));
    }
}
