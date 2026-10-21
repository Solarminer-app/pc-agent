package de.verdox.solarminer.pcagent.mining.economics;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.support.AtomicFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact-point thermal quarantine, reconsidered against the current operator ceiling. */
@Service
public class OperatingPointSafetyStore {
    private final ObjectMapper json;
    private final Path file;
    private volatile Map<String, Rejection> rejected;
    private volatile boolean available = true;

    public OperatingPointSafetyStore(ObjectMapper json,
            @Value("${solarminer.agent.operating-point-safety-file:./solarminer-agent/operating-point-safety.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        try {
            var root = json.readTree(Files.readString(file));
            if (!root.isObject()) throw new IllegalArgumentException("Invalid thermal history");
            Map<String, Rejection> loaded = new LinkedHashMap<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                // Older agents persisted timestamps only. Preserve these until remeasurement
                // or an explicit operator increase of the previous temperature policy.
                var value = entry.getValue().isValueNode()
                        ? new Rejection(json.treeToValue(entry.getValue(), Instant.class), null, null)
                        : json.treeToValue(entry.getValue(), Rejection.class);
                if (value.failedAt() == null || value.temperatureC() != null
                        && (!Double.isFinite(value.temperatureC()) || value.temperatureC() <= 0)
                        || value.ceilingC() != null && (!Double.isFinite(value.ceilingC()) || value.ceilingC() <= 0))
                    throw new IllegalArgumentException("Invalid thermal history");
                loaded.put(entry.getKey(), value);
            }
            rejected = Map.copyOf(loaded);
        } catch (java.nio.file.NoSuchFileException missing) {
            rejected = Map.of();
        } catch (Exception failure) {
            rejected = Map.of();
            available = false;
        }
    }

    public boolean available() { return available; }

    public static String profileKey(de.verdox.solarminer.pcagent.mining.measurement.AutoTuningProfileStore.Profile p) {
        return p.coinId() + "|" + p.softwareId() + "|" + p.powerLimitWatts() + "|" + p.softwareVersion()
                + "|" + p.coreMhz() + "|" + p.memoryMhz() + "|" + p.memoryOffsetMhz() + "|" + p.memoryOffsetPstate();
    }
    public record Rejection(Instant failedAt, Double temperatureC, Double ceilingC) { }

    public boolean permits(String device, CoinHoppingService.Candidate candidate) {
        return permits(device, candidate, Double.NaN);
    }

    public boolean permits(String device, CoinHoppingService.Candidate candidate, double ceiling) {
        return permits(device, candidate.pointKey(), candidate.measuredAt(), ceiling);
    }

    public boolean permits(String device, String pointKey, Instant measuredAt, double ceiling) {
        if (!available) return false;
        Rejection failure = rejected.get(device + "|" + pointKey);
        return failure == null || measuredAt != null && measuredAt.isAfter(failure.failedAt())
                || failure.ceilingC() != null && failure.temperatureC() != null
                && ceiling > failure.ceilingC() && ceiling > failure.temperatureC();
    }

    public synchronized void reject(String device, String pointKey) {
        reject(device, pointKey, null, null);
    }

    public synchronized void reject(String device, String pointKey, Double temperature, Double ceiling) {
        Map<String, Rejection> next = new LinkedHashMap<>(rejected);
        next.put(device + "|" + pointKey, new Rejection(Instant.now(), temperature, ceiling));
        persist(next);
    }

    /** Legacy entries had no observed temperature; an explicit limit increase releases them once. */
    public synchronized void releaseLegacyOnLimitIncrease(String device, double previous, double nextLimit) {
        if (nextLimit <= previous) return;
        Map<String, Rejection> next = new LinkedHashMap<>(rejected);
        boolean changed = false;
        for (var entry : next.entrySet()) {
            if (entry.getKey().startsWith(device + "|") && entry.getValue().ceilingC() == null) {
                // The historical observation is unavailable. The explicit policy increase
                // acknowledges that old quarantine; retain its previous policy boundary.
                entry.setValue(new Rejection(entry.getValue().failedAt(), previous, previous));
                changed = true;
            }
        }
        if (changed) persist(next);
    }

    private void persist(Map<String, Rejection> next) {
        // Retain the in-memory quarantine even when a disk write fails.
        if (!available) throw new IllegalStateException("power_mode_point_safety_unavailable");
        rejected = Map.copyOf(next);
        if (!AtomicFileStore.writeJson(file, next, json, "point-safety-")) {
            available = false;
            throw new IllegalStateException("power_mode_point_safety_unavailable");
        }
    }
}
