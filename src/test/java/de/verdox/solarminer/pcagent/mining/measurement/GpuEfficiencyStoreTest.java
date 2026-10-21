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
        var store = new GpuEfficiencyStore(new ObjectMapper().findAndRegisterModules(),
                tempDir.resolve("profiles.json").toString());
        var stable = new GpuEfficiencyStore.Profile("GPU-1", "RTX 2080", "quantus", "QPoW (Poseidon2)",
                180, 100_000d, 175d, 571.4d, Instant.parse("2026-10-14T10:00:00Z"), List.of());
        var failed = new GpuEfficiencyStore.Profile("GPU-1", "RTX 2080", "quantus", "QPoW (Poseidon2)",
                null, null, null, null, Instant.parse("2026-10-15T10:00:00Z"),
                List.of(new GpuEfficiencyStore.StepResult(160, null, null, null, false,
                        "Treiber hat die Leistungsgrenze nicht bestätigt")));

        store.save(stable);
        store.save(failed);

        assertEquals(stable, store.find("GPU-1", "QPoW (Poseidon2)"));
        assertEquals(stable, store.find("GPU-1", "quantus"));
        assertEquals(2, store.all().size());
        assertNull(store.all().stream().filter(profile -> profile.testedAt().equals(failed.testedAt()))
                .findFirst().orElseThrow().bestStableWatts());
    }

    @Test
    void sameCoinSweepKeepsBothMinerProfiles() {
        var store = new GpuEfficiencyStore(new ObjectMapper().findAndRegisterModules(),
                tempDir.resolve("miners.json").toString());
        Instant at = Instant.parse("2026-10-17T10:00:00Z");
        store.save(new GpuEfficiencyStore.Profile("GPU-1", "RTX", "quantus", "quantus",
                180, 100d, 150d, 0.67d, at, List.of(), "srbminer-multi"));
        store.save(new GpuEfficiencyStore.Profile("GPU-1", "RTX", "quantus", "quantus",
                180, 106d, 150d, 0.71d, at, List.of(), "quanpool-miner"));
        assertEquals(2, store.all().size());
        assertEquals("quanpool-miner", store.find("GPU-1", "quantus", "quanpool-miner").minerSoftwareId());
    }
    @Test
    void observedClocksSurvivePersistenceAndLegacyRowsKeepUnknownClocks() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var path = tempDir.resolve("clocks.json");
        var store = new GpuEfficiencyStore(mapper, path.toString());
        var step = new GpuEfficiencyStore.StepResult(100, 1000d, 90d, 60d, true, null, 980, 6800);
        store.save(new GpuEfficiencyStore.Profile("gpu", "RTX", "pearl", "PearlHash", 100,
                1000d, 90d, 1000d / 90, Instant.now(), List.of(step), "srbminer-multi"));
        var loaded = new GpuEfficiencyStore(mapper, path.toString()).all().getFirst().steps().getFirst();
        assertEquals(980, loaded.observedCoreMhz());
        assertEquals(6800, loaded.observedMemoryMhz());
        var old = mapper.readValue("{\"limitWatts\":100,\"medianHashrateHs\":1000,\"avgPowerWatts\":90,\"maxTemperatureC\":60,\"stable\":true}", GpuEfficiencyStore.StepResult.class);
        assertNull(old.observedCoreMhz());
        assertNull(old.observedMemoryMhz());
    }

}
