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
}
