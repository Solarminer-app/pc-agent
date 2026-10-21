package de.verdox.solarminer.pcagent.mining.economics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.support.AtomicFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A thermal failure withdraws automatic use until that exact point is measured again. */
@Service
public class OperatingPointSafetyStore {
    private final ObjectMapper json;
    private final Path file;
    private volatile Map<String, Instant> rejected;
    private volatile boolean available = true;

    public OperatingPointSafetyStore(ObjectMapper json,
            @Value("${solarminer.agent.operating-point-safety-file:./solarminer-agent/operating-point-safety.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        try {
            rejected = Map.copyOf(json.readValue(Files.readString(file), new TypeReference<Map<String, Instant>>() { }));
        } catch (java.nio.file.NoSuchFileException missing) {
            rejected = Map.of();
        } catch (Exception failure) {
            rejected = Map.of();
            available = false;
        }
    }

    public boolean available() { return available; }

    public boolean permits(String device, CoinHoppingService.Candidate candidate) {
        if (!available) return false;
        Instant failedAt = rejected.get(device + "|" + candidate.pointKey());
        return failedAt == null || candidate.measuredAt() != null && candidate.measuredAt().isAfter(failedAt);
    }

    public synchronized void reject(String device, String pointKey) {
        Map<String, Instant> next = new LinkedHashMap<>(rejected);
        next.put(device + "|" + pointKey, Instant.now());
        // Keep the in-memory rejection even if persistence fails. Automatic selection
        // then stays unavailable rather than forgetting the failure on the next tick.
        rejected = Map.copyOf(next);
        if (!available) throw new IllegalStateException("power_mode_point_safety_unavailable");
        if (!AtomicFileStore.writeJson(file, next, json, "point-safety-")) {
            available = false;
            throw new IllegalStateException("power_mode_point_safety_unavailable");
        }
    }
}
