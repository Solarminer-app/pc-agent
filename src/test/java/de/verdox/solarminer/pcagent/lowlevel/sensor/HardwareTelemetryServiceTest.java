package de.verdox.solarminer.pcagent.lowlevel.sensor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardwareTelemetryServiceTest {
    @Test
    void keepsNonWorkerPowerSensorsSeparateWithoutSummingCpuOrGpuReadings() {
        var measured = new HardwareTelemetryService.Metric(12.5, "W", "Linux hwmon", true, true);
        Map<String, HardwareTelemetryService.Metric> metrics = Map.of(
                "hwmon.mainboard.power1.power", measured,
                "hwmon.amdgpu.memory.power", measured,
                "hardware.amd_ryzen_cpu_memory_power", measured,
                "hwmon.psu.input.power", measured);

        var readings = HardwareTelemetryService.otherPowerReadings(metrics);

        assertEquals(2, readings.size());
        assertEquals(java.util.Set.of("hwmon.mainboard.power1.power", "hwmon.psu.input.power"),
                readings.stream().map(HardwareTelemetryService.OtherPowerReading::sensorKey)
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
