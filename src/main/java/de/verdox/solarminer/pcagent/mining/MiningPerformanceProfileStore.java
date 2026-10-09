package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable local benchmark evidence for economic dispatch; contains no pool credentials or identity. */
@Service
public class MiningPerformanceProfileStore {
    private final ObjectMapper json;
    private final Path file;
    private volatile Map<String, Profile> profiles;

    public MiningPerformanceProfileStore(ObjectMapper json,
                                         @Value("${solarminer.agent.performance-profiles-file:./solarminer-agent/performance-profiles.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.profiles = load();
    }

    public static String key(String workerId, String coin, String algorithm) {
        return workerId + "|" + coin + "|" + algorithm.toLowerCase(java.util.Locale.ROOT);
    }

    public Profile find(String workerId, String coin, String algorithm) {
        return profiles.get(key(workerId, coin, algorithm));
    }

    public synchronized void save(Profile profile) {
        Map<String, Profile> next = new LinkedHashMap<>(profiles);
        next.put(key(profile.workerId(), profile.coin(), profile.algorithm()), profile);
        if (!write(next)) throw new IllegalStateException("Performance profile could not be persisted");
        profiles = Map.copyOf(next);
    }

    private Map<String, Profile> load() {
        try {
            return Map.copyOf(json.readValue(Files.readString(file), new TypeReference<Map<String, Profile>>() { }));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private boolean write(Map<String, Profile> value) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "performance-", ".json");
            try {
                json.writeValue(temporary.toFile(), value);
                try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    public record Profile(String workerId, String hardwareType, String hardwareModel, String coin, String algorithm,
                          double hashrateHps, long powerWatts, int observations, Instant measuredAt, String source) { }
}
