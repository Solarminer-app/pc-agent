package de.verdox.gputoolkit;



/**
 * An interface to interact with any GPU via its native driver.
 */
public interface GPUInterface {

    /**
     * Returns the number of detected GPUs available in the system.
     *
     * @return the total count of GPUs
     */
    int getDeviceCount();

    /**
     * Returns the name/model description of the specified GPU.
     *
     * @param gpuIndex the GPU index
     * @return the device name as a string
     */
    String getDeviceName(int gpuIndex);

    /**
     * Sets the power target in watts for the specified GPU.
     *
     * @param gpuIndex the GPU index
     * @param watts    the target power consumption in watts
     */
    void setPowerTargetWatts(int gpuIndex, long watts);

    /**
     * Sets the core clock offset in MHz for the specified GPU.
     *
     * @param gpuIndex  the GPU index
     * @param offsetMhz the core clock offset in MHz
     */
    void setCoreClockOffsetMhz(int gpuIndex, int offsetMhz);

    void setMemoryClockOffsetMhz(int gpuIndex, int offsetMhz);

    /** Read the driver-reported offset limits and current value for a specific performance state. */
    ClockOffsetRange getClockOffsetRange(int gpuIndex, boolean memory, int pstate);

    /** Set a memory offset for a specific performance state. */
    void setMemoryClockOffsetMhz(int gpuIndex, int pstate, int offsetMhz);

    record ClockOffsetRange(int pstate, int minMhz, int maxMhz, int currentMhz) { }

    /**
     * Sets the target core clock in MHz for the specified GPU.
     *
     * @param gpuIndex  the GPU index
     * @param offsetMhz the target core clock in MHz
     */
    void setCoreClockTargetMhz(int gpuIndex, int offsetMhz);

    /**
     * Sets the target memory clock in MHz for the specified GPU.
     *
     * @param gpuIndex  the GPU index
     * @param offsetMhz the target memory clock in MHz
     */
    void setMemoryClockTargetMhz(int gpuIndex, int offsetMhz);

    /**
     * Returns the current core temperature of the specified GPU in Celsius.
     *
     * @param gpuIndex the GPU index
     * @return the temperature in degrees Celsius
     */
    float getTemperatureCelsius(int gpuIndex);

    /**
     * Returns the current fan speed of the specified GPU as a percentage.
     *
     * @param gpuIndex the GPU index
     * @return the fan speed percentage (0-100)
     */
    int getFanSpeedPercent(int gpuIndex);

    /**
     * Sets the fan speed of the specified GPU as a percentage.
     *
     * @param gpuIndex the GPU index
     * @param percent  the target fan speed percentage (0-100)
     */
    void setFanSpeedPercent(int gpuIndex, int percent);

    /**
     * Checks whether the GPU supports controlling the fan speed via the driver.
     *
     * @param gpuIndex the GPU index
     * @return true if fan speed control is supported, false otherwise
     */
    boolean supportsFanSpeedControl(int gpuIndex);

    String getDriverVersion();

    /**
     * Returns the current core utilization of the specified GPU as a percentage.
     *
     * @param gpuIndex the GPU index
     * @return the core utilization percentage (0-100)
     */
    int getCoreUtilizationPercent(int gpuIndex);

    /**
     * Returns the currently used VRAM (Video RAM) in bytes.
     *
     * @param gpuIndex the GPU index
     * @return the used VRAM in bytes
     */
    long getUsedVramBytes(int gpuIndex);

    /**
     * Returns the total available VRAM (Video RAM) in bytes.
     *
     * @param gpuIndex the GPU index
     * @return the total VRAM in bytes
     */
    long getTotalVramBytes(int gpuIndex);

    /**
     * Resets the clock settings (core and memory clocks/offsets) of the specified GPU to defaults.
     *
     * @param gpuIndex the GPU index
     */
    void resetClocksToDefaults(int gpuIndex);

    /**
     * Resets the power target configuration of the specified GPU to defaults.
     *
     * @param gpuIndex the GPU index
     */
    void resetPowerTargetToDefaults(int gpuIndex);

    /**
     * Resets the fan speed configuration of specified GPU to defaults.
     *
     * @param gpuIndex the GPU index
     */
    void resetFanSpeedToDefaults(int gpuIndex);

    /**
     * Resets all performance parameters (clocks, power target, fan speed, etc.)
     * of the specified GPU back to factory defaults.
     *
     * @param gpuIndex the GPU index
     */
    void resetToDefaults(int gpuIndex);

    /**
     * Returns the power target setting in watts configured in the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return the power target in watts
     */
    long getPowerTargetWatts(int gpuIndex);

    /**
     * Returns the power target setting as a percentage configured in the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return the power target percentage
     */
    float getPowerTargetPercent(int gpuIndex);

    /**
     * Returns the memory clock setting in MHz configured in the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return the memory clock in MHz
     */
    int getMemoryClockMhz(int gpuIndex);

    /**
     * Returns the core clock setting in MHz configured in the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return the core clock in MHz
     */
    int getCoreClockMhz(int gpuIndex);

    /**
     * Returns the live power draw currently measured by the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return the live power draw in watts
     */
    long getLivePowerDraw(int gpuIndex);

    /**
     * Returns the live core clock in MHz currently measured by the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return the live core clock in MHz
     */
    int getLiveCoreClockMhz(int gpuIndex);

    /**
     * Returns the live memory clock in MHz currently measured by the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return the live memory clock in MHz
     */
    int getLiveMemoryClockMhz(int gpuIndex);

    /**
     * Checks whether the GPU supports setting an offset for the core clock in MHz in the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return true if core clock offset is supported, false otherwise
     */
    boolean supportsOffsetCoreClock(int gpuIndex);

    /**
     * Checks whether the GPU supports setting an offset for the memory clock in MHz in the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return true if memory clock offset is supported, false otherwise
     */
    boolean supportsOffsetMemoryClock(int gpuIndex);

    /**
     * Checks whether the GPU supports setting a target core clock in MHz in the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return true if target core clock setting is supported, false otherwise
     */
    boolean supportsSettingTargetCoreClock(int gpuIndex);

    /**
     * Checks whether the GPU supports setting a target memory clock in MHz in the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return true if target memory clock setting is supported, false otherwise
     */
    boolean supportsSettingTargetMemoryClock(int gpuIndex);

    /**
     * Checks whether the GPU supports setting a power target in the GPU driver.
     *
     * @param gpuIndex the GPU index
     * @return true if power target changes are supported, false otherwise
     */
    boolean supportsPowerTargetChanges(int gpuIndex);

    /**
     * Checks whether the GPU driver supports resetting settings to defaults.
     *
     * @param gpuIndex the GPU index
     * @return true if resetting to defaults is supported, false otherwise
     */
    boolean supportsResetToDefaults(int gpuIndex);
}
