package de.verdox.solarminer.pcagent.lowlevel.sensor;

import com.sun.management.OperatingSystemMXBean;
import de.verdox.solarminer.pcagent.lowlevel.HardwareIdentityService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Collects a local, privacy-minimized snapshot for the PC-Agent telemetry API. */
@Service
public class HardwareTelemetryService {
    private static final Path HWMON = Path.of("/sys/class/hwmon");

    private final HardwareSensorReader sensorReader;
    private final WindowsLhmBootstrapService lhmBootstrap;
    private final HardwareIdentityService hardwareIdentity;
    private final LocalGpuPowerService gpuPowerService;
    private volatile Snapshot cached;
    private volatile long lastCollectedNanos;

    public HardwareTelemetryService(HardwareSensorReader sensorReader, WindowsLhmBootstrapService lhmBootstrap,
                                    HardwareIdentityService hardwareIdentity, LocalGpuPowerService gpuPowerService) {
        this.sensorReader = sensorReader;
        this.lhmBootstrap = lhmBootstrap;
        this.hardwareIdentity = hardwareIdentity;
        this.gpuPowerService = gpuPowerService;
    }

    public Snapshot snapshot() {
        long now = System.nanoTime();
        Snapshot current = cached;
        if (current != null && now - lastCollectedNanos < TimeUnit.SECONDS.toNanos(1)) return current;
        synchronized (this) {
            now = System.nanoTime();
            if (cached != null && now - lastCollectedNanos < TimeUnit.SECONDS.toNanos(1)) return cached;
            cached = collect();
            lastCollectedNanos = System.nanoTime();
            return cached;
        }
    }

    private Snapshot collect() {
        Map<String, Metric> metrics = new LinkedHashMap<>();
        add(metrics, "cpu.temperature", sensorReader.getCpuTemperatureCelsius(), "°C", cpuTemperatureSource(),
                sensorReader.isAccurate());
        add(metrics, "cpu.package_power", sensorReader.getCpuPowerWatts(), "W", cpuPowerSource(),
                sensorReader.isAccurate());
        if (sensorReader instanceof WindowsLhmSensorReader windowsReader) {
            for (WindowsLhmSensorReader.SensorReading reading : windowsReader.getAvailableSensors())
                add(metrics, reading.key(), reading.value(), reading.unit(), "LibreHardwareMonitor", true);
        }
        List<LocalGpuPowerService.Gpu> gpus = gpuPowerService.discover();

        long committedMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        // JVM memory is not host memory; report it with explicit JVM names so consumers
        // cannot mistake it for total physical RAM.
        add(metrics, "agent.jvm.memory.used", committedMemory - freeMemory, "B", "JVM", true);
        add(metrics, "agent.jvm.memory.committed", committedMemory, "B", "JVM", true);
        add(metrics, "agent.jvm.memory.max", Runtime.getRuntime().maxMemory(), "B", "JVM", true);

        if (isLinux()) addLinuxHwmon(metrics);
        if (isLinux()) addLinuxGpuTemperatures(metrics);
        double cpuWatts = sensorReader.getCpuPowerWatts();
        double gpuWatts = gpuPowerTotal(metrics, gpus);
        int powerComponents = (Double.isFinite(cpuWatts) && cpuWatts > 0 ? 1 : 0)
                + (Double.isFinite(gpuWatts) && gpuWatts > 0 ? 1 : 0);
        double totalWatts = (Double.isFinite(cpuWatts) && cpuWatts > 0 ? cpuWatts : 0)
                + (Double.isFinite(gpuWatts) && gpuWatts > 0 ? gpuWatts : 0);
        add(metrics, "system.total_power", powerComponents == 0 ? Double.NaN : totalWatts,
                "W", "CPU package + GPU board readings; partial if a device has no power sensor", false);
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        if (os != null) {
            double load = os.getCpuLoad();
            if (Double.isFinite(load) && load >= 0) add(metrics, "system.cpu.load", load * 100.0, "%", "JVM/OS", true);
            long physical = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            if (physical > 0 && free >= 0) {
                add(metrics, "system.memory.used", physical - free, "B", "OS", true);
                add(metrics, "system.memory.total", physical, "B", "OS", true);
                add(metrics, "system.memory.used_percent", (physical - free) * 100.0 / physical, "%", "OS", true);
            }
        }
        Map<String, String> sources = new LinkedHashMap<>();
        if (isLinux()) sources.put("linux-kernel-sensors", "active; reads hwmon, thermal and powercap on demand");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            sources.put("librehardwaremonitor", lhmBootstrap.status() + ": " + lhmBootstrap.detail());
        String lhmStatus = isWindows() ? lhmBootstrap.status() : "not-required";
        String lhmDetail = isWindows() ? lhmBootstrap.detail() : "Linux kernel sensor interfaces are used.";
        return new Snapshot(Instant.now(), System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"), hardwareIdentity.getProcessor(),
                mergeGpuNames(hardwareIdentity.getGraphicsCards(), gpus), gpus,
                metrics, sources, lhmStatus, lhmDetail);
    }

    private static List<String> mergeGpuNames(List<String> oshiNames, List<LocalGpuPowerService.Gpu> detected) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(oshiNames);
        for (LocalGpuPowerService.Gpu gpu : detected) {
            if (names.stream().noneMatch(name -> name.equalsIgnoreCase(gpu.model())
                    || name.toLowerCase(Locale.ROOT).contains(gpu.model().toLowerCase(Locale.ROOT))))
                names.add(gpu.model());
        }
        return List.copyOf(names);
    }

    private double gpuPowerTotal(Map<String, Metric> metrics, List<LocalGpuPowerService.Gpu> gpus) {
        double nvidiaSmiTotal = gpus.stream().map(LocalGpuPowerService.Gpu::currentWatts)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).filter(v -> v > 0).sum();
        Map<String, Double> deviceReadings = new LinkedHashMap<>();
        if (sensorReader instanceof WindowsLhmSensorReader windowsReader) {
            for (WindowsLhmSensorReader.SensorReading reading : windowsReader.getAvailableSensors()) {
                if ("W".equals(reading.unit()) && isGpuName(reading.hardwareName()) && reading.value() > 0)
                    deviceReadings.merge(reading.hardwareName(), reading.value(), Math::max);
            }
        }
        for (Map.Entry<String, Metric> entry : metrics.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            Metric metric = entry.getValue();
            if (key.startsWith("hwmon.") && key.endsWith(".power") && isGpuName(key)
                    && metric.available() && metric.value() != null && metric.value() > 0) {
                String[] parts = key.split("\\.");
                String device = parts.length > 1 ? parts[0] + "." + parts[1] : key;
                deviceReadings.merge(device, metric.value(), Math::max);
            }
        }
        double sensorTotal = deviceReadings.values().stream().mapToDouble(Double::doubleValue).sum();
        return Math.max(nvidiaSmiTotal, sensorTotal);
    }

    private static boolean isGpuName(String text) {
        String name = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return name.contains("gpu") || name.contains("nvidia") || name.contains("geforce")
                || name.contains("radeon") || name.contains("amdgpu") || name.contains("graphics")
                || name.contains("nouveau");
    }

    private void addLinuxHwmon(Map<String, Metric> metrics) {
        try (Stream<Path> devices = Files.list(HWMON)) {
            List<Path> sensors = devices.filter(Files::isDirectory).flatMap(device -> {
                try (Stream<Path> files = Files.list(device)) {
                    return files.filter(path -> path.getFileName().toString()
                            .matches("(?:temp\\d+_input|power\\d+_input|energy\\d+_input|fan\\d+_input|in\\d+_input|curr\\d+_input)")).toList().stream();
                } catch (Exception ignored) { return Stream.empty(); }
            }).sorted().toList();
            Map<String, Integer> duplicateNames = new LinkedHashMap<>();
            for (Path input : sensors) {
                String filename = input.getFileName().toString();
                String chip = readText(input.getParent().resolve("name"), "hwmon");
                String label = sensorLabel(input, filename);
                String metricName;
                String unit;
                double scale;
                if (filename.startsWith("temp")) {
                    metricName = "temperature";
                    unit = "°C";
                    scale = 0.001;
                } else if (filename.startsWith("power")) {
                    metricName = "power";
                    unit = "W";
                    scale = 0.000001;
                } else if (filename.startsWith("energy")) {
                    metricName = "energy";
                    unit = "J";
                    scale = 0.000001;
                } else if (filename.startsWith("in")) {
                    metricName = "voltage";
                    unit = "V";
                    scale = 0.001;
                } else if (filename.startsWith("curr")) {
                    metricName = "current";
                    unit = "A";
                    scale = 0.001;
                } else {
                    metricName = "fan_speed";
                    unit = "rpm";
                    scale = 1.0;
                }
                String base = "hwmon." + safe(chip) + "." + safe(label) + "." + metricName;
                int count = duplicateNames.merge(base, 1, Integer::sum);
                String key = count == 1 ? base : base + "." + count;
                try {
                    double raw = Double.parseDouble(Files.readString(input).trim());
                    double value = raw * scale;
                    if (Double.isFinite(value)) add(metrics, key, value, unit, "Linux hwmon", true);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
            // Some containers expose no host hwmon devices or deny sensor permissions.
        }
    }

    /** Reads GPU temperatures from driver interfaces that are not exposed as generic hwmon sensors. */
    private void addLinuxGpuTemperatures(Map<String, Metric> metrics) {
        addNvidiaGpuTemperatures(metrics);
        addAmdGpuTemperatures(metrics);
    }

    private void addNvidiaGpuTemperatures(Map<String, Metric> metrics) {
        try {
            Process process = new ProcessBuilder("nvidia-smi", "--query-gpu=index,temperature.gpu",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            if (!process.waitFor(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return;
            }
            if (process.exitValue() != 0) return;
            try (var output = process.getInputStream()) {
                for (String line : new String(output.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
                    String[] columns = line.split(",", -1);
                    if (columns.length != 2) continue;
                    try {
                        int index = Integer.parseInt(columns[0].trim());
                        double temperature = Double.parseDouble(columns[1].trim());
                        if (index >= 0 && temperature >= -20 && temperature <= 150)
                            add(metrics, "gpu.nvidia." + index + ".temperature", temperature, "°C", "nvidia-smi", true);
                    } catch (NumberFormatException ignored) { }
                }
            }
        } catch (Exception ignored) { }
    }

    /** AMD exposes edge/junction readings under each DRM card's hwmon directory. */
    private void addAmdGpuTemperatures(Map<String, Metric> metrics) {
        Path drm = Path.of("/sys/class/drm");
        try (Stream<Path> cards = Files.list(drm)) {
            for (Path card : cards.filter(path -> path.getFileName().toString().matches("card\\d+")).toList()) {
                String cardName = card.getFileName().toString();
                Path hwmon = card.resolve("device/hwmon");
                try (Stream<Path> devices = Files.list(hwmon)) {
                    for (Path device : devices.toList()) {
                        try (Stream<Path> inputs = Files.list(device)) {
                            for (Path input : inputs.filter(path -> path.getFileName().toString().matches("temp\\d+_input")).toList()) {
                                Double value = readHwmonTemperature(input);
                                if (value == null) continue;
                                String filename = input.getFileName().toString();
                                String number = filename.substring(4, filename.indexOf('_'));
                                String label = readText(input.resolveSibling("temp" + number + "_label"), "edge");
                                add(metrics, "gpu.amd." + cardName + "." + safe(label) + ".temperature",
                                        value, "°C", "Linux DRM hwmon", true);
                            }
                        } catch (Exception ignored) { }
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private static Double readHwmonTemperature(Path path) {
        try {
            double raw = Double.parseDouble(Files.readString(path).trim());
            double celsius = raw > 1000 ? raw / 1000.0 : raw;
            return Double.isFinite(celsius) && celsius >= -20 && celsius <= 150 ? celsius : null;
        } catch (Exception ignored) { return null; }
    }

    private static String sensorLabel(Path input, String filename) {
        int underscore = filename.indexOf('_');
        String prefix = filename.substring(0, underscore);
        String index = prefix.replaceAll("\\D", "");
        String label = readText(input.resolveSibling(prefix + index + "_label"), "");
        return label.isBlank() ? prefix + index : label;
    }

    private static String readText(Path path, String fallback) {
        try { return Files.readString(path).trim(); }
        catch (Exception ignored) { return fallback; }
    }

    private static String safe(String text) {
        String value = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return value.isBlank() ? "unknown" : value;
    }

    private static void add(Map<String, Metric> metrics, String key, double value, String unit,
                            String source, boolean accurate) {
        if (Double.isFinite(value) && value >= 0) metrics.put(key, new Metric(value, unit, source, true, accurate));
        else metrics.put(key, new Metric(null, unit, source, false, accurate));
    }

    private static String cpuTemperatureSource() { return isLinux() ? "Linux hwmon/thermal" : "LibreHardwareMonitor"; }
    private static String cpuPowerSource() { return isLinux() ? "Linux powercap/RAPL" : "LibreHardwareMonitor"; }
    private static boolean isLinux() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"); }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    public record Metric(Double value, String unit, String source, boolean available, boolean directMeasurement) { }
    public record Snapshot(Instant collectedAt, String platform, String architecture, String cpuName,
                           List<String> gpuNames, List<LocalGpuPowerService.Gpu> gpus, Map<String, Metric> metrics,
                           Map<String, String> sources, String sensorServiceStatus, String sensorServiceDetail) { }
}
