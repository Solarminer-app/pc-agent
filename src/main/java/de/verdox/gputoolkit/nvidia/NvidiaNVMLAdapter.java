package de.verdox.gputoolkit.nvidia;


import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import de.verdox.gputoolkit.GPUInterface;

import java.nio.charset.StandardCharsets;

public class NvidiaNVMLAdapter implements GPUInterface {

    private final NVML nvml;

    public NvidiaNVMLAdapter() {
        if (!NvmlLoader.isAvailable()) {
            throw new IllegalStateException("NvidiaNVMLAdapter kann nicht geladen werden, da NVML nicht verfügbar ist.");
        }
        this.nvml = NvmlLoader.get();
    }

    private Pointer getHandle(int gpuIndex) {
        NVML.PointerByReference handle = new NVML.PointerByReference();
        int res = nvml.nvmlDeviceGetHandleByIndex_v2(gpuIndex, handle);
        if (res != NVML.NVML_SUCCESS) {
            throw new RuntimeException("Konnte kein Handle für GPU " + gpuIndex + " abrufen. NVML Fehlercode: " + res);
        }
        return handle.getValue();
    }

    @Override
    public int getDeviceCount() {
        if (!NvmlLoader.isAvailable()) return 0;
        IntByReference count = new IntByReference();
        int res = nvml.nvmlDeviceGetCount_v2(count);
        return res == NVML.NVML_SUCCESS ? count.getValue() : 0;
    }

    @Override
    public String getDeviceName(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        byte[] nameBytes = new byte[96];
        int res = nvml.nvmlDeviceGetName(device, nameBytes, nameBytes.length);
        if (res == NVML.NVML_SUCCESS) {
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            int nullIdx = name.indexOf('\0');
            return nullIdx != -1 ? name.substring(0, nullIdx).trim() : name.trim();
        }
        return "Unknown NVIDIA GPU";
    }

    @Override
    public float getTemperatureCelsius(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        IntByReference temp = new IntByReference();
        int res = nvml.nvmlDeviceGetTemperature(device, 0, temp); // 0 = GPU temperature sensor
        return res == NVML.NVML_SUCCESS ? temp.getValue() : 0.0f;
    }

    @Override
    public int getFanSpeedPercent(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        IntByReference speed = new IntByReference();
        int res = nvml.nvmlDeviceGetFanSpeed(device, speed);
        return res == NVML.NVML_SUCCESS ? speed.getValue() : 0;
    }

    @Override
    public void setFanSpeedPercent(int gpuIndex, int percent) {
        Pointer device = getHandle(gpuIndex);
        int res = nvml.nvmlDeviceSetFanSpeed_v2(device, 0, percent);
        if (res != NVML.NVML_SUCCESS) {
            throw new RuntimeException("Konnte Lüftergeschwindigkeit nicht setzen. NVML Fehler: " + res);
        }
    }

    @Override
    public boolean supportsFanSpeedControl(int gpuIndex) {
        try {
            return getFanSpeedPercent(gpuIndex) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getDriverVersion() {
        byte[] versionBytes = new byte[80];
        int res = nvml.nvmlSystemGetDriverVersion(versionBytes, versionBytes.length);
        if (res == NVML.NVML_SUCCESS) {
            String version = new String(versionBytes, java.nio.charset.StandardCharsets.UTF_8);
            int nullIdx = version.indexOf('\0');
            return nullIdx != -1 ? version.substring(0, nullIdx).trim() : version.trim();
        }
        return "Unknown";
    }

    @Override
    public int getCoreUtilizationPercent(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        NVML.NvmlUtilization util = new NVML.NvmlUtilization();
        int res = nvml.nvmlDeviceGetUtilizationRates(device, util);
        return res == NVML.NVML_SUCCESS ? util.gpu : 0;
    }

    @Override
    public long getLivePowerDraw(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        IntByReference powerMw = new IntByReference();
        int res = nvml.nvmlDeviceGetPowerUsage(device, powerMw);
        return res == NVML.NVML_SUCCESS ? Math.round(powerMw.getValue() / 1000.0) : 0L;
    }

    @Override
    public long getUsedVramBytes(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        NVML.NvmlMemory mem = new NVML.NvmlMemory();
        int res = nvml.nvmlDeviceGetMemoryInfo(device, mem);
        return res == NVML.NVML_SUCCESS ? mem.used : 0L;
    }

    @Override
    public long getTotalVramBytes(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        NVML.NvmlMemory mem = new NVML.NvmlMemory();
        int res = nvml.nvmlDeviceGetMemoryInfo(device, mem);
        return res == NVML.NVML_SUCCESS ? mem.total : 0L;
    }

    @Override
    public int getLiveCoreClockMhz(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        IntByReference clock = new IntByReference();
        int res = nvml.nvmlDeviceGetClockInfo(device, 0, clock); // 0 = NVML_CLOCK_GRAPHICS
        return res == NVML.NVML_SUCCESS ? clock.getValue() : 0;
    }

    @Override
    public int getLiveMemoryClockMhz(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        IntByReference clock = new IntByReference();
        int res = nvml.nvmlDeviceGetClockInfo(device, 2, clock); // 2 = NVML_CLOCK_MEM
        return res == NVML.NVML_SUCCESS ? clock.getValue() : 0;
    }

    @Override
    public boolean supportsPowerTargetChanges(int gpuIndex) {
        try {
            return getPowerTargetWatts(gpuIndex) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean supportsOffsetCoreClock(int gpuIndex) {
        try {
            getClockOffsetRange(gpuIndex, false, 0);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean supportsOffsetMemoryClock(int gpuIndex) {
        try {
            getClockOffsetRange(gpuIndex, true, 0);
            return true;
        } catch (RuntimeException | LinkageError e) {
            try {
                getClockOffsetRange(gpuIndex, true, 2);
                return true;
            } catch (RuntimeException | LinkageError ignored) { return false; }
        }
    }

    @Override
    public boolean supportsSettingTargetCoreClock(int gpuIndex) {
        return true; // Wird von modernen Karten im NVML-Backend unterstützt
    }

    @Override
    public boolean supportsSettingTargetMemoryClock(int gpuIndex) {
        // Hängt stark von der Architektur ab (meist Enterprise), NVML wirft sonst Error
        return false;
    }

    @Override
    public boolean supportsResetToDefaults(int gpuIndex) {
        return true;
    }

    @Override
    public void setPowerTargetWatts(int gpuIndex, long watts) {
        Pointer device = getHandle(gpuIndex);
        int limitMw = (int) (watts * 1000L);
        int res = nvml.nvmlDeviceSetPowerManagementLimit(device, limitMw);
        if (res != NVML.NVML_SUCCESS) {
            throw new RuntimeException("Konnte Power Target nicht setzen. NVML Fehler: " + res);
        }
    }

    @Override
    public long getPowerTargetWatts(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        IntByReference limitMw = new IntByReference();
        int res = nvml.nvmlDeviceGetPowerManagementLimit(device, limitMw);
        return res == NVML.NVML_SUCCESS ? Math.round(limitMw.getValue() / 1000.0) : 0L;
    }

    @Override
    public float getPowerTargetPercent(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        long current = getPowerTargetWatts(gpuIndex);

        IntByReference defaultMw = new IntByReference();
        int res = nvml.nvmlDeviceGetPowerManagementDefaultLimit(device, defaultMw);
        if (res == NVML.NVML_SUCCESS && defaultMw.getValue() > 0) {
            long defaultWatts = Math.round(defaultMw.getValue() / 1000.0);
            return (float) current / defaultWatts * 100.0f;
        }
        return 100.0f;
    }

    @Override
    public int getCoreClockMhz(int gpuIndex) {
        return getLiveCoreClockMhz(gpuIndex);
    }

    @Override
    public int getMemoryClockMhz(int gpuIndex) {
        return getLiveMemoryClockMhz(gpuIndex);
    }

    @Override
    public void setCoreClockOffsetMhz(int gpuIndex, int offsetMhz) {
        Pointer device = getHandle(gpuIndex);
        NVML.NvmlClockOffset_v1 offset = new NVML.NvmlClockOffset_v1();
        offset.clockType = 0; // Graphics Clock
        offset.pState = 0;    // P0 State
        offset.clockOffsetMHz = offsetMhz;

        int res = nvml.nvmlDeviceSetClockOffsets(device, offset);
        if (res != NVML.NVML_SUCCESS) {
            throw new RuntimeException("Konnte Core Clock Offset via nvmlDeviceSetClockOffsets nicht setzen. NVML Fehler: " + res);
        }
    }

    @Override
    public void setMemoryClockOffsetMhz(int gpuIndex, int offsetMhz) {
        setMemoryClockOffsetMhz(gpuIndex, 0, offsetMhz);
    }

    @Override
    public ClockOffsetRange getClockOffsetRange(int gpuIndex, boolean memory, int pstate) {
        NVML.NvmlClockOffset_v1 offset = new NVML.NvmlClockOffset_v1();
        offset.clockType = memory ? 2 : 0;
        offset.pState = pstate;
        int result = nvml.nvmlDeviceGetClockOffsets(getHandle(gpuIndex), offset);
        if (result != NVML.NVML_SUCCESS)
            throw new IllegalStateException("NVML clock offset query failed: " + result);
        return new ClockOffsetRange(pstate, offset.minClockOffsetMHz,
                offset.maxClockOffsetMHz, offset.clockOffsetMHz);
    }

    @Override
    public void setMemoryClockOffsetMhz(int gpuIndex, int pstate, int offsetMhz) {
        ClockOffsetRange range = getClockOffsetRange(gpuIndex, true, pstate);
        if (offsetMhz < range.minMhz() || offsetMhz > range.maxMhz())
            throw new IllegalArgumentException("Memory offset outside NVML driver bounds");
        Pointer device = getHandle(gpuIndex);
        NVML.NvmlClockOffset_v1 offset = new NVML.NvmlClockOffset_v1();
        offset.clockType = 2; // NVML_CLOCK_MEM
        offset.pState = pstate;
        offset.clockOffsetMHz = offsetMhz;

        int res = nvml.nvmlDeviceSetClockOffsets(device, offset);
        if (res != NVML.NVML_SUCCESS) {
            throw new RuntimeException("Konnte Memory Clock Offset nicht setzen. NVML Fehler: " + res);
        }
    }

    @Override
    public void setCoreClockTargetMhz(int gpuIndex, int targetMhz) {
        Pointer device = getHandle(gpuIndex);
        int res = nvml.nvmlDeviceSetGpuLockedClocks(device, targetMhz, targetMhz);
        if (res != NVML.NVML_SUCCESS) {
            throw new RuntimeException("Core Clock Target Locking fehlgeschlagen. NVML Code: " + res);
        }
    }

    @Override
    public void setMemoryClockTargetMhz(int gpuIndex, int targetMhz) {
        throw new UnsupportedOperationException("Memory Clock Target Locking wird auf diesem Adapter nicht unterstützt.");
    }

    @Override
    public void resetClocksToDefaults(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        nvml.nvmlDeviceResetGpuLockedClocks(device);
        try {
            setCoreClockOffsetMhz(gpuIndex, 0);
            setMemoryClockOffsetMhz(gpuIndex, 0);
        } catch (Exception ignored) {}
    }

    @Override
    public void resetPowerTargetToDefaults(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        IntByReference defaultMw = new IntByReference();
        int res = nvml.nvmlDeviceGetPowerManagementDefaultLimit(device, defaultMw);
        if (res == NVML.NVML_SUCCESS) {
            nvml.nvmlDeviceSetPowerManagementLimit(device, defaultMw.getValue());
        }
    }

    @Override
    public void resetFanSpeedToDefaults(int gpuIndex) {
        Pointer device = getHandle(gpuIndex);
        int res = nvml.nvmlDeviceSetFanSpeedToDefault(device, 0);
        if (res != NVML.NVML_SUCCESS) {
            throw new RuntimeException("Konnte Lüfter nicht auf Standard zurücksetzen. NVML Fehler: " + res);
        }
    }

    @Override
    public void resetToDefaults(int gpuIndex) {
        resetClocksToDefaults(gpuIndex);
        resetPowerTargetToDefaults(gpuIndex);
    }
}
