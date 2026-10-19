package de.verdox.solarminer.pcagent.lowlevel;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Files;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Bounded GPU clock control for calibration runs. Driver APIs differ by vendor, so
 * support is reported per device and each write is checked by reading driver state back.
 * Voltage is deliberately not exposed: none of the currently supported CLI contracts
 * provides a portable, verifiable voltage control across our supported vendors/OSes.
 */
@Service
public class GpuTuningService {
    private final LocalGpuPowerService power;
    private final ObjectMapper json;
    private final Path recoveryFile;
    private final java.util.Set<String> recoveryPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> memoryTouched = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, OffsetRecovery> offsetRecovery = new java.util.concurrent.ConcurrentHashMap<>();
    private final MemoryOffsetDriver offsetDriver;
    private final java.util.Map<String, Capabilities> capabilityCache = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GpuTuningService(LocalGpuPowerService power, ObjectMapper json,
                            @Value("${solarminer.agent.gpu-tuning-recovery-file:./solarminer-agent/gpu-tuning-recovery.json}") String recoveryPath) {
        this(power, json, recoveryPath, new ToolkitClockDriver());
    }

    GpuTuningService(LocalGpuPowerService power, ObjectMapper json, String recoveryPath, MemoryOffsetDriver offsetDriver) {
        this.power = power;
        this.json = json;
        this.offsetDriver = offsetDriver;
        this.recoveryFile = Path.of(recoveryPath).toAbsolutePath().normalize();
        try {
            if (Files.isRegularFile(recoveryFile)) {
                var marker = json.readTree(Files.readString(recoveryFile));
                if (marker.isArray()) marker.forEach(value -> {
                    recoveryPending.add(value.asText()); memoryTouched.add(value.asText());
                }); // Older markers did not record touched domains; reset both conservatively.
                else if (marker.isObject()) marker.fields().forEachRemaining(entry -> {
                    recoveryPending.add(entry.getKey());
                    if (entry.getValue().isObject()) {
                        if (entry.getValue().path("memoryTouched").asBoolean()) memoryTouched.add(entry.getKey());
                        if (entry.getValue().hasNonNull("offsetPstate") && entry.getValue().hasNonNull("originalOffsetMhz"))
                            offsetRecovery.put(entry.getKey(), new OffsetRecovery(entry.getValue().get("offsetPstate").asInt(),
                                    entry.getValue().get("originalOffsetMhz").asInt()));
                    } else if (entry.getValue().asBoolean()) memoryTouched.add(entry.getKey());
                });
            }
        } catch (Exception ignored) { /* A malformed safety marker is reported as unavailable through capability status. */ }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTuning() {
        for (String id : List.copyOf(recoveryPending)) {
            if (restoreDefaults(id).applied()) clearRecovery(id);
        }
    }

    @PreDestroy
    public void restoreTuningOnShutdown() {
        for (String id : List.copyOf(recoveryPending)) {
            if (restoreDefaults(id).applied()) clearRecovery(id);
        }
    }

    public Capabilities capabilities(String deviceId) {
        return capabilityCache.getOrDefault(deviceId, unavailable(deviceId, "gpu_clock_probe_pending"));
    }

    public boolean hasPendingRecovery(String deviceId) {
        return recoveryPending.contains(deviceId);
    }

    /** Target only the affected NVIDIA card; the driver rejects a reset while other clients use it. */
    public ApplyResult resetUnresponsiveGpu(String deviceId) {
        if (deviceId == null || !deviceId.startsWith("GPU-"))
            return new ApplyResult(false, "gpu_not_found");
        try {
            power.runTuningCommand(List.of("nvidia-smi", "--gpu-reset", "-i", deviceId));
            return new ApplyResult(true, null);
        } catch (Exception | LinkageError failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ApplyResult(false, "auto_tuning_gpu_reset_failed");
        }
    }

    /** Effective clocks under load; a requested lock may be throttled by power or temperature. */
    public ClockTelemetry readEffectiveClocks(String deviceId) {
        LocalGpuPowerService.Gpu gpu = find(deviceId);
        if (gpu == null || !"NVIDIA".equalsIgnoreCase(gpu.vendor())) return null;
        try {
            String output = power.runTuningCommand(List.of("nvidia-smi", "-i", gpu.deviceId(),
                    "--query-gpu=clocks.current.graphics,clocks.current.memory", "--format=csv,noheader,nounits"));
            String[] values = output.strip().split(",");
            if (values.length < 2) return null;
            Integer core = integer(values[0]), memory = integer(values[1]);
            return core == null || memory == null ? null : new ClockTelemetry(core, memory);
        } catch (Exception ignored) { return null; }
    }

    /** The active P-state has its own driver-reported offset range. NVML availability gates support per device. */
    public MemoryOffsetRange memoryOffsetRange(String deviceId) {
        LocalGpuPowerService.Gpu gpu = find(deviceId);
        if (gpu == null || !"NVIDIA".equalsIgnoreCase(gpu.vendor())) return null;
        try {
            String state = power.runTuningCommand(List.of("nvidia-smi", "-i", gpu.deviceId(),
                    "--query-gpu=pstate", "--format=csv,noheader,nounits")).strip();
            if (!state.matches("P[0-9]+")) return null;
            int pstate = Integer.parseInt(state.substring(1));
            return memoryOffsetRangeForPstate(gpu, pstate);
        } catch (Exception | LinkageError ignored) { return null; }
    }

    private MemoryOffsetRange memoryOffsetRangeForPstate(LocalGpuPowerService.Gpu gpu, int pstate) {
        try {
            MemoryOffsetRange reported = offsetDriver.probe(gpu.index(), pstate);
            return reported != null && reported.pstate() == pstate
                    && reported.minMhz() <= reported.currentMhz()
                    && reported.currentMhz() <= reported.maxMhz() ? reported : null;
        } catch (Exception | LinkageError ignored) { return null; }
    }

    public Integer readMemoryOffset(String deviceId, int pstate) {
        LocalGpuPowerService.Gpu gpu = find(deviceId);
        if (gpu == null || !"NVIDIA".equalsIgnoreCase(gpu.vendor())) return null;
        try { return offsetDriver.probe(gpu.index(), pstate).currentMhz(); }
        catch (Exception | LinkageError ignored) { return null; }
    }

    /** Driver I/O used only by background refresh and explicit calibration/write actions. */
    public Capabilities refreshCapabilities(String deviceId) {
        LocalGpuPowerService.Gpu gpu = find(deviceId);
        if (gpu == null) {
            Capabilities missing = new Capabilities(deviceId, false, false, false, null, null,
                    null, null, "gpu_not_found");
            capabilityCache.put(deviceId, missing);
            return missing;
        }
        String vendor = gpu.vendor().toUpperCase(Locale.ROOT);
        if ("NVIDIA".equals(vendor)) {
            try {
                String output = power.runTuningCommand(List.of("nvidia-smi", "-i", gpu.deviceId(),
                        "--query-supported-clocks=memory,graphics", "--format=csv,noheader,nounits"));
                ClockBounds clocks = parseClockBounds(output);
                boolean coreSupported = clocks != null;
                MemoryLockSupport memorySupport = coreSupported ? nvidiaMemoryLockSupport(gpu.deviceId())
                        : MemoryLockSupport.UNAVAILABLE;
                boolean memorySupported = memorySupport != MemoryLockSupport.UNAVAILABLE;
                Capabilities result = new Capabilities(deviceId, coreSupported, memorySupported, false,
                        coreSupported ? clocks.core().min() : null, coreSupported ? clocks.core().max() : null,
                        coreSupported ? clocks.memory().min() : null, coreSupported ? clocks.memory().max() : null,
                        !coreSupported ? "gpu_clock_controls_unavailable"
                                : memorySupport == MemoryLockSupport.PROVISIONAL ? "gpu_memory_clock_lock_requires_write_test" : null,
                        coreSupported ? clocks.pairs() : List.of());
                capabilityCache.put(deviceId, result);
                return result;
            } catch (Exception ex) {
                Capabilities result = unavailable(deviceId, "gpu_clock_controls_unavailable");
                capabilityCache.put(deviceId, result);
                return result;
            }
        }
        if ("AMD".equals(vendor)) {
            try {
                String output = power.runTuningCommand(List.of("amd-smi", "static", "--gpu", gpu.deviceId(), "--clock", "--json"));
                Bounds gfx = parseNamedBounds(output, "GFXCLK", "SCLK", "SYS");
                Bounds mem = parseNamedBounds(output, "MCLK", "MEMCLK", "MEM");
                if (gfx == null) gfx = parseClockLevels(output, "GFXCLK", "SCLK", "SYS");
                if (mem == null) mem = parseClockLevels(output, "MCLK", "MEMCLK", "MEM");
                boolean supported = gfx != null;
                Capabilities result = new Capabilities(deviceId, supported, mem != null, false,
                        gfx == null ? null : gfx.min(), gfx == null ? null : gfx.max(),
                        mem == null ? null : mem.min(), mem == null ? null : mem.max(),
                        supported ? null : "gpu_clock_controls_unavailable");
                capabilityCache.put(deviceId, result);
                return result;
            } catch (Exception ex) {
                Capabilities result = unavailable(deviceId, "gpu_clock_controls_unavailable");
                capabilityCache.put(deviceId, result);
                return result;
            }
        }
        if ("INTEL".equals(vendor)) {
            try {
                String output = power.runTuningCommand(List.of("xpu-smi", "config", "-d", gpu.deviceId()));
                Bounds core = parseNamedBounds(output, "frequency range", "frequencyrange", "frequency");
                Capabilities result = new Capabilities(deviceId, core != null, false, false,
                        core == null ? null : core.min(), core == null ? null : core.max(), null, null,
                        core == null ? "gpu_clock_controls_unavailable" : null);
                capabilityCache.put(deviceId, result);
                return result;
            } catch (Exception ex) {
                Capabilities result = unavailable(deviceId, "gpu_clock_controls_unavailable");
                capabilityCache.put(deviceId, result);
                return result;
            }
        }
        Capabilities result = unavailable(deviceId, "gpu_vendor_unsupported");
        capabilityCache.put(deviceId, result);
        return result;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmCapabilities() { Thread.startVirtualThread(this::refreshAllCapabilities); }

    @Scheduled(fixedDelayString = "${solarminer.agent.gpu-tuning-capability-refresh-ms:60000}",
            initialDelayString = "${solarminer.agent.gpu-tuning-capability-initial-delay-ms:10000}")
    public void refreshAllCapabilities() {
        List<LocalGpuPowerService.Gpu> cards = power.snapshot();
        java.util.Set<String> ids = cards.stream().map(LocalGpuPowerService.Gpu::deviceId).collect(java.util.stream.Collectors.toSet());
        capabilityCache.keySet().removeIf(id -> !ids.contains(id));
        cards.forEach(gpu -> refreshCapabilities(gpu.deviceId()));
    }

    /** Apply one bounded clock range and reject writes the driver does not confirm. */
    public ApplyResult apply(String deviceId, Integer coreMinMhz, Integer coreMaxMhz,
                             Integer memoryMinMhz, Integer memoryMaxMhz) {
        return apply(deviceId, coreMinMhz, coreMaxMhz, memoryMinMhz, memoryMaxMhz, null, null);
    }

    public ApplyResult apply(String deviceId, Integer coreMinMhz, Integer coreMaxMhz,
                             Integer memoryMinMhz, Integer memoryMaxMhz,
                             Integer memoryOffsetMhz, Integer memoryOffsetPstate) {
        LocalGpuPowerService.Gpu gpu = find(deviceId);
        if (gpu == null) return new ApplyResult(false, "gpu_not_found");
        if (memoryMinMhz != null && "NVIDIA".equalsIgnoreCase(gpu.vendor())
                && gpu.model() != null && gpu.model().matches("(?i).*RTX\\s*20[0-9]{2}.*"))
            return new ApplyResult(false, "gpu_memory_clock_control_unavailable");
        if (memoryOffsetMhz != null && (memoryMinMhz != null || memoryMaxMhz != null || memoryOffsetPstate == null
                || !"NVIDIA".equalsIgnoreCase(gpu.vendor())))
            return new ApplyResult(false, "gpu_tuning_request_invalid");
        MemoryOffsetRange offsetRange = null;
        if (memoryOffsetMhz != null) {
            offsetRange = memoryOffsetRangeForPstate(gpu, memoryOffsetPstate);
            if (offsetRange == null)
                return new ApplyResult(false, "gpu_memory_offset_unavailable");
            if (memoryOffsetMhz < offsetRange.minMhz() || memoryOffsetMhz > offsetRange.maxMhz())
                return new ApplyResult(false, "gpu_clock_target_out_of_range");
        }
        Capabilities caps = refreshCapabilities(deviceId);
        if (!valid(coreMinMhz, coreMaxMhz, caps.coreMinMhz(), caps.coreMaxMhz(), caps.coreClockControl())
                || !valid(memoryMinMhz, memoryMaxMhz, caps.memoryMinMhz(), caps.memoryMaxMhz(), caps.memoryClockControl()))
            return new ApplyResult(false, "gpu_clock_target_out_of_range");
        if (coreMinMhz == null && memoryMinMhz == null && memoryOffsetMhz == null)
            return new ApplyResult(false, "gpu_tuning_request_invalid");
        if (!persistRecovery(deviceId, memoryMinMhz != null,
                offsetRange == null ? null : new OffsetRecovery(offsetRange.pstate(), offsetRange.currentMhz())))
            return new ApplyResult(false, "gpu_tuning_recovery_save_failed");
        try {
            String vendor = gpu.vendor().toUpperCase(Locale.ROOT);
            if ("NVIDIA".equals(vendor)) {
                if (coreMinMhz != null) {
                    boolean writtenByToolkit = false;
                    if (coreMinMhz.equals(coreMaxMhz) && isWindows()) {
                        try {
                            offsetDriver.setCoreTarget(gpu.index(), coreMinMhz);
                            writtenByToolkit = true;
                        } catch (Exception | LinkageError failure) {
                            if (memoryOffsetMhz != null) throw new IOException("GPU toolkit core target failed", failure);
                        }
                    }
                    if (!writtenByToolkit) power.runTuningCommand(List.of("nvidia-smi", "-i", gpu.deviceId(),
                            "--lock-gpu-clocks=" + coreMinMhz + "," + coreMaxMhz));
                }
                if (memoryMinMhz != null) power.runTuningCommand(List.of("nvidia-smi", "-i", gpu.deviceId(),
                        "--lock-memory-clocks=" + memoryMinMhz + "," + memoryMaxMhz));
                if (memoryOffsetMhz != null) {
                    offsetDriver.set(gpu.index(), memoryOffsetPstate, memoryOffsetMhz);
                    Integer observed = readMemoryOffset(deviceId, memoryOffsetPstate);
                    if (!Objects.equals(observed, memoryOffsetMhz))
                        throw new IOException("NVML memory offset readback mismatch");
                }
            } else if ("AMD".equals(vendor)) {
                power.runTuningCommand(List.of("amd-smi", "set", "--gpu", gpu.deviceId(), "--perf-level", "MANUAL"));
                if (coreMinMhz != null) power.runTuningCommand(List.of("amd-smi", "set", "--gpu", gpu.deviceId(), "--clk-limit", "sclk", "min", coreMinMhz.toString()));
                if (coreMaxMhz != null) power.runTuningCommand(List.of("amd-smi", "set", "--gpu", gpu.deviceId(), "--clk-limit", "sclk", "max", coreMaxMhz.toString()));
                if (memoryMinMhz != null) power.runTuningCommand(List.of("amd-smi", "set", "--gpu", gpu.deviceId(), "--clk-limit", "mclk", "min", memoryMinMhz.toString()));
                if (memoryMaxMhz != null) power.runTuningCommand(List.of("amd-smi", "set", "--gpu", gpu.deviceId(), "--clk-limit", "mclk", "max", memoryMaxMhz.toString()));
            } else if ("INTEL".equals(vendor)) {
                if (memoryMinMhz != null) return new ApplyResult(false, "gpu_memory_clock_control_unavailable");
                if (coreMinMhz != null) power.runTuningCommand(List.of("xpu-smi", "config", "-d", gpu.deviceId(),
                        "--frequencyrange", coreMinMhz + "," + coreMaxMhz));
            }
            return new ApplyResult(true, null);
        } catch (Exception | LinkageError ex) {
            String cause = Objects.toString(ex.getMessage(), "").toLowerCase(Locale.ROOT);
            ApplyResult rollback = restoreDefaults(deviceId);
            if (rollback.applied()) clearRecovery(deviceId);
            return new ApplyResult(false, !rollback.applied() ? "gpu_clock_restore_failed"
                    : cause.contains("permission") || cause.contains("privilege") || cause.contains("access denied")
                    ? "gpu_clock_permission_denied" : "gpu_clock_write_failed");
        }
    }

    /** Reset only tuning clock controls to driver defaults; power caps are not touched. */
    public ApplyResult restoreDefaults(String deviceId) {
        return restoreDefaults(deviceId, false);
    }

    /** Explicit operator reset also clears a supported memory lock set outside this Agent. */
    public ApplyResult restoreDriverDefaults(String deviceId) {
        return restoreDefaults(deviceId, true);
    }

    private ApplyResult restoreDefaults(String deviceId, boolean operatorRequested) {
        LocalGpuPowerService.Gpu gpu = find(deviceId);
        if (gpu == null) return new ApplyResult(false, "gpu_not_found");
        try {
            String vendor = gpu.vendor().toUpperCase(Locale.ROOT);
            if ("NVIDIA".equals(vendor)) {
                power.runTuningCommand(List.of("nvidia-smi", "-i", gpu.deviceId(), "--reset-gpu-clocks"));
                boolean rtx20 = gpu.model() != null && gpu.model().matches("(?i).*RTX\\s*20[0-9]{2}.*");
                if (memoryTouched.contains(deviceId) || operatorRequested && !rtx20
                        && refreshCapabilities(deviceId).memoryClockControl())
                    power.runTuningCommand(List.of("nvidia-smi", "-i", gpu.deviceId(), "--reset-memory-clocks"));
                OffsetRecovery previous = offsetRecovery.get(deviceId);
                if (previous != null) {
                    offsetDriver.set(gpu.index(), previous.pstate(), operatorRequested ? 0 : previous.originalMhz());
                    if (!Objects.equals(readMemoryOffset(deviceId, previous.pstate()),
                            operatorRequested ? 0 : previous.originalMhz()))
                        throw new IOException("NVML memory offset restore mismatch");
                } else if (operatorRequested && rtx20) {
                    MemoryOffsetRange current = memoryOffsetRange(deviceId);
                    if (current != null && current.currentMhz() != 0) {
                        offsetDriver.set(gpu.index(), current.pstate(), 0);
                        if (!Objects.equals(readMemoryOffset(deviceId, current.pstate()), 0))
                            throw new IOException("NVML memory offset reset mismatch");
                    }
                }
            } else if ("AMD".equals(vendor)) {
                power.runTuningCommand(List.of("amd-smi", "set", "--gpu", gpu.deviceId(), "--perf-level", "AUTO"));
            } else if ("INTEL".equals(vendor)) {
                power.runTuningCommand(List.of("xpu-smi", "config", "-d", gpu.deviceId(), "--resetfrequencyrange"));
            } else return new ApplyResult(false, "gpu_vendor_unsupported");
            clearRecovery(deviceId);
            return new ApplyResult(true, null);
        } catch (Exception | LinkageError ex) {
            return new ApplyResult(false, "gpu_clock_restore_failed");
        }
    }

    private synchronized boolean persistRecovery(String deviceId, boolean memoryRequested, OffsetRecovery offset) {
        boolean added = recoveryPending.add(deviceId);
        boolean memoryAdded = memoryRequested && memoryTouched.add(deviceId);
        boolean offsetAdded = offset != null && offsetRecovery.putIfAbsent(deviceId, offset) == null;
        boolean saved = de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(recoveryFile,
                recoveryMarker(), json, recoveryFile.getFileName().toString());
        if (!saved) {
            if (added) recoveryPending.remove(deviceId);
            if (memoryAdded) memoryTouched.remove(deviceId);
            if (offsetAdded) offsetRecovery.remove(deviceId);
        }
        return saved;
    }

    private synchronized void clearRecovery(String deviceId) {
        recoveryPending.remove(deviceId);
        memoryTouched.remove(deviceId);
        offsetRecovery.remove(deviceId);
        if (recoveryPending.isEmpty()) {
            try { Files.deleteIfExists(recoveryFile); } catch (Exception ignored) { }
        } else de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(recoveryFile,
                recoveryMarker(), json, recoveryFile.getFileName().toString());
    }

    private java.util.Map<String, Object> recoveryMarker() {
        java.util.Map<String, Object> marker = new java.util.TreeMap<>();
        for (String id : recoveryPending) {
            OffsetRecovery offset = offsetRecovery.get(id);
            marker.put(id, offset == null ? memoryTouched.contains(id)
                    : java.util.Map.of("memoryTouched", memoryTouched.contains(id),
                    "offsetPstate", offset.pstate(), "originalOffsetMhz", offset.originalMhz()));
        }
        return marker;
    }

    private record OffsetRecovery(int pstate, int originalMhz) { }
    public record MemoryOffsetRange(int pstate, int minMhz, int maxMhz, int currentMhz) { }
    public interface MemoryOffsetDriver {
        MemoryOffsetRange probe(int gpuIndex, int pstate) throws Exception;
        void set(int gpuIndex, int pstate, int offsetMhz) throws Exception;
        void setCoreTarget(int gpuIndex, int coreMhz) throws Exception;
    }

    private LocalGpuPowerService.Gpu find(String id) {
        return power.refresh().stream().filter(g -> Objects.equals(g.deviceId(), id)).findFirst().orElse(null);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static boolean valid(Integer min, Integer max, Integer lower, Integer upper, boolean supported) {
        if (min == null && max == null) return true;
        return supported && min != null && max != null && lower != null && upper != null
                && min >= lower && max <= upper && min <= max;
    }

    private static Capabilities unavailable(String id, String reason) {
        return new Capabilities(id, false, false, false, null, null, null, null, reason);
    }

    private enum MemoryLockSupport { UNAVAILABLE, RUNTIME_REPORTED, PROVISIONAL }

    private MemoryLockSupport nvidiaMemoryLockSupport(String deviceId) {
        try {
            String info = power.runTuningCommand(List.of("nvidia-smi", "-i", deviceId, "--lock-memory-clocks-info"))
                    .toLowerCase(Locale.ROOT);
            boolean runtime = info.contains("runtime modifiable") || info.lines()
                    .map(String::strip)
                    .anyMatch(line -> line.matches("memory\\s+clock\\s+switching\\s+type\\s*:\\s*runtime"));
            return runtime ? MemoryLockSupport.RUNTIME_REPORTED : MemoryLockSupport.UNAVAILABLE;
        } catch (Exception ex) {
            String detail = Objects.toString(ex.getMessage(), "").toLowerCase(Locale.ROOT);
            // Older nvidia-smi builds have -lmc but no read-only lock-policy query.
            if (!detail.contains("not recognized") && !detail.contains("unrecognized")
                    && !detail.contains("unknown option")) return MemoryLockSupport.UNAVAILABLE;
            try {
                String help = power.runTuningCommand(List.of("nvidia-smi", "--help"));
                if (!help.contains("--lock-memory-clocks=") || !help.contains("--reset-memory-clocks"))
                    return MemoryLockSupport.UNAVAILABLE;
                String capability = power.runTuningCommand(List.of("nvidia-smi", "-i", deviceId,
                        "--query-gpu=compute_cap", "--format=csv,noheader,nounits")).strip();
                // NVIDIA documents runtime -lmc on Volta+, excluding Hopper's deferred-only policy.
                return capability.matches("[78]\\.[0-9]+") ? MemoryLockSupport.PROVISIONAL : MemoryLockSupport.UNAVAILABLE;
            } catch (Exception ignored) { return MemoryLockSupport.UNAVAILABLE; }
        }
    }

    private static ClockBounds parseClockBounds(String output) {
        int coreMin = Integer.MAX_VALUE, coreMax = Integer.MIN_VALUE;
        int memoryMin = Integer.MAX_VALUE, memoryMax = Integer.MIN_VALUE;
        java.util.Set<ClockPair> pairs = new java.util.LinkedHashSet<>();
        for (String line : output.lines().toList()) {
            String[] values = line.split(",");
            if (values.length < 2) continue;
            Integer memory = integer(values[0]), core = integer(values[1]);
            if (memory != null && memory > 0) { memoryMin = Math.min(memoryMin, memory); memoryMax = Math.max(memoryMax, memory); }
            if (core != null && core > 0) { coreMin = Math.min(coreMin, core); coreMax = Math.max(coreMax, core); }
            if (memory != null && memory > 0 && core != null && core > 0)
                pairs.add(new ClockPair(memory, core));
        }
        return coreMin == Integer.MAX_VALUE || memoryMin == Integer.MAX_VALUE ? null
                : new ClockBounds(new Bounds(coreMin, coreMax), new Bounds(memoryMin, memoryMax), List.copyOf(pairs));
    }

    private static Bounds parseNamedBounds(String output, String... names) {
        String lower = output.toLowerCase(Locale.ROOT);
        boolean named = false;
        for (String name : names) named |= lower.contains(name.toLowerCase(Locale.ROOT));
        if (!named) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?:min(?:imum)?[^0-9]*)?(\\d{2,5})\\s*(?:mhz)?\\s*(?:to|[-–])\\s*(\\d{2,5})").matcher(output);
        if (matcher.find()) {
            Integer min = integer(matcher.group(1)), max = integer(matcher.group(2));
            if (min != null && max != null && min > 0 && max >= min) return new Bounds(min, max);
        }
        return null;
    }

    /** AMD-SMI reports selectable frequency levels instead of a min/max pair on many ASICs. */
    private static Bounds parseClockLevels(String output, String... names) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        boolean inSection = false;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            String normalized = trimmed.replaceAll("^[\"']", "")
                    .replaceAll("[\"']?\\s*[:=].*$", "")
                    .replaceAll("[^A-Za-z0-9_].*$", "").toUpperCase(Locale.ROOT);
            boolean section = false;
            for (String name : names) if (normalized.equals(name.toUpperCase(Locale.ROOT))) section = true;
            if (section) { inSection = true; continue; }
            if (inSection && List.of("SYS", "SCLK", "GFXCLK", "MEM", "MCLK", "MEMCLK").contains(normalized)) break;
            if (inSection && trimmed.matches("^[A-Za-z][A-Za-z0-9_]*\\s*[:=].*")) break;
            if (!inSection) continue;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?<![A-Za-z])([1-9][0-9]{2,3})(?:\\s*MHz)?(?![A-Za-z0-9])").matcher(trimmed);
            while (matcher.find()) {
                Integer value = integer(matcher.group(1));
                if (value != null && value >= 100 && value <= 5000) { min = Math.min(min, value); max = Math.max(max, value); }
            }
        }
        return min == Integer.MAX_VALUE ? null : new Bounds(min, max);
    }

    private static Integer integer(String text) {
        try { return Integer.parseInt(text.trim().replaceAll("[^0-9]", "")); }
        catch (RuntimeException ex) { return null; }
    }

    private record Bounds(int min, int max) { }
    private record ClockBounds(Bounds core, Bounds memory, List<ClockPair> pairs) { }
    public record ClockPair(int memoryMhz, int coreMhz) { }
    public record ClockTelemetry(int coreMhz, int memoryMhz) { }
    public record Capabilities(String deviceId, boolean coreClockControl, boolean memoryClockControl,
                               boolean voltageControl, Integer coreMinMhz, Integer coreMaxMhz,
                               Integer memoryMinMhz, Integer memoryMaxMhz, String reason,
                               List<ClockPair> supportedPairs) {
        public Capabilities {
            supportedPairs = supportedPairs == null ? List.of() : List.copyOf(supportedPairs);
        }
        public Capabilities(String deviceId, boolean coreClockControl, boolean memoryClockControl,
                            boolean voltageControl, Integer coreMinMhz, Integer coreMaxMhz,
                            Integer memoryMinMhz, Integer memoryMaxMhz, String reason) {
            this(deviceId, coreClockControl, memoryClockControl, voltageControl, coreMinMhz, coreMaxMhz,
                    memoryMinMhz, memoryMaxMhz, reason, List.of());
        }
    }
    public record ApplyResult(boolean applied, String reason) { }
}
