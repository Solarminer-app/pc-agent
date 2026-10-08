package de.verdox.solarminer.pcagent.lowlevel.sensor;

import java.util.function.Supplier;

public class FallbackSensorReader implements HardwareSensorReader {

    private final Supplier<Double> estimatedWattageSupplier;

    public FallbackSensorReader(Supplier<Double> estimatedWattageSupplier) {
        this.estimatedWattageSupplier = estimatedWattageSupplier;
    }

    @Override
    public double getCpuPowerWatts() {
        return estimatedWattageSupplier.get();
    }

    @Override
    public double getCpuTemperatureCelsius() {
        return -1.0;
    }

    @Override
    public boolean isAccurate() {
        return false;
    }
}
