package de.verdox.solarminer.pcagent.mining.measurement;

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
import java.util.ArrayList;
import java.util.List;

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

    public List<Profile> all() {
        List<Profile> values = new ArrayList<>(profiles.values());
        values.sort((a, b) -> {
            int byModel = a.hardwareModel().compareToIgnoreCase(b.hardwareModel());
            return byModel != 0 ? byModel : a.algorithm().compareToIgnoreCase(b.algorithm());
        });
        return List.copyOf(values);
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
        return de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(file, value, json, "performance-");
    }

    public record Profile(String workerId, String hardwareType, String hardwareModel, String coin, String algorithm,
                          double hashrateHps, long powerWatts, int observations, Instant measuredAt, String source) { }
}
