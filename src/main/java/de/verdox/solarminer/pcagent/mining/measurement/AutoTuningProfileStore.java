package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persisted best-known settings scoped to device, algorithm, and exact miner software. */
@Service
public class AutoTuningProfileStore {
    private final Path file;
    private final Path measurementFile;
    private final ObjectMapper json;
    private final Map<String, List<Profile>> profiles = new LinkedHashMap<>();
    private final List<Measurement> measurements = new ArrayList<>();

    public AutoTuningProfileStore(ObjectMapper json,
            @Value("${solarminer.agent.auto-tuning-profiles-file:./solarminer-agent/auto-tuning-profiles.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.measurementFile = file.resolveSibling(file.getFileName() + ".measurements.jsonl");
        try {
            JsonNode root = json.readTree(Files.readString(file));
            JsonNode storedProfiles = root.has("profiles") && root.get("profiles").isObject()
                    ? root.get("profiles") : root;
            if (root.has("measurements") && root.get("measurements").isArray())
                measurements.addAll(json.convertValue(root.get("measurements"), new TypeReference<List<Measurement>>() { }));
            storedProfiles.fields().forEachRemaining(entry -> {
                if (entry.getKey().equals("measurements")) return;
                try {
                    List<Profile> rows = entry.getValue().isArray()
                            ? json.convertValue(entry.getValue(), new TypeReference<List<Profile>>() { })
                            : List.of(json.treeToValue(entry.getValue(), Profile.class));
                    if (!rows.isEmpty()) profiles.put(entry.getKey(), List.copyOf(rows));
                } catch (Exception ignored) { /* A damaged entry does not hide other devices. */ }
            });
        }
        catch (Exception ignored) { }
        try (var reader = Files.newBufferedReader(measurementFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    Measurement row = json.readValue(line, Measurement.class);
                    measurements.add(row);
                    Profile stable = row.stableProfile();
                    if (stable != null) {
                        String key = key(stable.deviceId(), stable.coinId(), stable.softwareId());
                        List<Profile> history = new ArrayList<>(profiles.getOrDefault(key, List.of()));
                        if (!history.contains(stable)) history.add(stable);
                        profiles.put(key, List.copyOf(history));
                    }
                } catch (Exception ignored) { /* A truncated event never hides later complete lines. */ }
            }
        } catch (Exception ignored) { }
    }

    public synchronized Profile find(String deviceId, String coinId, String softwareId) {
        return profiles.getOrDefault(key(deviceId, coinId, softwareId), List.of()).stream()
                .max(java.util.Comparator.comparingDouble(Profile::efficiencyHashesPerWatt)).orElse(null);
    }

    public synchronized List<Profile> all() { return profiles.values().stream().flatMap(List::stream).toList(); }

    public synchronized List<Measurement> measurements() { return List.copyOf(measurements); }

    /** Persist every raw sample and candidate outcome before continuing calibration. */
    public synchronized boolean appendMeasurement(Measurement measurement) {
        if (measurement == null || measurement.at() == null || measurement.runId() == null
                || measurement.deviceId() == null || measurement.coinId() == null
                || measurement.softwareId() == null || measurement.powerLimitWatts() <= 0
                || measurement.type() == null || !List.of("SAMPLE", "RESULT").contains(measurement.type())) return false;
        Profile stable = measurement.stableProfile();
        if (Boolean.TRUE.equals(measurement.stable()) && stable == null) return false;
        try {
            Files.createDirectories(measurementFile.getParent());
            byte[] line = (json.writeValueAsString(measurement) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(measurementFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                long end = channel.size();
                if (end > 0) {
                    ByteBuffer last = ByteBuffer.allocate(1);
                    if (channel.read(last, end - 1) != 1) return false;
                    last.flip();
                    channel.position(end);
                    if (last.get() != '\n') channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
                }
                ByteBuffer bytes = ByteBuffer.wrap(line);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
        } catch (Exception failure) { return false; }
        if (stable != null) {
            String key = key(stable.deviceId(), stable.coinId(), stable.softwareId());
            List<Profile> rows = new ArrayList<>(profiles.getOrDefault(key, List.of()));
            rows.add(stable);
            profiles.put(key, List.copyOf(rows));
        }
        measurements.add(measurement);
        return true;
    }

    /** All verified points for this exact hardware/algorithm/miner version, including repeated runs. */
    public synchronized List<Profile> candidates(String deviceId, String coinId, String softwareId,
                                                  String softwareVersion, String model, String vendor, String algorithm) {
        return profiles.getOrDefault(key(deviceId, coinId, softwareId), List.of()).stream()
                .filter(row -> row != null && row.powerLimitWatts() != null && row.powerLimitWatts() > 0
                        && row.stableSamples() > 0 && Double.isFinite(row.hashrateHs()) && row.hashrateHs() > 0
                        && Double.isFinite(row.measuredWatts()) && row.measuredWatts() > 0
                        && Double.isFinite(row.efficiencyHashesPerWatt()) && row.efficiencyHashesPerWatt() > 0
                        && Objects.equals(row.softwareVersion(), softwareVersion)
                        && Objects.equals(row.model(), model) && Objects.equals(row.vendor(), vendor)
                        && (algorithm == null || Objects.equals(row.algorithm(), algorithm)))
                .toList();
    }

    public synchronized List<Profile> matrix(String deviceId, String coinId, String softwareId,
                                             String softwareVersion, String model, String vendor) {
        return matrix(deviceId, coinId, softwareId, softwareVersion, model, vendor, null);
    }

    public synchronized List<Profile> matrix(String deviceId, String coinId, String softwareId,
                                             String softwareVersion, String model, String vendor, String algorithm) {
        List<Profile> rows = candidates(deviceId, coinId, softwareId, softwareVersion, model, vendor, algorithm);
        if (rows.stream().map(Profile::powerLimitWatts).distinct().count() < 3) return List.of();
        Map<String, List<Profile>> repeatedSettings = new LinkedHashMap<>();
        for (Profile row : rows) repeatedSettings.computeIfAbsent(row.powerLimitWatts() + "|"
                + row.coreMhz() + "|" + row.memoryMhz() + "|" + row.memoryOffsetMhz()
                + "|" + row.memoryOffsetPstate(), ignored -> new ArrayList<>()).add(row);
        Map<Integer, Profile> bestAtPower = new LinkedHashMap<>();
        for (List<Profile> repeats : repeatedSettings.values()) {
            Profile row = aggregate(repeats);
            bestAtPower.merge(row.powerLimitWatts(), row,
                (left, right) -> Comparator.comparingDouble(Profile::efficiencyHashesPerWatt)
                        .thenComparing(Profile::calibratedAt).compare(left, right) >= 0 ? left : right);
        }
        return bestAtPower.values().stream().sorted(Comparator.comparingInt(Profile::powerLimitWatts)).toList();
    }

    private static Profile aggregate(List<Profile> repeats) {
        Profile latest = repeats.stream().max(Comparator.comparing(Profile::calibratedAt)).orElseThrow();
        double hashrate = SampleCollector.median(repeats.stream().mapToDouble(Profile::hashrateHs).toArray());
        double watts = SampleCollector.median(repeats.stream().mapToDouble(Profile::measuredWatts).toArray());
        int samples = (int) Math.min(Integer.MAX_VALUE, repeats.stream().mapToLong(Profile::stableSamples).sum());
        return new Profile(latest.deviceId(), latest.model(), latest.vendor(), latest.coinId(), latest.algorithm(),
                latest.softwareId(), latest.softwareVersion(), latest.coreMhz(), latest.memoryMhz(),
                latest.powerLimitWatts(), hashrate, watts, samples, hashrate / watts, latest.calibratedAt(),
                latest.memoryOffsetMhz(), latest.memoryOffsetPstate());
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
        String key = key(profile.deviceId(), profile.coinId(), profile.softwareId());
        List<Profile> history = new ArrayList<>(next.getOrDefault(key, List.of()));
        for (Profile row : rows) if (!history.contains(row)) history.add(row);
        next.put(key, List.copyOf(history));
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(file, next, json,
                file.getFileName().toString())) return false;
        profiles.clear(); profiles.putAll(next);
        return true;
    }

    public record Measurement(String runId, Instant at, String deviceId, String model, String vendor,
                              String coinId, String algorithm, String softwareId, String softwareVersion,
                              Integer coreMhz, Integer memoryMhz, int powerLimitWatts, String type,
                              Double hashrateHs, Double measuredWatts, Double temperatureC,
                              Integer observedCoreMhz, Integer observedMemoryMhz,
                              Integer validSamples, Long acceptedShares, Long rejectedShares,
                              Boolean stable, String reason, Integer memoryOffsetMhz, Integer memoryOffsetPstate) {
        public Measurement(String runId, Instant at, String deviceId, String model, String vendor,
                           String coinId, String algorithm, String softwareId, String softwareVersion,
                           Integer coreMhz, Integer memoryMhz, int powerLimitWatts, String type,
                           Double hashrateHs, Double measuredWatts, Double temperatureC,
                           Integer observedCoreMhz, Integer observedMemoryMhz,
                           Integer validSamples, Long acceptedShares, Long rejectedShares,
                           Boolean stable, String reason) {
            this(runId, at, deviceId, model, vendor, coinId, algorithm, softwareId, softwareVersion,
                    coreMhz, memoryMhz, powerLimitWatts, type, hashrateHs, measuredWatts, temperatureC,
                    observedCoreMhz, observedMemoryMhz, validSamples, acceptedShares, rejectedShares,
                    stable, reason, null, null);
        }
        Profile stableProfile() {
            if (!"RESULT".equals(type) || !Boolean.TRUE.equals(stable) || hashrateHs == null
                    || measuredWatts == null || !Double.isFinite(hashrateHs) || !Double.isFinite(measuredWatts)
                    || hashrateHs <= 0 || measuredWatts <= 0 || validSamples == null || validSamples <= 0) return null;
            return new Profile(deviceId, model, vendor, coinId, algorithm, softwareId, softwareVersion,
                    coreMhz, memoryMhz, powerLimitWatts, hashrateHs, measuredWatts, validSamples == null ? 0 : validSamples,
                    hashrateHs / measuredWatts, at, memoryOffsetMhz, memoryOffsetPstate);
        }
    }

    private static String key(String deviceId, String coinId, String softwareId) {
        return deviceId + "|" + coinId + "|" + (softwareId == null ? "" : softwareId);
    }

    public record Profile(String deviceId, String model, String vendor, String coinId, String algorithm,
                          String softwareId, String softwareVersion, Integer coreMhz, Integer memoryMhz, Integer powerLimitWatts,
                          double hashrateHs, double measuredWatts, int stableSamples,
                          double efficiencyHashesPerWatt, Instant calibratedAt,
                          Integer memoryOffsetMhz, Integer memoryOffsetPstate) {
        public Profile(String deviceId, String model, String vendor, String coinId, String algorithm,
                       String softwareId, String softwareVersion, Integer coreMhz, Integer memoryMhz,
                       Integer powerLimitWatts, double hashrateHs, double measuredWatts, int stableSamples,
                       double efficiencyHashesPerWatt, Instant calibratedAt) {
            this(deviceId, model, vendor, coinId, algorithm, softwareId, softwareVersion, coreMhz, memoryMhz,
                    powerLimitWatts, hashrateHs, measuredWatts, stableSamples, efficiencyHashesPerWatt,
                    calibratedAt, null, null);
        }
        public Profile(String deviceId, String model, String vendor, String coinId, String algorithm,
                       String softwareId, Integer coreMhz, Integer memoryMhz, Integer powerLimitWatts,
                       double hashrateHs, double measuredWatts, int stableSamples,
                       double efficiencyHashesPerWatt, Instant calibratedAt) {
            this(deviceId, model, vendor, coinId, algorithm, softwareId, null, coreMhz, memoryMhz,
                    powerLimitWatts, hashrateHs, measuredWatts, stableSamples, efficiencyHashesPerWatt, calibratedAt);
        }
    }
}
