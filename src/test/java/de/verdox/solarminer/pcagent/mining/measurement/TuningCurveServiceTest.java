package de.verdox.solarminer.pcagent.mining.measurement;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TuningCurveServiceTest {
    private final AutoTuningProfileStore store = mock(AutoTuningProfileStore.class);
    private final TuningCurveService curves = new TuningCurveService(store);
    private final Instant start = Instant.parse("2026-10-21T12:00:00Z");
    private AutoTuningProfileStore.Measurement row(String run, String device, String miner, int cap, String type,
                                                  int second, Double rate, Double watts, Integer core, Boolean stable) {
        return new AutoTuningProfileStore.Measurement(run, start.plusSeconds(second), device, "GPU", "NVIDIA", "pearl", "PearlHash",
                miner, "test-version", 1200, null, cap, type, rate, watts, 60d, core, 6800, 16, null, null, stable, null, -100, 2);
    }
    @Test void aggregatesOnlyActiveSamplesAndNeverLabelsSearchOrPartialWindowsConfirmed() {
        when(store.measurements()).thenReturn(List.of(
                row("run", "gpu", "srb", 100, "SAMPLE", 0, 100d, 80d, 1100, null),
                row("run", "gpu", "srb", 100, "SEARCH_RESULT", 1, 110d, 85d, 1150, true),
                row("run", "gpu", "srb", 100, "SAMPLE", 2, 200d, 90d, 1200, null),
                row("run", "gpu", "srb", 100, "SAMPLE", 3, 220d, 100d, 1300, null),
                row("run", "gpu", "srb", 100, "SAMPLE", 4, null, 0d, null, null)));
        var points = curves.points("gpu");
        assertEquals(2, points.size());
        assertEquals("SEARCH_RESULT", points.getFirst().stage());
        var partial = points.getLast();
        assertEquals("SAMPLE", partial.stage());
        assertNull(partial.stable());
        assertEquals(210d, partial.hashrateHs());
        assertEquals(95d, partial.measuredWatts());
        assertEquals(1250, partial.coreMhz());
        assertEquals(1200, partial.targetCoreMhz());
        assertNull(partial.targetMemoryMhz());
        assertEquals(-100, partial.memoryOffsetMhz());
        assertEquals(2, partial.memoryOffsetPstate());
        assertEquals(2, partial.samples());
    }
    @Test void latestRunIsChosenIndependentlyPerSoftwareAndCannotLeakAnotherGpu() {
        when(store.measurements()).thenReturn(List.of(
                row("old", "gpu", "srb", 100, "RESULT", 1, 9999d, 80d, 1200, true),
                row("peak-run", "gpu", "peak", 150, "RESULT", 2, 1000d, 140d, 1200, true),
                row("new", "gpu", "srb", 100, "SEARCH_RESULT", 3, 500d, 90d, 1200, true),
                row("other", "another-gpu", "srb", 100, "RESULT", 4, 9999d, 80d, 1200, true)));
        var points = curves.points("gpu");
        assertEquals(2, points.size());
        assertEquals(Set.of("new", "peak-run"), points.stream().map(TuningCurveService.Point::runId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(points.stream().noneMatch(point -> point.hashrateHs() == 9999d));
    }
    @Test void resultClosesSampleWindowAndFailedOutcomeDoesNotLeaveAProvisionalSuccess() {
        when(store.measurements()).thenReturn(List.of(
                row("run", "gpu", "srb", 100, "SAMPLE", 0, 100d, 90d, 1200, null),
                row("run", "gpu", "srb", 100, "RESULT", 1, 100d, 90d, 1200, true),
                row("run", "gpu", "srb", 150, "SAMPLE", 2, 999d, 140d, 1200, null),
                row("run", "gpu", "srb", 150, "SEARCH_RESULT", 3, null, null, null, false)));
        var points = curves.points("gpu");
        assertEquals(1, points.size());
        assertEquals("RESULT", points.getFirst().stage());
        assertTrue(points.getFirst().stable());
    }
    @Test void activeWindowPayloadIsBoundedAndMissingReadbackIsNotReplacedByTarget() {
        var rows = new ArrayList<AutoTuningProfileStore.Measurement>();
        for (int i = 0; i < 100; i++) rows.add(row("run", "gpu", "srb", 100, "SAMPLE", i, 100d, 90d, null, null));
        when(store.measurements()).thenReturn(rows);
        var point = curves.points("gpu").getFirst();
        assertEquals(32, point.samples());
        assertNull(point.coreMhz());
        assertEquals(1200, point.targetCoreMhz());
        assertTrue(curves.points("missing").isEmpty());
    }
    @Test void unsafePartialTelemetryIsShownAsUnstableAndDoesNotCountAsValidEvidence() {
        var invalid = new AutoTuningProfileStore.Measurement("run", start, "gpu", "GPU", "NVIDIA", "pearl", "PearlHash",
                "srb", "test-version", 1200, null, 100, "SAMPLE", 100d, 90d, 90d, 1100, 6800, 1,
                null, null, null, "auto_tuning_temperature_limit", -100, 2);
        when(store.measurements()).thenReturn(List.of(invalid));
        var point = curves.points("gpu").getFirst();
        assertEquals(Boolean.FALSE, point.stable());
        assertEquals(0, point.samples());
        assertEquals(100d, point.hashrateHs());
    }

}
