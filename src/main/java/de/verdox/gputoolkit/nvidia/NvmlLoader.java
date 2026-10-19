package de.verdox.gputoolkit.nvidia;


import com.sun.jna.Native;
import lombok.Getter;

public class NvmlLoader {
    private static NVML nvmlInstance = null;
    @Getter
    private static boolean isAvailable = false;

    static {
        try {
            String libName = System.getProperty("os.name").toLowerCase().contains("win") ? "nvml" : "nvidia-ml";
            nvmlInstance = Native.load(libName, NVML.class);

            int result = nvmlInstance.nvmlInit_v2();
            if (result == 0) {
                isAvailable = true;
            }
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Could not find NVIDIA NVML. No NVIDIA-Driver installed? (" + e.getMessage() + ")");
        } catch (Exception e) {
            System.err.println("Error while loading NVML: " + e.getMessage());
        }
    }

    public static NVML get() {
        if (!isAvailable || nvmlInstance == null) {
            throw new IllegalStateException("NVML not available on this system.");
        }
        return nvmlInstance;
    }
}
