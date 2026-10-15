package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GpuEfficiencyStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void failedAttemptRemainsVisibleWithoutReplacingStableProfile() {
        var store = new GpuEfficiencyStore(new ObjectMapper(), tempDir.resolve("profiles.json").toString());
        var stable = new GpuEfficiencyStore.Profile("GPU-1", "RTX 2080", "quantus", "QPoW",
                180, 100_000d, 175d, 571.4d, Instant.parse("2026-10-14T10:00:00Z"), List.of());
        var failed = new GpuEfficiencyStore.Profile("GPU-1", "RTX 2080", "quantus", "QPoW",
                null, null, null, null, Instant.parse("2026-10-15T10:00:00Z"),
                List.of(new GpuEfficiencyStore.StepResult(160, null, null, null, false,
                        "Treiber hat die Leistungsgrenze nicht bestätigt")));

        store.save(stable);
        store.save(failed);

        assertEquals(stable, store.find("GPU-1", "QPoW"));
        assertEquals(2, store.all().size());
        assertNull(store.all().stream().filter(profile -> profile.testedAt().equals(failed.testedAt()))
                .findFirst().orElseThrow().bestStableWatts());
    }
}
