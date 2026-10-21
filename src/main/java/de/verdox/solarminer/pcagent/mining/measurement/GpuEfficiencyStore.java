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
    private volatile String loadError;

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
        String profileKey = key(profile.deviceId(), profile.algorithm()) + "|"
                + java.util.Objects.toString(profile.minerSoftwareId(), "legacy");
        // Keep failed attempts as history without replacing the latest usable profile
        // consumed by economic planning through find(deviceId, algorithm).
        String storageKey = profile.bestStableWatts() == null
                ? profileKey + "|attempt|" + profile.testedAt()
                : profileKey;
        next.put(storageKey, profile);
        if (!write(next)) throw new IllegalStateException("Efficiency profiles could not be persisted");
        profiles = Map.copyOf(next);
    }

    public synchronized boolean delete(Profile profile) {
        if (profile == null) return false;
        Map<String, Profile> next = new LinkedHashMap<>(profiles);
        String storageKey = next.entrySet().stream()
                .filter(entry -> entry.getValue().equals(profile))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (storageKey == null) return false;
        next.remove(storageKey);
        if (!write(next)) throw new IllegalStateException("Efficiency profiles could not be persisted");
        profiles = Map.copyOf(next);
        return true;
    }

    public List<Profile> all() {
        List<Profile> values = new ArrayList<>(profiles.values());
        values.sort((a, b) -> {
            int byModel = a.model().compareToIgnoreCase(b.model());
            return byModel != 0 ? byModel : a.algorithm().compareToIgnoreCase(b.algorithm());
        });
        return List.copyOf(values);
    }

    public StorageStatus storageStatus() {
        return new StorageStatus(file.toString(), Files.isRegularFile(file), profiles.size(), loadError);
    }

    public Profile find(String deviceId, String algorithm) {
        de.verdox.solarminer.pcagent.coin.Coin coin = de.verdox.solarminer.pcagent.coin.Coin.byAlgorithm(algorithm).orElse(null);
        if (coin == null) return null;
        return profiles.values().stream()
                .filter(profile -> profile.bestStableWatts() != null && deviceId.equals(profile.deviceId())
                        && coin.matchesAlgorithm(profile.algorithm()))
                .max(java.util.Comparator.comparing(Profile::testedAt)).orElse(null);
    }

    public Profile find(String deviceId, String algorithm, String softwareId) {
        de.verdox.solarminer.pcagent.coin.Coin coin = de.verdox.solarminer.pcagent.coin.Coin.byAlgorithm(algorithm).orElse(null);
        if (coin == null) return null;
        return profiles.values().stream().filter(profile -> profile.bestStableWatts() != null
                        && deviceId.equals(profile.deviceId()) && coin.matchesAlgorithm(profile.algorithm())
                        && (softwareId == null || profile.minerSoftwareId() == null
                        || softwareId.equals(profile.minerSoftwareId())))
                .max(java.util.Comparator.comparing(Profile::testedAt)).orElse(null);
    }

    private Map<String, Profile> load() {
        try {
            return Map.copyOf(json.readValue(Files.readString(file), new TypeReference<Map<String, Profile>>() {
            }));
        } catch (java.nio.file.NoSuchFileException missing) {
            return Map.of();
        } catch (Exception error) {
            loadError = error.getClass().getSimpleName() + ": " + java.util.Objects.toString(error.getMessage(), "");
            return Map.of();
        }
    }

    private boolean write(Map<String, Profile> value) {
        return de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(file, value, json, "gpu-efficiency-");
    }

    public record Profile(String deviceId, String model, String coin, String algorithm,
                          Integer bestStableWatts, Double bestHashrateHs, Double bestPowerWatts,
                          Double bestHashesPerWatt, Instant testedAt, List<StepResult> steps,
                          String minerSoftwareId) {
        public Profile(String deviceId, String model, String coin, String algorithm,
                       Integer bestStableWatts, Double bestHashrateHs, Double bestPowerWatts,
                       Double bestHashesPerWatt, Instant testedAt, List<StepResult> steps) {
            this(deviceId, model, coin, algorithm, bestStableWatts, bestHashrateHs,
                    bestPowerWatts, bestHashesPerWatt, testedAt, steps, null);
        }
    }

    public record StorageStatus(String file, boolean exists, int profiles, String loadError) { }

    public record StepResult(int limitWatts, Double medianHashrateHs, Double avgPowerWatts,
                             Double maxTemperatureC, boolean stable, String note,
                             Integer observedCoreMhz, Integer observedMemoryMhz,
                             ThermalStabilityTracker.Evidence thermalValidation) {
        public StepResult(int limitWatts, Double medianHashrateHs, Double avgPowerWatts,
                          Double maxTemperatureC, boolean stable, String note,
                          Integer observedCoreMhz, Integer observedMemoryMhz) {
            this(limitWatts, medianHashrateHs, avgPowerWatts, maxTemperatureC, stable, note,
                    observedCoreMhz, observedMemoryMhz, null);
        }
        public StepResult(int limitWatts, Double medianHashrateHs, Double avgPowerWatts,
                          Double maxTemperatureC, boolean stable, String note) {
            this(limitWatts, medianHashrateHs, avgPowerWatts, maxTemperatureC, stable, note, null, null);
        }
    }
}
