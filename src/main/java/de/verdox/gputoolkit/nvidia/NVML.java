package de.verdox.gputoolkit.nvidia;


import java.util.List;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

import java.util.Arrays;

public interface NVML extends Library {
    int NVML_SUCCESS = 0;

    int nvmlInit_v2();
    int nvmlShutdown();
    int nvmlDeviceGetCount_v2(IntByReference deviceCount);
    int nvmlDeviceGetHandleByIndex_v2(int index, PointerByReference deviceHandle);
    int nvmlDeviceGetName(Pointer device, byte[] name, int length);

    int nvmlDeviceGetTemperature(Pointer device, int sensorType, IntByReference temp);
    int nvmlDeviceGetFanSpeed(Pointer device, IntByReference speed);
    int nvmlDeviceGetUtilizationRates(Pointer device, NvmlUtilization utilization);
    int nvmlDeviceGetMemoryInfo(Pointer device, NvmlMemory memory);
    int nvmlDeviceGetPowerUsage(Pointer device, IntByReference powerMw);
    int nvmlDeviceGetPowerManagementLimit(Pointer device, IntByReference limitMw);
    int nvmlDeviceGetPowerManagementDefaultLimit(Pointer device, IntByReference defaultLimitMw);
    int nvmlDeviceGetClockInfo(Pointer device, int clockType, IntByReference clockMhz);

    int nvmlDeviceSetPowerManagementLimit(Pointer device, int limitMw);
    int nvmlDeviceSetGpuLockedClocks(Pointer device, int minGpuClockMhz, int maxGpuClockMhz);
    int nvmlDeviceResetGpuLockedClocks(Pointer device);

    int nvmlDeviceSetGpuClockOffsets(Pointer device, int offsetMhz);
    int nvmlDeviceSetMemClockOffset(Pointer device, int offsetMhz);

    int nvmlDeviceSetFanSpeed_v2(Pointer device, int fan, int speed);
    int nvmlDeviceSetFanSpeedToDefault(Pointer device, int fan);

    int nvmlDeviceGetPowerManagementLimitConstraints(Pointer device, IntByReference minPowerMw, IntByReference maxPowerMw);

    int nvmlSystemGetDriverVersion(byte[] version, int length);

    int nvmlDeviceSetClockOffsets(Pointer device, NvmlClockOffset_v1 offsetInfo);
    int nvmlDeviceGetClockOffsets(Pointer device, NvmlClockOffset_v1 offsetInfo);

    class PointerByReference extends com.sun.jna.ptr.PointerByReference {}

    @com.sun.jna.Structure.FieldOrder({"gpu", "memory"})
    class NvmlUtilization extends com.sun.jna.Structure {
        public int gpu;
        public int memory;
    }

    @com.sun.jna.Structure.FieldOrder({"total", "free", "used"})
    class NvmlMemory extends com.sun.jna.Structure {
        public long total;
        public long free;
        public long used;
    }

    class NvmlClockOffset_v1 extends Structure {
        public int version;
        public int clockType;
        public int pState;
        public int clockOffsetMHz;
        public int minClockOffsetMHz;
        public int maxClockOffsetMHz;

        public NvmlClockOffset_v1() {
            this.version = (1 << 24) | 24;
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("version", "clockType", "pState", "clockOffsetMHz",
                    "minClockOffsetMHz", "maxClockOffsetMHz");
        }
    }
}
