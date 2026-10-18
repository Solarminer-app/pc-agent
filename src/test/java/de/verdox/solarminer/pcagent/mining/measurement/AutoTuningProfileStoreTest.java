package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
    }

    private static AutoTuningProfileStore.Profile profile(int watts) {
        return new AutoTuningProfileStore.Profile("GPU-a", "RTX", "NVIDIA", "pearl", "PearlHash",
                "srbminer-multi", "3.7.3", 1200, 800, watts, 1000, watts - 10, 8,
                1000d / (watts - 10), Instant.parse("2026-10-18T00:00:00Z"));
    }
}
