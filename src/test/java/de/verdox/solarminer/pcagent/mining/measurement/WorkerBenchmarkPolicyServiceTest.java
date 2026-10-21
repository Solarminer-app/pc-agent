package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkerBenchmarkPolicyServiceTest {
    @TempDir Path directory;
    private WorkerBenchmarkPolicyService store() {
        return new WorkerBenchmarkPolicyService(new ObjectMapper(), directory.resolve("policies.json").toString());
    }
    @Test void independentCoinAndSoftwarePermissionsSurviveRestart() {
        var store = store();
        var pairs = List.of(new WorkerBenchmarkPolicyService.Pair("pearl", "srbminer-multi"),
                new WorkerBenchmarkPolicyService.Pair("pearl", "peakminer"),
                new WorkerBenchmarkPolicyService.Pair("quantus", "srbminer-multi"),
                new WorkerBenchmarkPolicyService.Pair("quantus", "wildrig-multi"));
        for (boolean coin : List.of(false, true)) for (boolean miner : List.of(false, true)) {
            store.save("gpu", new WorkerBenchmarkPolicyService.Policy(pairs, coin, miner, 72));
            var reloaded = store();
            assertTrue(reloaded.permits("gpu", "pearl", "srbminer-multi", "pearl", "srbminer-multi"));
            assertEquals(coin, reloaded.permits("gpu", "pearl", "srbminer-multi", "quantus", "srbminer-multi"));
            assertEquals(miner, reloaded.permits("gpu", "pearl", "srbminer-multi", "pearl", "peakminer"));
            assertEquals(coin && miner, reloaded.permits("gpu", "pearl", "srbminer-multi", "quantus", "wildrig-multi"));
            assertFalse(reloaded.permits("gpu", "pearl", "srbminer-multi", "ravencoin", "srbminer-multi"));
        }
    }
    @Test void emptySelectionExcludesWorkerWithoutWideningOtherWorkers() {
        var store = store();
        store.save("gpu", new WorkerBenchmarkPolicyService.Policy(List.of(), true, true, 70));
        assertFalse(store.measures("gpu", "pearl", "peakminer"));
        assertTrue(store.measures("other", "pearl", "peakminer"));
    }
    @Test void temperatureRejectsExactThresholdMissingAndNonFiniteValues() {
        var store = store();
        store.save("gpu", new WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 72));
        assertTrue(store.temperatureSafe("gpu", 71.9, 85));
        for (Double temp : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, 0d, -1d, 72d, 80d})
            assertFalse(store.temperatureSafe("gpu", temp, 85));
        assertFalse(store.temperatureSafe("gpu", 70d, 65)); // Operator cannot weaken a stricter server ceiling.
    }
    @Test void invalidTemperatureNeverPersists() {
        for (double limit : new double[]{39, 91, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () ->
                    new WorkerBenchmarkPolicyService.Policy(List.of(), false, false, limit));
    }
    @Test void unreadablePolicyFailsClosedInsteadOfEnablingUnrestrictedSelection() throws Exception {
        Files.writeString(directory.resolve("policies.json"), "broken");
        assertThrows(IllegalStateException.class, this::store);
    }
    @Test void failedWriteRetainsCurrentPolicy() throws Exception {
        var store = store();
        var policy = new WorkerBenchmarkPolicyService.Policy(List.of(), false, false, 72);
        store.save("gpu", policy);
        Files.delete(directory.resolve("policies.json"));
        Files.createDirectory(directory.resolve("policies.json"));
        assertThrows(IllegalStateException.class, () -> store.save("gpu",
                new WorkerBenchmarkPolicyService.Policy(List.of(), true, true, 80)));
        assertEquals(policy, store.get("gpu"));
    }
}
