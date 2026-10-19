package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoTuningProfileStoreTest {
    @TempDir Path directory;

    @Test
    void persistsThreeTiersAndRejectsStaleMinerOrHardware() {
        Path file = directory.resolve("matrix.json");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AutoTuningProfileStore store = new AutoTuningProfileStore(json, file.toString());
        List<AutoTuningProfileStore.Profile> matrix = List.of(profile(100), profile(150), profile(200));
        assertTrue(store.saveMatrix(matrix));

        AutoTuningProfileStore reloaded = new AutoTuningProfileStore(json, file.toString());
        assertEquals(3, reloaded.matrix("GPU-a", "pearl", "srbminer-multi", "3.7.3", "RTX", "NVIDIA").size());
        assertEquals(150, reloaded.forBudget("GPU-a", "pearl", "srbminer-multi", "3.7.3", "RTX", "NVIDIA", 175).powerLimitWatts());
        assertNull(reloaded.forBudget("GPU-a", "pearl", "srbminer-multi", "3.7.3", "RTX", "NVIDIA", 90));
        assertTrue(reloaded.matrix("GPU-a", "pearl", "srbminer-multi", "3.7.4", "RTX", "NVIDIA").isEmpty());
        assertTrue(reloaded.matrix("GPU-a", "pearl", "srbminer-multi", "3.7.3", "Other", "NVIDIA").isEmpty());
        assertTrue(reloaded.matrix("GPU-a", "pearl", "srbminer-multi", "3.7.3", "RTX", "NVIDIA", "new-algorithm").isEmpty());
        assertFalse(reloaded.saveMatrix(List.of(profile(100), profile(100), profile(200))));
        assertTrue(reloaded.appendMeasurement(measurement("new-run", "RESULT", 100, 1300, true, null)));
        AutoTuningProfileStore migrated = new AutoTuningProfileStore(json, file.toString());
        assertEquals(4, migrated.candidates("GPU-a", "pearl", "srbminer-multi", "3.7.3",
                "RTX", "NVIDIA", "PearlHash").size());
    }

    @Test
    void retainsEveryAttemptAndReusesStableCandidatesAcrossRuns() throws Exception {
        Path file = directory.resolve("history.json");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AutoTuningProfileStore store = new AutoTuningProfileStore(json, file.toString());
        assertTrue(store.appendMeasurement(measurement("run-1", "SAMPLE", 100, 1200, null, null)));
        assertTrue(store.appendMeasurement(measurement("run-1", "RESULT", 100, 1200, true, null)));
        assertTrue(store.appendMeasurement(measurement("run-1", "RESULT", 150, 1200, true, null)));
        assertTrue(store.appendMeasurement(measurement("run-1", "RESULT", 200, 1200, false,
                "auto_tuning_rejected_share_limit")));
        assertTrue(store.appendMeasurement(measurement("run-2", "RESULT", 100, 1300, true, null)));
        assertTrue(store.appendMeasurement(measurement("run-2", "RESULT", 200, 1200, true, null)));
        Files.writeString(file.resolveSibling("history.json.measurements.jsonl"), "incomplete",
                StandardOpenOption.APPEND);
        assertTrue(store.appendMeasurement(measurement("run-3", "SAMPLE", 150, 1200, null, null)));

        AutoTuningProfileStore reloaded = new AutoTuningProfileStore(json, file.toString());
        assertEquals(7, reloaded.measurements().size());
        assertEquals(4, reloaded.candidates("GPU-a", "pearl", "srbminer-multi", "3.7.3",
                "RTX", "NVIDIA", "PearlHash").size());
        assertEquals(3, reloaded.matrix("GPU-a", "pearl", "srbminer-multi", "3.7.3",
                "RTX", "NVIDIA", "PearlHash").size());
        assertEquals(1300, reloaded.matrix("GPU-a", "pearl", "srbminer-multi", "3.7.3",
                "RTX", "NVIDIA", "PearlHash").getFirst().coreMhz());
        assertTrue(reloaded.candidates("GPU-a", "pearl", "srbminer-multi", "3.7.4",
                "RTX", "NVIDIA", "PearlHash").isEmpty());
        assertTrue(reloaded.measurements().stream().anyMatch(row ->
                "auto_tuning_rejected_share_limit".equals(row.reason())));
    }

    @Test
    void repeatedMeasurementsUseMedianInsteadOfLuckyMaximum() {
        Path file = directory.resolve("repeat.json");
        AutoTuningProfileStore store = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(), file.toString());
        assertTrue(store.saveMatrix(List.of(profile(100), profile(150), profile(200))));
        assertTrue(store.appendMeasurement(measurement("run-2", "RESULT", 100, 1200, true, null, 5000d)));
        assertTrue(store.appendMeasurement(measurement("run-3", "RESULT", 100, 1200, true, null, 1100d)));

        AutoTuningProfileStore reloaded = new AutoTuningProfileStore(new ObjectMapper().findAndRegisterModules(), file.toString());
        var bestAt100 = reloaded.matrix("GPU-a", "pearl", "srbminer-multi", "3.7.3",
                "RTX", "NVIDIA", "PearlHash").getFirst();
        assertEquals(1100d, bestAt100.hashrateHs());
        assertEquals(24, bestAt100.stableSamples());
        assertEquals(5, reloaded.candidates("GPU-a", "pearl", "srbminer-multi", "3.7.3",
                "RTX", "NVIDIA", "PearlHash").size());
    }

    @Test
    void offsetMeasurementsSurviveReloadAndKeepDistinctProfiles() {
        Path file = directory.resolve("offset-history.json");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AutoTuningProfileStore store = new AutoTuningProfileStore(json, file.toString());
        for (int offset : List.of(-100, 0)) {
            var row = new AutoTuningProfileStore.Measurement("run-offset", Instant.parse("2026-10-19T12:00:00Z"),
                    "GPU-a", "RTX", "NVIDIA", "pearl", "PearlHash", "srbminer-multi", "3.7.3",
                    1200, null, 150, "RESULT", offset == -100 ? 1200d : 1000d, 140d, 60d,
                    1200, 6800 + offset, 16, null, null, true, null, offset, 2);
            assertTrue(store.appendMeasurement(row));
        }

        AutoTuningProfileStore loaded = new AutoTuningProfileStore(json, file.toString());
        assertEquals(2, loaded.measurements().size());
        assertEquals(2, loaded.candidates("GPU-a", "pearl", "srbminer-multi", "3.7.3",
                "RTX", "NVIDIA", "PearlHash").size());
        assertEquals(-100, loaded.candidates("GPU-a", "pearl", "srbminer-multi", "3.7.3",
                "RTX", "NVIDIA", "PearlHash").getFirst().memoryOffsetMhz());
    }

    private static AutoTuningProfileStore.Measurement measurement(String run, String type, int power,
                                                                  int core, Boolean stable, String reason) {
        return measurement(run, type, power, core, stable, reason, core == 1300 ? 1100d : 1000d);
    }

    private static AutoTuningProfileStore.Measurement measurement(String run, String type, int power,
                                                                  int core, Boolean stable, String reason,
                                                                  double hashrate) {
        return new AutoTuningProfileStore.Measurement(run, Instant.parse("2026-10-19T12:00:00Z"),
                "GPU-a", "RTX", "NVIDIA", "pearl", "PearlHash", "srbminer-multi", "3.7.3",
                core, 800, power, type, stable == null || stable ? hashrate : null,
                stable == null || stable ? power - 10d : null, 60d, core, 800,
                8, 5L, 0L, stable, reason);
    }

    private static AutoTuningProfileStore.Profile profile(int watts) {
        return new AutoTuningProfileStore.Profile("GPU-a", "RTX", "NVIDIA", "pearl", "PearlHash",
                "srbminer-multi", "3.7.3", 1200, 800, watts, 1000, watts - 10, 8,
                1000d / (watts - 10), Instant.parse("2026-10-18T00:00:00Z"));
    }
}
