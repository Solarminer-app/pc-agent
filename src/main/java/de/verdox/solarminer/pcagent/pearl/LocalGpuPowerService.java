package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
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

/** Driver-backed NVIDIA/AMD power-cap control with verified writes and crash recovery. */
@Service
public class LocalGpuPowerService {
    private static final long DISCOVERY_CACHE = TimeUnit.SECONDS.toNanos(2);
    private static final long CAPABILITY_CACHE = TimeUnit.MINUTES.toNanos(5);

    @FunctionalInterface
    interface CommandRunner { String run(List<String> command) throws IOException, InterruptedException; }
    private record Capability(boolean writable, String error, long checkedAt) { }
    private record Recovery(String vendor, int watts) { }
    private record AmdDescriptor(int index, String selector) { }

    private final ObjectMapper json;
    private final Path limitsFile;
    private final Path recoveryFile;
    private final Path drmRoot;
    private final CommandRunner commands;
    private volatile List<Gpu> cached = List.of();
    private volatile long discoveredAt;
    private volatile Map<String, UserLimits> userLimits;
    private volatile Map<String, Integer> applied = Map.of();
    private final Map<String, Integer> originalLimits = new LinkedHashMap<>();
    private final Map<String, Capability> capabilities = new HashMap<>();
    private final Map<String, Recovery> recovery;
    /** Only entries loaded at process start are eligible for automatic crash recovery. */
    private final Map<String, Recovery> startupRecovery;
    private final Map<String, Path> amdSysfsPowerCaps = new HashMap<>();
    private final Map<String, Path> amdSysfsTemperatures = new HashMap<>();

    public record Gpu(String vendor, int index, String deviceId, String model, int driverMinWatts, int driverMaxWatts,
                      int minWatts, int maxWatts, Integer currentPowerLimitWatts, Double currentWatts,
                      String usageMeasurement, boolean supportsDynamicPowerScaling, String regulationError) { }
    public record UserLimits(int minimumWatts, int maximumWatts) { }

    @Autowired
    public LocalGpuPowerService(ObjectMapper json, @Value("${solarminer.agent.gpu-power-settings-file:./solarminer-agent/gpu-power-limits.json}") String path) {
        this(json, Path.of(path).toAbsolutePath().normalize(), null, Path.of("/sys/class/drm"));
    }

    LocalGpuPowerService(ObjectMapper json, Path path, CommandRunner runner) {
        this(json, path, runner, Path.of("/sys/class/drm"));
    }

    LocalGpuPowerService(ObjectMapper json, Path path, CommandRunner runner, Path drmRoot) {
        this.json = json;
        this.limitsFile = path.toAbsolutePath().normalize();
        this.recoveryFile = limitsFile.resolveSibling("gpu-power-recovery.json");
        this.drmRoot = drmRoot;
        this.commands = runner == null ? this::runProcess : runner;
        this.userLimits = load(limitsFile, new TypeReference<Map<String, UserLimits>>() { });
        this.recovery = new LinkedHashMap<>(load(recoveryFile, new TypeReference<Map<String, Recovery>>() { }));
        this.startupRecovery = new LinkedHashMap<>(recovery);
    }

    public List<Gpu> discover() {
        if (System.nanoTime() - discoveredAt < DISCOVERY_CACHE) return cached;
        synchronized (this) {
            if (System.nanoTime() - discoveredAt >= DISCOVERY_CACHE) {
                cached = detect();
                discoveredAt = System.nanoTime();
            }
            return cached;
        }
    }

    /** Bypasses the short telemetry cache for safety-sensitive snapshots. */
    public synchronized List<Gpu> refresh() {
        discoveredAt = 0;
        return discover();
    }

    private List<Gpu> detect() {
        List<Gpu> cards = new ArrayList<>();
        detectNvidia(cards);
        int beforeAmd = cards.size();
        detectAmd(cards);
        boolean amdSmiReadable = cards.subList(beforeAmd, cards.size()).stream()
                .anyMatch(gpu -> gpu.driverMinWatts() > 0 && gpu.driverMaxWatts() >= gpu.driverMinWatts()
                        && gpu.currentPowerLimitWatts() != null);
        if (!amdSmiReadable) {
            cards.subList(beforeAmd, cards.size()).clear();
            detectAmdSysfs(cards);
        }
        if (recoverPending(cards)) return detect();
        if (!startupRecovery.isEmpty()) {
            cards.replaceAll(gpu -> startupRecovery.containsKey(gpu.deviceId())
                    ? new Gpu(gpu.vendor(), gpu.index(), gpu.deviceId(), gpu.model(), gpu.driverMinWatts(), gpu.driverMaxWatts(),
                    gpu.minWatts(), gpu.maxWatts(), gpu.currentPowerLimitWatts(), gpu.currentWatts(), gpu.usageMeasurement(), false,
                    "Gespeicherter Power-Cap konnte nach dem letzten Abbruch noch nicht wiederhergestellt werden")
                    : gpu);
        }
        return List.copyOf(cards);
    }

    private void detectNvidia(List<Gpu> cards) {
        try {
            String output = commands.run(List.of("nvidia-smi", "--query-gpu=index,uuid,name,power.min_limit,power.max_limit,power.limit,power.draw", "--format=csv,noheader,nounits"));
            for (String line : output.lines().toList()) {
                String[] c = line.split(",", -1);
                if (c.length != 7) continue;
                try {
                    addCard(cards, "NVIDIA", Integer.parseInt(c[0].trim()), c[1].trim(), c[2].trim(),
                            watts(c[3]), watts(c[4]), optionalWatts(c[5]), decimal(c[6]));
                } catch (RuntimeException ignored) { }
            }
        } catch (IOException | InterruptedException e) { interrupted(e); }
    }

    private void detectAmd(List<Gpu> cards) {
        try {
            JsonNode list = json.readTree(commands.run(List.of("amd-smi", "list", "--json")));
            for (AmdDescriptor descriptor : amdDescriptors(list)) {
                JsonNode stat = json.readTree(commands.run(List.of("amd-smi", "static", "--gpu", descriptor.selector(), "--asic", "--limit", "--json")));
                JsonNode metric = json.readTree(commands.run(List.of("amd-smi", "metric", "--gpu", descriptor.selector(), "--power", "--json")));
                Map<String, String> values = flattened(stat, metric);
                Integer min = wattsValue(first(values, "MIN_POWER_CAP", "POWER_CAP_MIN", "MIN_POWER_LIMIT", "MIN_POWER"));
                Integer max = wattsValue(first(values, "MAX_POWER_CAP", "POWER_CAP_MAX", "MAX_POWER_LIMIT", "MAX_POWER"));
                Integer current = wattsValue(first(values, "CURRENT_POWER_CAP", "POWER_CAP", "POWER_LIMIT"));
                Double draw = decimal(first(values, "CURRENT_SOCKET_POWER", "AVERAGE_SOCKET_POWER", "SOCKET_POWER", "CURRENT_POWER", "POWER_USAGE"));
                String model = first(values, "MARKET_NAME", "PRODUCT_NAME", "ASIC_NAME");
                addCard(cards, "AMD", descriptor.index(), descriptor.selector(), model == null ? "AMD GPU" : model,
                        valueOrZero(min), valueOrZero(max), current, draw);
            }
        } catch (IOException | InterruptedException | RuntimeException e) { interrupted(e); }
    }

    /** Linux Radeon fallback using the kernel's standard hwmon power-cap files. */
    private void detectAmdSysfs(List<Gpu> cards) {
        if (!Files.isDirectory(drmRoot)) return;
        try (var entries = Files.list(drmRoot)) {
            for (Path card : entries.filter(path -> path.getFileName().toString().matches("card\\d+"))
                    .sorted().toList()) {
                Path device = card.resolve("device");
                if (!"0x1002".equalsIgnoreCase(readText(device.resolve("vendor")))) continue;
                Path hwmonRoot = device.resolve("hwmon");
                if (!Files.isDirectory(hwmonRoot)) continue;
                try (var hwmons = Files.list(hwmonRoot)) {
                    Path hwmon = hwmons.filter(Files::isDirectory)
                            .filter(path -> Files.isRegularFile(path.resolve("power1_cap")))
                            .findFirst().orElse(null);
                    if (hwmon == null) continue;
                    Integer min = microValue(hwmon.resolve("power1_cap_min"), false);
                    Integer max = microValue(hwmon.resolve("power1_cap_max"), false);
                    Integer current = microValue(hwmon.resolve("power1_cap"), false);
                    if (min == null || max == null || current == null) continue;
                    String id;
                    try { id = device.toRealPath().getFileName().toString(); }
                    catch (IOException ignored) { id = card.getFileName().toString(); }
                    int index = Integer.parseInt(card.getFileName().toString().substring(4));
                    Path temperature = hwmon.resolve("temp1_input");
                    amdSysfsPowerCaps.put(id, hwmon.resolve("power1_cap"));
                    if (Files.isRegularFile(temperature)) amdSysfsTemperatures.put(id, temperature);
                    Double draw = microDouble(hwmon.resolve("power1_average"));
                    if (draw == null) draw = microDouble(hwmon.resolve("power1_input"));
                    String model = readText(device.resolve("product_name"));
                    addCard(cards, "AMD", index, id, model == null || model.isBlank() ? "AMD GPU " + id : model,
                            min, max, current, draw);
                }
            }
        } catch (IOException | RuntimeException ignored) { }
    }

    private void addCard(List<Gpu> cards, String vendor, int index, String id, String model,
                         int driverMin, int driverMax, Integer current, Double draw) {
        boolean readable = !id.isBlank() && driverMin > 0 && driverMax >= driverMin && current != null;
        UserLimits saved = userLimits.get(id);
        int min = readable ? Math.max(driverMin, saved == null ? driverMin : saved.minimumWatts()) : 0;
        int max = readable ? Math.min(driverMax, saved == null ? driverMax : saved.maximumWatts()) : 0;
        boolean valid = readable && min <= max;
        Capability capability = valid ? writableCapability(vendor, id, current) : null;
        cards.add(new Gpu(vendor, index, id, model, driverMin, driverMax, min, max, current, draw,
                draw == null ? "unavailable" : "measured", valid && capability.writable(),
                valid ? capability.error() : "Treibergrenzen oder Nutzergrenzen nicht sicher verifizierbar"));
    }

    /** Same-value write: verifies permissions/capability without increasing consumption. */
    private Capability writableCapability(String vendor, String id, int current) {
        Capability known = capabilities.get(id);
        long now = System.nanoTime();
        if (known != null && now - known.checkedAt() < CAPABILITY_CACHE) return known;
        try {
            writeLimit(vendor, id, current);
            Integer actual = readLimit(vendor, id);
            if (actual == null || Math.abs(actual - current) > 1) throw new IOException("Treiber-Readback stimmt nicht überein");
            known = new Capability(true, null, now);
        } catch (IOException | InterruptedException e) {
            interrupted(e);
            known = new Capability(false, "Power-Cap nicht schreibbar (Administrator/root und Treiber prüfen): "
                    + Objects.toString(e.getMessage(), "unbekannter Fehler"), now);
        }
        capabilities.put(id, known);
        return known;
    }

    public synchronized boolean setUserLimits(String id, int minimumWatts, int maximumWatts) {
        Gpu gpu = discover().stream().filter(g -> g.deviceId().equals(id)).findFirst().orElse(null);
        if (gpu == null || !gpu.supportsDynamicPowerScaling() || minimumWatts < gpu.driverMinWatts()
                || maximumWatts > gpu.driverMaxWatts() || minimumWatts > maximumWatts) return false;
        Map<String, UserLimits> next = new HashMap<>(userLimits);
        next.put(id, new UserLimits(minimumWatts, maximumWatts));
        if (!save(limitsFile, next)) return false;
        userLimits = Map.copyOf(next);
        discoveredAt = 0;
        return true;
    }

    public int appliedTarget(Gpu gpu) {
        return applied.getOrDefault(gpu.deviceId(), gpu.currentPowerLimitWatts() == null ? 0 : gpu.currentPowerLimitWatts());
    }

    /** Applies a multi-GPU transaction; every modified card is rolled back from its live snapshot on failure. */
    public synchronized boolean setTotalPowerTarget(long watts, List<Gpu> requested) {
        discoveredAt = 0;
        Map<String, Gpu> now = new HashMap<>();
        discover().forEach(g -> now.put(g.deviceId(), g));
        List<Gpu> cards = requested.stream().map(g -> now.get(g.deviceId())).filter(Objects::nonNull).toList();
        if (cards.size() != requested.size() || cards.isEmpty()
                || cards.stream().map(Gpu::deviceId).distinct().count() != cards.size()
                || cards.stream().anyMatch(g -> !g.supportsDynamicPowerScaling())) return false;
        long min = cards.stream().mapToLong(Gpu::minWatts).sum(), max = cards.stream().mapToLong(Gpu::maxWatts).sum();
        if (watts < min || watts > max) return false;
        List<Integer> targets = distribute(watts, cards, min, max);
        Map<String, Integer> before = new LinkedHashMap<>();
        for (Gpu card : cards) {
            if (card.currentPowerLimitWatts() == null) return false;
            before.put(card.deviceId(), card.currentPowerLimitWatts());
        }
        if (!rememberOriginals(cards, before)) return false;
        try {
            for (int i = 0; i < cards.size(); i++) setAndVerify(cards.get(i), targets.get(i));
            Map<String, Integer> next = new HashMap<>(applied);
            for (int i = 0; i < cards.size(); i++) next.put(cards.get(i).deviceId(), targets.get(i));
            applied = Map.copyOf(next);
            discoveredAt = 0;
            return true;
        } catch (IOException | InterruptedException e) {
            interrupted(e);
            if (!restore(cards, before)) {
                for (Gpu card : cards)
                    startupRecovery.put(card.deviceId(), new Recovery(card.vendor(), before.get(card.deviceId())));
            }
            discoveredAt = 0;
            return false;
        }
    }

    private boolean rememberOriginals(List<Gpu> cards, Map<String, Integer> before) {
        boolean changed = false;
        List<String> added = new ArrayList<>();
        for (Gpu card : cards) if (!originalLimits.containsKey(card.deviceId())) {
            int value = before.get(card.deviceId());
            originalLimits.put(card.deviceId(), value);
            recovery.put(card.deviceId(), new Recovery(card.vendor(), value));
            added.add(card.deviceId());
            changed = true;
        }
        if (!changed || save(recoveryFile, recovery)) return true;
        added.forEach(id -> { originalLimits.remove(id); recovery.remove(id); });
        return false;
    }

    static List<Integer> distribute(long watts, List<Gpu> cards, long min, long max) {
        long remaining = watts - min, headroom = Math.max(1, max - min);
        List<Integer> targets = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            Gpu gpu = cards.get(i);
            int target = i == cards.size() - 1 ? (int) (gpu.minWatts() + remaining)
                    : (int) (gpu.minWatts() + (watts - min) * (gpu.maxWatts() - gpu.minWatts()) / headroom);
            target = Math.max(gpu.minWatts(), Math.min(gpu.maxWatts(), target));
            remaining -= target - gpu.minWatts();
            targets.add(target);
        }
        return targets;
    }

    private void setAndVerify(Gpu gpu, int target) throws IOException, InterruptedException {
        writeLimit(gpu.vendor(), gpu.deviceId(), target);
        Integer actual = readLimit(gpu.vendor(), gpu.deviceId());
        if (actual == null || actual < gpu.driverMinWatts() || actual > gpu.driverMaxWatts() || Math.abs(actual - target) > 1)
            throw new IOException("Treiber bestätigte den Power-Cap nicht");
    }

    private void writeLimit(String vendor, String id, int target) throws IOException, InterruptedException {
        if ("NVIDIA".equals(vendor)) commands.run(List.of("nvidia-smi", "-i", id, "-pl", Integer.toString(target)));
        else if ("AMD".equals(vendor) && amdSysfsPowerCaps.containsKey(id))
            Files.writeString(amdSysfsPowerCaps.get(id), Long.toString(target * 1_000_000L), StandardCharsets.US_ASCII);
        else if ("AMD".equals(vendor)) commands.run(List.of("amd-smi", "set", "--gpu", id, "--power-cap", Integer.toString(target)));
        else throw new IOException("Nicht unterstützter GPU-Hersteller: " + vendor);
    }

    private Integer readLimit(String vendor, String id) throws IOException, InterruptedException {
        if ("NVIDIA".equals(vendor)) return optionalWatts(commands.run(List.of("nvidia-smi", "-i", id,
                "--query-gpu=power.limit", "--format=csv,noheader,nounits")).strip());
        if (amdSysfsPowerCaps.containsKey(id)) return microValue(amdSysfsPowerCaps.get(id), false);
        JsonNode node = json.readTree(commands.run(List.of("amd-smi", "static", "--gpu", id, "--limit", "--json")));
        return wattsValue(first(flattened(node), "CURRENT_POWER_CAP", "POWER_CAP", "POWER_LIMIT"));
    }

    private boolean restore(List<Gpu> cards, Map<String, Integer> before) {
        boolean success = true;
        for (Gpu gpu : cards) {
            Integer target = before.get(gpu.deviceId());
            if (target == null) continue;
            if (target < gpu.driverMinWatts() || target > gpu.driverMaxWatts()) {
                success = false;
                continue;
            }
            try {
                writeLimit(gpu.vendor(), gpu.deviceId(), target);
                Integer actual = readLimit(gpu.vendor(), gpu.deviceId());
                success = actual != null && Math.abs(actual - target) <= 1 && success;
            } catch (Exception ignored) { success = false; }
        }
        return success;
    }

    public synchronized boolean restorePowerLimits(Map<String, Integer> snapshot) {
        discoveredAt = 0;
        Map<String, Gpu> byId = new HashMap<>();
        discover().forEach(g -> byId.put(g.deviceId(), g));
        List<Gpu> cards = snapshot.keySet().stream().map(byId::get).filter(Objects::nonNull).toList();
        boolean success = cards.size() == snapshot.size() && restore(cards, snapshot);
        discoveredAt = 0;
        return success;
    }

    private boolean recoverPending(List<Gpu> cards) {
        if (startupRecovery.isEmpty()) return false;
        Map<String, Gpu> byId = new HashMap<>();
        cards.forEach(g -> byId.put(g.deviceId(), g));
        boolean changed = false;
        for (Iterator<Map.Entry<String, Recovery>> it = startupRecovery.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Recovery> entry = it.next();
            Gpu gpu = byId.get(entry.getKey());
            if (gpu == null || !gpu.vendor().equals(entry.getValue().vendor())
                    || entry.getValue().watts() < gpu.driverMinWatts()
                    || entry.getValue().watts() > gpu.driverMaxWatts()) continue;
            try {
                writeLimit(gpu.vendor(), entry.getKey(), entry.getValue().watts());
                Integer actual = readLimit(gpu.vendor(), entry.getKey());
                if (actual != null && Math.abs(actual - entry.getValue().watts()) <= 1) {
                    recovery.remove(entry.getKey());
                    it.remove();
                    changed = true;
                }
            } catch (Exception ignored) { }
        }
        if (changed) save(recoveryFile, recovery);
        return changed;
    }

    @PreDestroy
    public synchronized void restoreOriginalLimits() {
        Map<String, Gpu> byId = new HashMap<>();
        cached.forEach(g -> byId.put(g.deviceId(), g));
        Map<String, Integer> targets = new LinkedHashMap<>();
        recovery.forEach((id, value) -> targets.put(id, value.watts()));
        List<Gpu> cards = targets.keySet().stream().map(byId::get).filter(Objects::nonNull).toList();
        if (cards.size() == targets.size() && restore(cards, targets)) {
            recovery.clear();
            startupRecovery.clear();
            try { Files.deleteIfExists(recoveryFile); } catch (IOException ignored) { }
        }
    }

    public Double readTemperatureC(Gpu gpu) {
        try {
            Double temperature;
            if ("NVIDIA".equals(gpu.vendor())) temperature = decimal(commands.run(List.of("nvidia-smi", "-i", gpu.deviceId(),
                    "--query-gpu=temperature.gpu", "--format=csv,noheader,nounits")));
            else if (amdSysfsTemperatures.containsKey(gpu.deviceId())) {
                Long milli = longValue(readText(amdSysfsTemperatures.get(gpu.deviceId())));
                temperature = milli == null ? null : milli / 1_000d;
            } else {
                JsonNode node = json.readTree(commands.run(List.of("amd-smi", "metric", "--gpu", gpu.deviceId(), "--temperature", "--json")));
                temperature = decimal(first(flattened(node), "HOTSPOT_TEMPERATURE", "TEMPERATURE_HOTSPOT",
                        "EDGE_TEMPERATURE", "TEMPERATURE_EDGE", "TEMPERATURE"));
            }
            return temperature != null && temperature >= -20 && temperature <= 150 ? temperature : null;
        } catch (IOException | InterruptedException e) { interrupted(e); return null; }
    }

    private <T> Map<String, T> load(Path path, TypeReference<Map<String, T>> type) {
        try { return Map.copyOf(json.readValue(Files.readString(path), type)); }
        catch (Exception ignored) { return Map.of(); }
    }

    private boolean save(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                json.writeValue(tmp.toFile(), value);
                try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(tmp); }
            return true;
        } catch (IOException e) { return false; }
    }

    private static List<AmdDescriptor> amdDescriptors(JsonNode root) {
        List<AmdDescriptor> found = new ArrayList<>();
        collectAmdDescriptors(root, found);
        Map<String, AmdDescriptor> unique = new LinkedHashMap<>();
        found.forEach(value -> unique.putIfAbsent(value.selector(), value));
        return List.copyOf(unique.values());
    }

    private static void collectAmdDescriptors(JsonNode node, List<AmdDescriptor> found) {
        if (node == null) return;
        if (node.isObject()) {
            Map<String, String> values = flattened(node);
            Integer index = integer(first(values, "GPU", "GPU_ID", "GPU_INDEX", "ID"));
            // Prefer PCI BDF: it is accepted by AMD-SMI and also maps unambiguously to SRBMiner.
            String selector = first(values, "BDF", "UUID");
            if (index != null && selector != null) found.add(new AmdDescriptor(index, selector));
            node.elements().forEachRemaining(child -> collectAmdDescriptors(child, found));
        } else if (node.isArray()) node.elements().forEachRemaining(child -> collectAmdDescriptors(child, found));
    }

    private static Map<String, String> flattened(JsonNode... roots) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode root : roots) flatten(root, values);
        return values;
    }

    private static void flatten(JsonNode node, Map<String, String> values) {
        if (node == null) return;
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isValueNode()) values.putIfAbsent(normalize(entry.getKey()), entry.getValue().asText());
            else flatten(entry.getValue(), values);
        });
        else if (node.isArray()) node.elements().forEachRemaining(child -> flatten(child, values));
    }

    private static String normalize(String key) {
        return key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(normalize(key));
            if (value != null && !value.isBlank() && !"N/A".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static int watts(String value) {
        Integer parsed = wattsValue(value);
        if (parsed == null) throw new NumberFormatException(value);
        return parsed;
    }

    private static Integer optionalWatts(String value) { return wattsValue(value); }

    private static Integer wattsValue(String value) {
        Double parsed = decimal(value);
        if (parsed == null) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("uw") || lower.contains("µw")) parsed /= 1_000_000d;
        else if (lower.contains("mw")) parsed /= 1_000d;
        else if (!lower.contains("w") && parsed > 100_000d) parsed /= 1_000_000d;
        return parsed >= 0 && parsed <= 10_000 ? (int) Math.round(parsed) : null;
    }

    private static Double decimal(String value) {
        if (value == null) return null;
        try {
            String number = value.trim().replace(',', '.').replaceFirst("^.*?(-?[0-9]+(?:\\.[0-9]+)?).*$", "$1");
            double parsed = Double.parseDouble(number);
            return Double.isFinite(parsed) && parsed >= 0 ? parsed : null;
        } catch (Exception ignored) { return null; }
    }

    private static Integer integer(String value) { Double parsed = decimal(value); return parsed == null ? null : parsed.intValue(); }
    private static int valueOrZero(Integer value) { return value == null ? 0 : value; }
    private static void interrupted(Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); }

    private static String readText(Path path) {
        try { return Files.readString(path, StandardCharsets.US_ASCII).trim(); }
        catch (IOException ignored) { return null; }
    }

    private static Long longValue(String value) {
        try { return value == null ? null : Long.parseLong(value.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Integer microValue(Path path, boolean milli) {
        Long value = longValue(readText(path));
        if (value == null || value < 0) return null;
        long divisor = milli ? 1_000L : 1_000_000L;
        return (int) Math.round((double) value / divisor);
    }

    private static Double microDouble(Path path) {
        Long value = longValue(readText(path));
        return value == null || value < 0 ? null : value / 1_000_000d;
    }

    private String runProcess(List<String> command) throws IOException, InterruptedException {
        Path output = Files.createTempFile("solarminer-gpu-", ".log");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(Duration.ofSeconds(8).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("GPU-Befehl hat das Zeitlimit überschritten");
            }
            if (Files.size(output) > 32768) throw new IOException("GPU-Tool-Ausgabe überschreitet 32 KiB");
            String text = Files.readString(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException("GPU-Tool fehlgeschlagen: " + text.strip());
            return text;
        } finally { Files.deleteIfExists(output); }
    }
}
