package de.verdox.solarminer.pcagent.mining.measurement;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkPresetTest {
    @Test void slowKeepsExhaustiveCoverageAndOldClientsKeepSlow() {
        var full = EfficiencySweepService.candidateLimits(100, 300, 15);
        assertSame(full, BenchmarkPreset.SLOW.limits(full));
        assertEquals(BenchmarkPreset.SLOW, BenchmarkPreset.parse(null));
        assertEquals(0, BenchmarkPreset.SLOW.seconds());
        assertEquals(16, BenchmarkPreset.SLOW.searchSamples());
        assertEquals(24, BenchmarkPreset.SLOW.searchCandidates());
        assertEquals(9, BenchmarkPreset.SLOW.confirmationCandidates());
    }
    @Test void fasterPresetsKeepRangeEndpointsAndSpendLessOnSearch() {
        var full = EfficiencySweepService.candidateLimits(100, 300, 15);
        for (var preset : List.of(BenchmarkPreset.QUICK, BenchmarkPreset.MEDIUM)) {
            var reduced = preset.limits(full);
            assertEquals(preset == BenchmarkPreset.QUICK ? 3 : 6, reduced.size());
            assertEquals(full.getFirst(), reduced.getFirst()); assertEquals(full.getLast(), reduced.getLast());
            assertTrue(full.containsAll(reduced));
            assertTrue(preset.searchCandidates() < BenchmarkPreset.SLOW.searchCandidates());
        }
        assertTrue(BenchmarkPreset.QUICK.seconds() < 20 * 60);
        assertTrue(BenchmarkPreset.MEDIUM.seconds() >= 30 * 60 && BenchmarkPreset.MEDIUM.seconds() < 60 * 60);
        assertEquals(List.of(110, 100), BenchmarkPreset.QUICK.limits(List.of(110, 100)));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPreset.parse("turbo"));
    }
}
