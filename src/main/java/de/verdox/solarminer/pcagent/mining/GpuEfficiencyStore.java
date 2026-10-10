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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted per-device efficiency profiles produced by the efficiency sweep. The store keeps
 * the best stable power limit per GPU and algorithm plus the full step history so the operator
 * can review every measured value. It never stores wallets, pools or identities.
 */
@Service
public class GpuEfficiencyStore {
    private final ObjectMapper json;
    private final Path file;
    private volatile Map<String, Profile> profiles;

    public GpuEfficiencyStore(ObjectMapper json,
                              @Value("${solarminer.agent.gpu-efficiency-file:./solarminer-agent/gpu-efficiency-profiles.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.profiles = load();
    }

    public static String key(String deviceId, String algorithm) {
        return deviceId + "|" + algorithm;
    }

    public synchronized void save(Profile profile) {
        Map<String, Profile> next = new LinkedHashMap<>(profiles);
        next.put(key(profile.deviceId(), profile.algorithm()), profile);
        if (!write(next)) throw new IllegalStateException("Efficiency profiles could not be persisted");
        profiles = Map.copyOf(next);
    }

    public List<Profile> all() {
        List<Profile> values = new ArrayList<>(profiles.values());
        values.sort((a, b) -> {
            int byModel = a.model().compareToIgnoreCase(b.model());
            return byModel != 0 ? byModel : a.algorithm().compareToIgnoreCase(b.algorithm());
        });
        return List.copyOf(values);
    }

    public Profile find(String deviceId, String algorithm) {
        return profiles.get(key(deviceId, algorithm));
    }

    private Map<String, Profile> load() {
        try {
            return Map.copyOf(json.readValue(Files.readString(file), new TypeReference<Map<String, Profile>>() {
            }));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean write(Map<String, Profile> value) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "gpu-efficiency-", ".json");
            try {
                json.writeValue(temp.toFile(), value);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public record Profile(String deviceId, String model, String coin, String algorithm,
                          Integer bestStableWatts, Double bestHashrateHs, Double bestPowerWatts,
                          Double bestHashesPerWatt, Instant testedAt, List<StepResult> steps) {
    }

    public record StepResult(int limitWatts, Double medianHashrateHs, Double avgPowerWatts,
                             Double maxTemperatureC, boolean stable, String note) {
    }
}
