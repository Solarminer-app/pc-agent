package de.verdox.solarminer.pcagent.lowlevel;

import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.NetworkIF;
import oshi.hardware.GraphicsCard;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

@Service
public class HardwareIdentityService {

    private static final Logger LOGGER = Logger.getLogger(HardwareIdentityService.class.getName());

    private final String macAddress;
    private final UUID deterministicUuid;
    private final String processor;
    private final List<String> graphicsCards;

    public HardwareIdentityService() {
        SystemInfo systemInfo = new SystemInfo();
        this.processor = systemInfo.getHardware().getProcessor().getProcessorIdentifier().getName();
        this.graphicsCards = systemInfo.getHardware().getGraphicsCards().stream()
                .map(GraphicsCard::getName).filter(name -> name != null && !name.isBlank()).distinct().toList();
        this.macAddress = determinePrimaryMacAddress(systemInfo);

        this.deterministicUuid = UUID.nameUUIDFromBytes(this.macAddress.getBytes(StandardCharsets.UTF_8));

        LOGGER.info("Hardware Identity initialized. MAC: " + this.macAddress + " | UUID: " + this.deterministicUuid +" | Processor: "+processor);
    }

    public String getProcessor() {
        return processor;
    }

    public List<String> getGraphicsCards() { return graphicsCards; }

    private String determinePrimaryMacAddress(SystemInfo systemInfo) {
        List<NetworkIF> networkIFs = systemInfo.getHardware().getNetworkIFs();

        for (NetworkIF net : networkIFs) {
            String mac = net.getMacaddr();
            if (mac != null && !mac.isBlank() && !mac.equals("00:00:00:00:00:00")) {
                return mac.toUpperCase();
            }
        }

        LOGGER.warning("Could not find a valid MAC address. Falling back to default identity.");
        return "UNKNOWN-MAC-ADDRESS";
    }

    public String getMacAddress() {
        return macAddress;
    }

    public UUID getDeterministicUuid() {
        return deterministicUuid;
    }
}
