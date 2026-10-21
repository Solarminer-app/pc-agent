package de.verdox.solarminer.pcagent.mining.economics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class OperatingPointSafetyStoreTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    CoinHoppingService.Candidate point(int watts, Instant measuredAt) {
        return new CoinHoppingService.Candidate("pearl", watts, watts - 5, 1000, 1,
                measuredAt, Instant.now(), "SWEEP", 1, 1, "peakminer");
    }
    @Test void thermalFailureSurvivesRestartAndOnlyFreshEvidenceReenablesTheExactPoint() {
        var file = directory.resolve("safety.json");
        var store = new OperatingPointSafetyStore(json, file.toString());
        var failed = point(280, Instant.now().minusSeconds(60));
        store.reject("gpu-2", failed.pointKey());
        var restarted = new OperatingPointSafetyStore(json, file.toString());
        assertTrue(restarted.available());
        assertFalse(restarted.permits("gpu-2", failed));
        assertTrue(restarted.permits("gpu-0", failed), "A sibling GPU is independent");
        assertTrue(restarted.permits("gpu-2", point(205, failed.measuredAt())));
        assertTrue(restarted.permits("gpu-2", point(280, Instant.now().plusSeconds(1))));
    }
    @Test void unreadableHistoryFailsClosedAndIsNotOverwritten() throws Exception {
        var file = directory.resolve("bad.json");
        Files.writeString(file, "broken");
        var store = new OperatingPointSafetyStore(json, file.toString());
        assertFalse(store.available());
        assertFalse(store.permits("gpu", point(150, Instant.now())));
        assertEquals("power_mode_point_safety_unavailable",
                assertThrows(IllegalStateException.class, () -> store.reject("gpu", "point")).getMessage());
        assertEquals("broken", Files.readString(file));
    }
    @Test void failedPersistenceKeepsAutomaticSelectionBlocked() throws Exception {
        var parent = directory.resolve("blocked");
        var store = new OperatingPointSafetyStore(json, parent.resolve("safety.json").toString());
        Files.writeString(parent, "not a directory");
        var point = point(280, Instant.now().minusSeconds(1));
        assertThrows(IllegalStateException.class, () -> store.reject("gpu", point.pointKey()));
        assertFalse(store.available());
        assertFalse(store.permits("gpu", point));
    }

    @Test void raisedLimitReleasesOnlyHeatBelowTheNewLimitAndLoweringRestoresQuarantine() {
        var file = directory.resolve("limits.json");
        var store = new OperatingPointSafetyStore(json, file.toString());
        var point = point(200, Instant.now().minusSeconds(60));
        store.reject("gpu", point.pointKey(), 84d, 84d);
        var loaded = new OperatingPointSafetyStore(json, file.toString());
        assertFalse(loaded.permits("gpu", point, 84));
        assertTrue(loaded.permits("gpu", point, 86));
        assertFalse(loaded.permits("gpu", point, 82));
        loaded.reject("gpu", point.pointKey(), 87d, 84d);
        assertFalse(loaded.permits("gpu", point, 86), "An overshoot above the new ceiling is still unsafe");
        assertTrue(loaded.permits("gpu", point, 88));
    }

    @Test void legacyTimestampQuarantineMigratesOnlyOnExplicitPolicyIncrease() throws Exception {
        var file = directory.resolve("legacy.json");
        var point = point(160, Instant.now().minusSeconds(60));
        Files.writeString(file, json.writeValueAsString(java.util.Map.of("gpu|" + point.pointKey(), Instant.now())));
        var store = new OperatingPointSafetyStore(json, file.toString());
        assertTrue(store.available());
        assertFalse(store.permits("gpu", point, 86));
        var policies = new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService(json,
                directory.resolve("policy.json").toString());
        policies.setPointSafety(store);
        policies.save("gpu", new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService.Policy(java.util.List.of(), false, false, 84));
        assertFalse(store.permits("gpu", point, 86));
        policies.save("gpu", new de.verdox.solarminer.pcagent.mining.measurement.WorkerBenchmarkPolicyService.Policy(java.util.List.of(), false, false, 86));
        store = new OperatingPointSafetyStore(json, file.toString());
        assertTrue(store.permits("gpu", point, 86));
        assertFalse(store.permits("gpu", point, 84));
    }
}
