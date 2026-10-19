package de.verdox.gputoolkit.nvidia;

import de.verdox.gputoolkit.GPUInterface;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** GPUInterface implementation using the NVIDIA driver's nvidia-smi executable. */
public final class NvidiaSmiAdapter implements GPUInterface {
    private static final String UNAVAILABLE = "[N/A]";
    private final String executable;

    public NvidiaSmiAdapter() {
        this(System.getProperty("gputoolkit.nvidiaSmi", "nvidia-smi"));
    }

    /** Allows callers to select an explicit executable path (useful on Windows installations). */
    public NvidiaSmiAdapter(String executable) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("nvidia-smi executable must not be blank");
        }
        this.executable = executable;
    }

    private record Result(int exitCode, String output, String error) { }

    private Result run(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out running nvidia-smi");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return new Result(process.exitValue(), output, error);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run nvidia-smi ('" + executable + "'): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running nvidia-smi", e);
        }
    }

    private String checked(String... arguments) {
        Result result = run(arguments);
        if (result.exitCode() != 0) {
            String detail = result.error().isBlank() ? result.output() : result.error();
            throw new IllegalStateException("nvidia-smi command failed (exit " + result.exitCode() + "): " + detail);
        }
        return result.output();
    }

    private String query(int gpuIndex, String field) {
        checkIndex(gpuIndex);
        return checked("--id=" + gpuIndex, "--query-gpu=" + field, "--format=csv,noheader,nounits").lines()
                .findFirst().orElse("").trim();
    }

    private static void checkIndex(int gpuIndex) {
        if (gpuIndex < 0) throw new IllegalArgumentException("GPU index must be non-negative");
    }

    private static long number(String value, String field) {
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase(UNAVAILABLE)) {
            throw new IllegalStateException("nvidia-smi did not report " + field);
        }
        try {
            return Math.round(Double.parseDouble(normalized));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid nvidia-smi value for " + field + ": " + normalized, e);
        }
    }

    private void set(int gpuIndex, String... arguments) {
        checkIndex(gpuIndex);
        List<String> command = new ArrayList<>(List.of("--id=" + gpuIndex));
        command.addAll(List.of(arguments));
        checked(command.toArray(String[]::new));
    }

    @Override public int getDeviceCount() {
        Result result = run("--query-gpu=index", "--format=csv,noheader,nounits");
        if (result.exitCode() != 0) return 0;
        return (int) result.output().lines().filter(line -> !line.isBlank()).count();
    }

    @Override public String getDeviceName(int gpuIndex) { return query(gpuIndex, "name"); }
    @Override public String getDriverVersion() { return checked("--query-gpu=driver_version", "--format=csv,noheader,nounits").lines().findFirst().orElse("Unknown").trim(); }
    @Override public float getTemperatureCelsius(int gpuIndex) { return number(query(gpuIndex, "temperature.gpu"), "temperature.gpu"); }
    @Override public int getFanSpeedPercent(int gpuIndex) { return (int) number(query(gpuIndex, "fan.speed"), "fan.speed"); }
    @Override public int getCoreUtilizationPercent(int gpuIndex) { return (int) number(query(gpuIndex, "utilization.gpu"), "utilization.gpu"); }
    @Override public long getLivePowerDraw(int gpuIndex) { return number(query(gpuIndex, "power.draw"), "power.draw"); }
    @Override public long getUsedVramBytes(int gpuIndex) { return number(query(gpuIndex, "memory.used"), "memory.used") * 1024L * 1024L; }
    @Override public long getTotalVramBytes(int gpuIndex) { return number(query(gpuIndex, "memory.total"), "memory.total") * 1024L * 1024L; }
    @Override public int getLiveCoreClockMhz(int gpuIndex) { return (int) number(query(gpuIndex, "clocks.gr"), "clocks.gr"); }
    @Override public int getLiveMemoryClockMhz(int gpuIndex) { return (int) number(query(gpuIndex, "clocks.mem"), "clocks.mem"); }
    @Override public int getCoreClockMhz(int gpuIndex) { return getLiveCoreClockMhz(gpuIndex); }
    @Override public int getMemoryClockMhz(int gpuIndex) { return getLiveMemoryClockMhz(gpuIndex); }

    @Override public long getPowerTargetWatts(int gpuIndex) { return number(query(gpuIndex, "power.limit"), "power.limit"); }
    @Override public float getPowerTargetPercent(int gpuIndex) {
        long current = getPowerTargetWatts(gpuIndex);
        long defaultLimit = number(query(gpuIndex, "power.default_limit"), "power.default_limit");
        if (defaultLimit <= 0) throw new IllegalStateException("Invalid nvidia-smi default power limit");
        return (float) current / defaultLimit * 100.0f;
    }
    @Override public void setPowerTargetWatts(int gpuIndex, long watts) {
        if (watts <= 0) throw new IllegalArgumentException("Power target must be positive");
        set(gpuIndex, "--power-limit=" + watts);
    }
    @Override public boolean supportsPowerTargetChanges(int gpuIndex) {
        try { number(query(gpuIndex, "power.min_limit"), "power.min_limit"); number(query(gpuIndex, "power.max_limit"), "power.max_limit"); return true; }
        catch (RuntimeException e) { return false; }
    }
    @Override public void resetPowerTargetToDefaults(int gpuIndex) {
        long watts = number(query(gpuIndex, "power.default_limit"), "power.default_limit");
        setPowerTargetWatts(gpuIndex, watts);
    }

    @Override public void setCoreClockTargetMhz(int gpuIndex, int targetMhz) {
        if (targetMhz <= 0) throw new IllegalArgumentException("Core clock target must be positive");
        set(gpuIndex, "--lock-gpu-clocks=" + targetMhz + "," + targetMhz);
    }
    @Override public void setMemoryClockTargetMhz(int gpuIndex, int targetMhz) {
        if (targetMhz <= 0) throw new IllegalArgumentException("Memory clock target must be positive");
        set(gpuIndex, "--lock-memory-clocks=" + targetMhz + "," + targetMhz);
    }
    @Override public boolean supportsSettingTargetCoreClock(int gpuIndex) { return commandSupported(gpuIndex, "--lock-gpu-clocks"); }
    @Override public boolean supportsSettingTargetMemoryClock(int gpuIndex) { return commandSupported(gpuIndex, "--lock-memory-clocks"); }
    @Override public void setCoreClockOffsetMhz(int gpuIndex, int offsetMhz) { throw unsupported("Core clock offsets"); }
    @Override public void setMemoryClockOffsetMhz(int gpuIndex, int offsetMhz) { throw unsupported("Memory clock offsets"); }
    @Override public GPUInterface.ClockOffsetRange getClockOffsetRange(int gpuIndex, boolean memory, int pstate) { throw unsupported("Clock offset ranges"); }
    @Override public void setMemoryClockOffsetMhz(int gpuIndex, int pstate, int offsetMhz) { throw unsupported("Memory clock offsets"); }
    @Override public boolean supportsOffsetCoreClock(int gpuIndex) { return false; }
    @Override public boolean supportsOffsetMemoryClock(int gpuIndex) { return false; }
    @Override public boolean supportsFanSpeedControl(int gpuIndex) { return false; }
    @Override public void setFanSpeedPercent(int gpuIndex, int percent) { throw unsupported("Fan speed control"); }
    @Override public void resetFanSpeedToDefaults(int gpuIndex) { throw unsupported("Fan speed control"); }
    @Override public boolean supportsResetToDefaults(int gpuIndex) { return true; }
    @Override public void resetClocksToDefaults(int gpuIndex) {
        set(gpuIndex, "--reset-gpu-clocks");
        set(gpuIndex, "--reset-memory-clocks");
    }
    @Override public void resetToDefaults(int gpuIndex) { resetClocksToDefaults(gpuIndex); resetPowerTargetToDefaults(gpuIndex); }

    private static UnsupportedOperationException unsupported(String feature) {
        return new UnsupportedOperationException(feature + " are not exposed by this nvidia-smi adapter");
    }

    private boolean commandSupported(int gpuIndex, String option) {
        try {
            checkIndex(gpuIndex);
            Result result = run("--help");
            return result.exitCode() == 0 && (result.output() + result.error()).contains(option);
        }
        catch (RuntimeException e) { return false; }
    }
}
