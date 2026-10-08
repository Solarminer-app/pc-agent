package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Driver-backed GPU power control. Dynamic control is exposed only after the driver reports a
 * stable UUID, limits and a readable applied limit. Other cards are deliberately start/stop-only.
 */
@Service
public class LocalGpuPowerService {
    private static final Pattern AMD_INDEX = Pattern.compile("(?m)^\\s*GPU:\\s*(\\d+)\\b");
    private final ObjectMapper json;
    private final Path limitsFile;
    private volatile List<Gpu> cached = List.of();
    private volatile long discoveredAt;
    private volatile Map<String, UserLimits> userLimits;
    private volatile Map<String, Integer> applied = Map.of();
    private final Map<String, Integer> originalLimits = new HashMap<>();

    public record Gpu(String vendor, int index, String deviceId, String model, int driverMinWatts, int driverMaxWatts,
                      int minWatts, int maxWatts, Integer currentPowerLimitWatts, Double currentWatts,
                      String usageMeasurement, boolean supportsDynamicPowerScaling, String regulationError) {
    }

    public record UserLimits(int minimumWatts, int maximumWatts) {
    }

    public LocalGpuPowerService(ObjectMapper json, @Value("${solarminer.agent.gpu-power-settings-file:./solarminer-agent/gpu-power-limits.json}") String limitsPath) {
        this.json = json;
        this.limitsFile = Path.of(limitsPath).toAbsolutePath().normalize();
        this.userLimits = load();
    }

    public List<Gpu> discover() {
        if (System.nanoTime() - discoveredAt < TimeUnit.SECONDS.toNanos(2)) return cached;
        synchronized (this) {
            if (System.nanoTime() - discoveredAt >= TimeUnit.SECONDS.toNanos(2)) {
                cached = detect();
                discoveredAt = System.nanoTime();
            }
            return cached;
        }
    }

    private List<Gpu> detect() {
        List<Gpu> cards = new ArrayList<>();
        try {
            String output = run(List.of("nvidia-smi", "--query-gpu=index,uuid,name,power.min_limit,power.max_limit,power.limit,power.draw", "--format=csv,noheader,nounits"));
            for (String line : output.lines().toList()) {
                String[] c = line.split(",", -1);
                if (c.length != 7) continue;
                try {
                    int index = Integer.parseInt(c[0].trim());
                    String id = c[1].trim();
                    int driverMin = watts(c[3]), driverMax = watts(c[4]);
                    Integer actual = optionalWatts(c[5]);
                    boolean supported = id.startsWith("GPU-") && driverMin > 0 && driverMax >= driverMin && actual != null;
                    UserLimits saved = userLimits.get(id);
                    int min = supported ? Math.max(driverMin, saved == null ? driverMin : saved.minimumWatts()) : 0;
                    int max = supported ? Math.min(driverMax, saved == null ? driverMax : saved.maximumWatts()) : 0;
                    boolean valid = supported && min <= max;
                    cards.add(new Gpu("NVIDIA", index, id, c[2].trim(), driverMin, driverMax, min, max, actual, optionalDouble(c[6]), optionalDouble(c[6]) == null ? "unavailable" : "measured", valid, valid ? null : "Treibergrenzen oder Nutzergrenzen nicht sicher verifizierbar"));
                } catch (RuntimeException ignored) {
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        try {
            Matcher matches = AMD_INDEX.matcher(run(List.of("amd-smi", "list")));
            while (matches.find()) {
                int index = Integer.parseInt(matches.group(1));
                cards.add(new Gpu("AMD", index, "amd-index-" + index, "AMD GPU (Treiber-ID nicht verfügbar)", 0, 0, 0, 0, null, null, "unavailable", false, "Kein stabiler Gerätebezeichner und keine verifizierbaren Power-Limits verfügbar"));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return List.copyOf(cards);
    }

    public synchronized boolean setUserLimits(String deviceId, int minimumWatts, int maximumWatts) {
        Gpu gpu = discover().stream().filter(g -> g.deviceId().equals(deviceId)).findFirst().orElse(null);
        if (gpu == null || !gpu.supportsDynamicPowerScaling() || minimumWatts < gpu.driverMinWatts() || maximumWatts > gpu.driverMaxWatts() || minimumWatts > maximumWatts)
            return false;
        Map<String, UserLimits> next = new HashMap<>(userLimits);
        next.put(deviceId, new UserLimits(minimumWatts, maximumWatts));
        if (!save(next)) return false;
        userLimits = Map.copyOf(next);
        discoveredAt = 0;
        return true;
    }

    public int appliedTarget(Gpu gpu) {
        return applied.getOrDefault(gpu.deviceId(), gpu.currentPowerLimitWatts() == null ? 0 : gpu.currentPowerLimitWatts());
    }

    /**
     * Re-detects immediately before a write, constrains to persisted effective limits, then reads it back.
     */
    public synchronized boolean setTotalPowerTarget(long watts, List<Gpu> requested) {
        discoveredAt = 0;
        Map<String, Gpu> now = new HashMap<>();
        discover().forEach(g -> now.put(g.deviceId(), g));
        List<Gpu> cards = requested.stream().map(g -> now.get(g.deviceId())).filter(Objects::nonNull).toList();
        if (cards.size() != requested.size() || cards.isEmpty() || cards.stream().anyMatch(g -> !g.supportsDynamicPowerScaling()))
            return false;
        long min = cards.stream().mapToLong(Gpu::minWatts).sum(), max = cards.stream().mapToLong(Gpu::maxWatts).sum();
        if (watts < min || watts > max) return false;
        List<Integer> targets = distribute(watts, cards, min, max);
        Map<String, Integer> old = new HashMap<>(applied);
        try {
            for (Gpu card : cards)
                if (card.currentPowerLimitWatts() != null)
                    originalLimits.putIfAbsent(card.deviceId(), card.currentPowerLimitWatts());
            for (int i = 0; i < cards.size(); i++) setAndVerify(cards.get(i), targets.get(i));
            Map<String, Integer> next = new HashMap<>(applied);
            for (int i = 0; i < cards.size(); i++) next.put(cards.get(i).deviceId(), targets.get(i));
            applied = Map.copyOf(next);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            restore(cards, old);
            return false;
        }
    }

    private static List<Integer> distribute(long watts, List<Gpu> cards, long min, long max) {
        long remaining = watts - min, headroom = Math.max(1, max - min);
        List<Integer> targets = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            Gpu g = cards.get(i);
            int target = i == cards.size() - 1 ? (int) (g.minWatts() + remaining) : (int) (g.minWatts() + (watts - min) * (g.maxWatts() - g.minWatts()) / headroom);
            target = Math.max(g.minWatts(), Math.min(g.maxWatts(), target));
            remaining -= target - g.minWatts();
            targets.add(target);
        }
        return targets;
    }

    private void setAndVerify(Gpu gpu, int target) throws IOException, InterruptedException {
        run(List.of("nvidia-smi", "-i", gpu.deviceId(), "-pl", Integer.toString(target)));
        Integer actual = optionalWatts(run(List.of("nvidia-smi", "-i", gpu.deviceId(), "--query-gpu=power.limit", "--format=csv,noheader,nounits")).strip());
        if (actual == null || actual < gpu.minWatts() || actual > gpu.maxWatts() || Math.abs(actual - target) > 1)
            throw new IOException("Driver did not verify power limit");
    }

    private void restore(List<Gpu> cards, Map<String, Integer> old) {
        for (Gpu g : cards)
            try {
                if (old.containsKey(g.deviceId()))
                    run(List.of("nvidia-smi", "-i", g.deviceId(), "-pl", String.valueOf(old.get(g.deviceId()))));
            } catch (Exception ignored) {
            }
    }

    @PreDestroy
    public synchronized void restoreOriginalLimits() {
        for (Map.Entry<String, Integer> entry : originalLimits.entrySet()) {
            try {
                run(List.of("nvidia-smi", "-i", entry.getKey(), "-pl", String.valueOf(entry.getValue())));
            } catch (Exception ignored) {
            }
        }
    }

    private Map<String, UserLimits> load() {
        try {
            return Map.copyOf(json.readValue(Files.readString(limitsFile), new TypeReference<Map<String, UserLimits>>() {
            }));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean save(Map<String, UserLimits> value) {
        try {
            Files.createDirectories(limitsFile.getParent());
            Path tmp = Files.createTempFile(limitsFile.getParent(), "gpu-limits-", ".json");
            try {
                json.writeValue(tmp.toFile(), value);
                Files.move(tmp, limitsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, limitsFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int watts(String s) {
        Integer value = optionalWatts(s);
        if (value == null) throw new NumberFormatException(s);
        return value;
    }

    private static Integer optionalWatts(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) && v >= 0 ? (int) Math.round(v) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double optionalDouble(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) && v >= 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String run(List<String> command) throws IOException, InterruptedException {
        Path output = Files.createTempFile("solarminer-gpu-", ".log");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("GPU command timed out");
            }
            if (Files.size(output) > 32768) throw new IOException("GPU tool output exceeded 32 KiB");
            String text = Files.readString(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException("GPU tool failed: " + text.strip());
            return text;
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
