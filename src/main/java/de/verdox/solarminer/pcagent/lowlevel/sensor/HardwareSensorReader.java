package de.verdox.solarminer.pcagent.lowlevel.sensor;

public interface HardwareSensorReader {
    double getCpuPowerWatts();

    double getCpuTemperatureCelsius();

    boolean isAccurate();
}