package de.verdox.solarminer.pcagent.lowlevel.sensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.HardwareIdentityService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.logging.Logger;

@Configuration
public class SensorConfiguration {

    private static final Logger LOGGER = Logger.getLogger(SensorConfiguration.class.getName());

    @Bean
    public HardwareSensorReader hardwareSensorReader(ObjectMapper objectMapper, HardwareIdentityService hardwareIdentityService,
                                                      WindowsLhmBootstrapService windowsLhmBootstrapService) {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("linux") || os.contains("nix")) {
            LOGGER.info("Linux OS detected. Probing kernel hwmon, thermal and powercap sensors.");
            return new LinuxSensorReader();
        } else if (os.contains("win")) {
            LOGGER.info("Windows OS detected. LibreHardwareMonitor JSON source will be probed on localhost:8085.");
            return new WindowsLhmSensorReader(hardwareIdentityService, objectMapper, windowsLhmBootstrapService);
        }

        LOGGER.warning("No native hardware sensors could be attached. Using Fallback mode.");
        return new FallbackSensorReader(() -> -1.0);
    }
}
