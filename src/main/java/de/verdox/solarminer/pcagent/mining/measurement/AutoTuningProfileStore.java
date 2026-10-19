package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted best-known settings scoped to device, algorithm, and exact miner software. */
@Service
public class AutoTuningProfileStore {
    private final Path file;
    private final ObjectMapper json;
    private final Map<String, List<Profile>> profiles = new LinkedHashMap<>();

    public AutoTuningProfileStore(ObjectMapper json,
            @Value("${solarminer.agent.auto-tuning-profiles-file:./solarminer-agent/auto-tuning-profiles.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        try {
            JsonNode root = json.readTree(Files.readString(file));
            root.fields().forEachRemaining(entry -> {
                try {
                    List<Profile> rows = entry.getValue().isArray()
                            ? json.convertValue(entry.getValue(), new TypeReference<List<Profile>>() { })
                            : List.of(json.treeToValue(entry.getValue(), Profile.class));
                    if (!rows.isEmpty()) profiles.put(entry.getKey(), List.copyOf(rows));
                } catch (Exception ignored) { /* A damaged entry does not hide other devices. */ }
            });
        }
        catch (Exception ignored) { }
    }

    public synchronized Profile find(String deviceId, String coinId, String softwareId) {
        return profiles.getOrDefault(key(deviceId, coinId, softwareId), List.of()).stream()
                .max(java.util.Comparator.comparingDouble(Profile::efficiencyHashesPerWatt)).orElse(null);
    }

    public synchronized List<Profile> all() { return profiles.values().stream().flatMap(List::stream).toList(); }

    public synchronized List<Profile> matrix(String deviceId, String coinId, String softwareId,
                                             String softwareVersion, String model, String vendor) {
        return matrix(deviceId, coinId, softwareId, softwareVersion, model, vendor, null);
    }

    public synchronized List<Profile> matrix(String deviceId, String coinId, String softwareId,
                                             String softwareVersion, String model, String vendor, String algorithm) {
        List<Profile> rows = profiles.getOrDefault(key(deviceId, coinId, softwareId), List.of());
        if (rows.size() < 3 || rows.stream().anyMatch(row -> row == null || row.powerLimitWatts() == null
                || row.powerLimitWatts() <= 0) || rows.stream().map(Profile::powerLimitWatts).distinct().count() < 3
                || rows.stream().anyMatch(row -> !java.util.Objects.equals(row.softwareVersion(), softwareVersion)
                || !java.util.Objects.equals(row.model(), model) || !java.util.Objects.equals(row.vendor(), vendor)
                || algorithm != null && !java.util.Objects.equals(row.algorithm(), algorithm)))
            return List.of();
        return rows.stream().sorted(java.util.Comparator.comparingInt(Profile::powerLimitWatts)).toList();
    }

    public synchronized Profile forBudget(String deviceId, String coinId, String softwareId,
                                          String softwareVersion, String model, String vendor, int budgetWatts) {
        return forBudget(deviceId, coinId, softwareId, softwareVersion, model, vendor, budgetWatts, null);
    }

    public synchronized Profile forBudget(String deviceId, String coinId, String softwareId,
                                          String softwareVersion, String model, String vendor, int budgetWatts,
                                          String algorithm) {
        return matrix(deviceId, coinId, softwareId, softwareVersion, model, vendor, algorithm).stream()
                .filter(row -> row.powerLimitWatts() <= budgetWatts)
                .max(java.util.Comparator.comparingInt(Profile::powerLimitWatts)).orElse(null);
    }

    /** Local autonomous mode optimizes efficiency; explicit solar budgets select a tier separately. */
    public synchronized Profile mostEfficientWithinBudget(String deviceId, String coinId, String softwareId,
                                                            String softwareVersion, String model, String vendor,
                                                            int budgetWatts, String algorithm) {
        return matrix(deviceId, coinId, softwareId, softwareVersion, model, vendor, algorithm).stream()
                .filter(row -> row.powerLimitWatts() <= budgetWatts)
                .max(java.util.Comparator.comparingDouble(Profile::efficiencyHashesPerWatt)
                        .thenComparingInt(row -> -row.powerLimitWatts())).orElse(null);
    }

    public synchronized boolean saveMatrix(List<Profile> rows) {
        if (rows == null || rows.size() < 3 || rows.stream().anyMatch(row -> row == null || row.powerLimitWatts() == null
                || row.powerLimitWatts() <= 0 || !Double.isFinite(row.efficiencyHashesPerWatt())
                || row.efficiencyHashesPerWatt() <= 0)) return false;
        Profile first = rows.getFirst();
        if (rows.stream().anyMatch(row -> !key(row.deviceId(), row.coinId(), row.softwareId())
                .equals(key(first.deviceId(), first.coinId(), first.softwareId()))
                || !java.util.Objects.equals(row.algorithm(), first.algorithm())
                || !java.util.Objects.equals(row.softwareVersion(), first.softwareVersion())
                || !java.util.Objects.equals(row.model(), first.model())
                || !java.util.Objects.equals(row.vendor(), first.vendor()))
                || rows.stream().map(Profile::powerLimitWatts).distinct().count() != rows.size()) return false;
        return saveRows(first, rows);
    }

    public synchronized boolean save(Profile profile) {
        return saveRows(profile, List.of(profile));
    }

    private boolean saveRows(Profile profile, List<Profile> rows) {
        Map<String, List<Profile>> next = new LinkedHashMap<>(profiles);
        next.put(key(profile.deviceId(), profile.coinId(), profile.softwareId()), List.copyOf(rows));
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(file, next, json,
                file.getFileName().toString())) return false;
        profiles.clear(); profiles.putAll(next);
        return true;
    }

    private static String key(String deviceId, String coinId, String softwareId) {
        return deviceId + "|" + coinId + "|" + (softwareId == null ? "" : softwareId);
    }

    public record Profile(String deviceId, String model, String vendor, String coinId, String algorithm,
                          String softwareId, String softwareVersion, Integer coreMhz, Integer memoryMhz, Integer powerLimitWatts,
                          double hashrateHs, double measuredWatts, int stableSamples,
                          double efficiencyHashesPerWatt, Instant calibratedAt) {
        public Profile(String deviceId, String model, String vendor, String coinId, String algorithm,
                       String softwareId, Integer coreMhz, Integer memoryMhz, Integer powerLimitWatts,
                       double hashrateHs, double measuredWatts, int stableSamples,
                       double efficiencyHashesPerWatt, Instant calibratedAt) {
            this(deviceId, model, vendor, coinId, algorithm, softwareId, null, coreMhz, memoryMhz,
                    powerLimitWatts, hashrateHs, measuredWatts, stableSamples, efficiencyHashesPerWatt, calibratedAt);
        }
    }
}
