package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.support.AtomicFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

/** Operator-owned measurement scope and independent automatic selection permissions. */
@Service
public class WorkerBenchmarkPolicyService {
    public record Pair(String coin, String minerSoftwareId) { }
    public record Policy(List<Pair> combinations, boolean allowCoinSwitch, boolean allowMinerSwitch,
                         double maxTemperatureC) {
        public Policy {
            combinations = combinations == null ? List.of() : List.copyOf(combinations);
            if (!Double.isFinite(maxTemperatureC) || maxTemperatureC < 40 || maxTemperatureC > 90)
                throw new IllegalArgumentException("benchmark_temperature_invalid");
            if (combinations.size() > 64 || combinations.stream().anyMatch(pair -> pair == null
                    || Coin.byIdOrNull(pair.coin()) == null || Coin.NONE.id().equals(pair.coin())
                    || pair.minerSoftwareId() == null || pair.minerSoftwareId().isBlank()))
                throw new IllegalArgumentException("benchmark_combination_invalid");
        }
        public boolean measures(String coin, String software) {
            return combinations.contains(new Pair(coin, software));
        }
    }
    private de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore pointSafety;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPointSafety(de.verdox.solarminer.pcagent.mining.economics.OperatingPointSafetyStore safety) {
        this.pointSafety = safety;
    }
    private final ObjectMapper json;
    private final Path file;
    private volatile Map<String, Policy> policies;
    public WorkerBenchmarkPolicyService(ObjectMapper json,
            @Value("${solarminer.agent.benchmark-policies-file:./solarminer-agent/benchmark-policies.json}") String path) {
        this.json = json; this.file = Path.of(path).toAbsolutePath().normalize();
        try { policies = Map.copyOf(json.readValue(Files.readString(file), new TypeReference<Map<String, Policy>>() { })); }
        catch (NoSuchFileException missing) { policies = Map.of(); }
        catch (Exception failure) { throw new IllegalStateException("benchmark_policy_load_failed", failure); }
    }
    public Map<String, Policy> all() { return policies; }
    public Policy get(String worker) { return policies.get(worker); }
    public boolean measures(String worker, String coin, String software) {
        Policy policy = get(worker);
        return policy == null || policy.measures(coin, software);
    }
    public boolean permits(String worker, String assignedCoin, String assignedMiner, String coin, String software) {
        Policy policy = get(worker);
        return policy == null || policy.measures(coin, software)
                && (policy.allowCoinSwitch() || Objects.equals(assignedCoin, coin))
                && (policy.allowMinerSwitch() || Objects.equals(assignedMiner, software));
    }
    public boolean hasMeasurements(Set<String> workers, Set<String> coins) {
        return workers.isEmpty() || workers.stream().anyMatch(worker -> get(worker) == null
                || get(worker).combinations().stream().anyMatch(pair -> coins.isEmpty() || coins.contains(pair.coin())));
    }
    public double ceiling(String worker, double fallback) {
        Policy policy = get(worker);
        return policy == null ? fallback : Math.min(fallback, policy.maxTemperatureC());
    }
    public boolean temperatureSafe(String worker, Double temperature, double fallback) {
        return temperature != null && Double.isFinite(temperature) && temperature > 0
                && temperature < ceiling(worker, fallback);
    }
    public synchronized void save(String worker, Policy policy) {
        if (worker == null || worker.isBlank() || policy == null) throw new IllegalArgumentException("benchmark_combination_invalid");
        Map<String, Policy> next = new LinkedHashMap<>(policies); next.put(worker, policy);
        if (!AtomicFileStore.writeJson(file, next, json, "benchmark-policy-"))
            throw new IllegalStateException("benchmark_policy_save_failed");
        Policy previous = policies.get(worker);
        policies = Map.copyOf(next);
        if (previous != null && pointSafety != null)
            pointSafety.releaseLegacyOnLimitIncrease(worker, previous.maxTemperatureC(), policy.maxTemperatureC());
    }
}
