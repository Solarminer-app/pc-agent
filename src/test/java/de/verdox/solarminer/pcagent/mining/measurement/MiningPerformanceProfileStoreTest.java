package de.verdox.solarminer.pcagent.mining.measurement;

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
                31_000_000d, 180, 12, Instant.parse("2026-10-09T10:00:00Z"), "BENCHMARK", 200);
        store.save(saved);
        var otherTarget = new MiningPerformanceProfileStore.Profile("GPU-1", "GPU", "Example", "ravencoin", "kawpow",
                29_000_000d, 150, 12, Instant.parse("2026-10-10T10:00:00Z"), "LIVE", 160);
        store.save(otherTarget);

        var restarted = new MiningPerformanceProfileStore(mapper, file.toString());
        var restored = restarted.find("GPU-1", "ravencoin", "KAWPOW");
        assertNotNull(restored);
        assertEquals(otherTarget, restored);
        assertEquals(2, restarted.all().size());
    }
}
