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

        var first = BayesianGpuOptimizer.next(candidates, observations);
        assertSame(middle, first);
        observations.add(new BayesianGpuOptimizer.Observation(first, 1.0, true, null));
        var second = BayesianGpuOptimizer.next(candidates, observations);
        assertNotNull(second);
        assertNotEquals(first, second);
        observations.add(new BayesianGpuOptimizer.Observation(second, 1.4, true, null));
        var last = BayesianGpuOptimizer.next(candidates, observations);
        assertNotNull(last);
        assertNotEquals(first, last);
        assertNotEquals(second, last);
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

    @Test
    void startsAtEfficientInteriorInsteadOfDriverOrderedExtreme() {
        var high = new BayesianGpuOptimizer.Candidate(2100, 7000, 180, 1, 1, .5);
        var low = new BayesianGpuOptimizer.Candidate(300, 405, 180, 0, 0, .5);
        var efficientInterior = new BayesianGpuOptimizer.Candidate(900, 810, 180, .35, .35, .5);

        assertEquals(efficientInterior, BayesianGpuOptimizer.next(List.of(high, low, efficientInterior), List.of(), List.of()));
    }

    @Test
    void continuesBroadCoverageRatherThanCommittingAfterTwoReadings() {
        var low = new BayesianGpuOptimizer.Candidate(840, 810, 100, .3, .1, 0);
        var distant = new BayesianGpuOptimizer.Candidate(2100, 7000, 100, 1, 1, 0);
        var nearby = new BayesianGpuOptimizer.Candidate(1050, 810, 100, .42, .1, 0);
        var farMemory = new BayesianGpuOptimizer.Candidate(840, 5000, 100, .3, .7, 0);
        var attempted = List.of(
                new BayesianGpuOptimizer.Observation(low, .36, true, null),
                new BayesianGpuOptimizer.Observation(distant, .13, true, null));

        assertEquals(farMemory, BayesianGpuOptimizer.next(List.of(low, distant, nearby, farMemory), attempted, attempted));
    }
    @Test
    void periodicExplorationEscapesAnAlreadySampledNeighbourhood() {
        var a = new BayesianGpuOptimizer.Candidate(900, 5000, 100, .3, .3, 0);
        var b = new BayesianGpuOptimizer.Candidate(950, 5000, 100, .35, .3, 0);
        var c = new BayesianGpuOptimizer.Candidate(1000, 5000, 100, .4, .3, 0);
        var near = new BayesianGpuOptimizer.Candidate(1050, 5000, 100, .45, .3, 0);
        var far = new BayesianGpuOptimizer.Candidate(1600, 7000, 100, 1, 1, 0);
        var readings = List.of(new BayesianGpuOptimizer.Observation(a, 10, true, null),
                new BayesianGpuOptimizer.Observation(b, 11, true, null),
                new BayesianGpuOptimizer.Observation(c, 12, true, null));
        assertEquals(far, BayesianGpuOptimizer.next(List.of(a, b, c, near, far), readings));
    }

    @Test
    void retainsDistinctPromisingRegionsInsteadOfThreeAdjacentWinners() {
        var a = new BayesianGpuOptimizer.Candidate(900, 5000, 100, .1, .1, 0);
        var adjacent = new BayesianGpuOptimizer.Candidate(950, 5000, 100, .12, .1, 0);
        var b = new BayesianGpuOptimizer.Candidate(1400, 5000, 100, .7, .1, 0);
        var c = new BayesianGpuOptimizer.Candidate(1600, 7000, 100, 1, 1, 0);
        var regions = BayesianGpuOptimizer.regions(List.of(
                new BayesianGpuOptimizer.Observation(a, 10, true, null),
                new BayesianGpuOptimizer.Observation(adjacent, 9.9, true, null),
                new BayesianGpuOptimizer.Observation(b, 9, true, null),
                new BayesianGpuOptimizer.Observation(c, 8, true, null)));
        assertEquals(List.of(a, b, c), regions.stream().map(BayesianGpuOptimizer.Observation::candidate).toList());
    }

    @Test
    void probesMemoryCliffAtMidpointOnlyWhenOtherDimensionsAreFixed() {
        var low = new BayesianGpuOptimizer.Candidate(900, 5000, 100, .3, 0, 0);
        var high = new BayesianGpuOptimizer.Candidate(900, 7000, 100, .3, 1, 0);
        var middle = new BayesianGpuOptimizer.Candidate(900, 6000, 100, .3, .5, 0);
        var confounded = new BayesianGpuOptimizer.Candidate(1200, 6000, 100, .7, .5, 0);
        var readings = List.of(new BayesianGpuOptimizer.Observation(low, 10, true, null),
                new BayesianGpuOptimizer.Observation(high, 20, true, null));
        assertEquals(middle, BayesianGpuOptimizer.cliffMidpoint(List.of(confounded, middle), readings));
        assertNull(BayesianGpuOptimizer.cliffMidpoint(List.of(confounded), readings));
    }

    @Test
    void expectedImprovementWithFiveReadingsIsIndependentOfScoreUnits() {
        var candidates = java.util.stream.IntStream.rangeClosed(0, 10)
                .mapToObj(i -> new BayesianGpuOptimizer.Candidate(500 + i * 100, 5000, 100, i / 10d, 0, 0)).toList();
        var small = java.util.stream.IntStream.range(0, 5).mapToObj(i ->
                new BayesianGpuOptimizer.Observation(candidates.get(i * 2), .1 + .02 * i, true, null)).toList();
        var large = small.stream().map(o -> new BayesianGpuOptimizer.Observation(o.candidate(), o.score() * 1e6, true, null)).toList();
        assertEquals(BayesianGpuOptimizer.next(candidates, small), BayesianGpuOptimizer.next(candidates, large));
    }

}
