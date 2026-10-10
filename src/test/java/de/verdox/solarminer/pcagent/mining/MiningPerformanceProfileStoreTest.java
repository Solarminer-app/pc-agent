package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MiningPerformanceProfileStoreTest {
    @TempDir Path directory;

    @Test void benchmarkProfileSurvivesRestartAndIsKeyedByWorkerCoinAndAlgorithm() {
        Path file = directory.resolve("profiles.json");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new MiningPerformanceProfileStore(mapper, file.toString());
        var saved = new MiningPerformanceProfileStore.Profile("GPU-1", "GPU", "Example", "ravencoin", "kawpow",
                31_000_000d, 180, 12, Instant.parse("2026-10-09T10:00:00Z"), "BENCHMARK");
        store.save(saved);

        var restored = new MiningPerformanceProfileStore(mapper, file.toString())
                .find("GPU-1", "ravencoin", "KAWPOW");
        assertNotNull(restored);
        assertEquals(saved, restored);
    }
}
