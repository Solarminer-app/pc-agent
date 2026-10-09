package de.verdox.solarminer.pcagent.lowlevel.sensor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@EnabledOnOs(OS.LINUX)
class LinuxSensorReaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void findsEnergyCounterWithoutFollowingSysfsStyleLinkCycles() throws Exception {
        Path powercap = Files.createDirectory(temporaryDirectory.resolve("powercap"));
        Path devices = Files.createDirectory(temporaryDirectory.resolve("devices"));
        Path rapl = Files.createDirectories(devices.resolve("intel-rapl:0/intel-rapl:0:0"));
        Path counter = Files.writeString(rapl.resolve("energy_uj"), "1234");

        Path packageDirectory = rapl.getParent();
        Files.createSymbolicLink(powercap.resolve("intel-rapl:0"), packageDirectory);
        Files.createSymbolicLink(packageDirectory.resolve("device"), packageDirectory);
        Files.createSymbolicLink(packageDirectory.resolve("subsystem"), powercap);
        Files.createSymbolicLink(rapl.resolve("power"), packageDirectory);

        Path found = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> LinuxSensorReader.findEnergyCounter(powercap));

        assertEquals(counter.toRealPath(), found);
        assertEquals(List.of(counter.toRealPath()), LinuxSensorReader.energyFiles(packageDirectory));
    }
}
