package de.verdox.solarminer.pcagent.xmr;

import de.verdox.solarminer.pcagent.xmr.download.XmrDownloadService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.logging.Logger;

@Service
@EnableScheduling
public class XmrWatchdogService {
    private static final Logger LOGGER = Logger.getLogger(XmrWatchdogService.class.getName());
    private final XmrMinerService xmrMinerService;

    public XmrWatchdogService(XmrMinerService xmrMinerService) {
        this.xmrMinerService = xmrMinerService;
    }

    @Scheduled(fixedRate = 30000)
    public void monitorConfigWritability() {
        File configFile = XmrDownloadService.CONFIG_PATH.toFile();
        if (!configFile.exists()) {
            return;
        }

        if (!configFile.canWrite()) {
            LOGGER.severe("SECURITY ALERT: config.json is no longer writable! Triggering Failure Switch.");
            triggerFailureSwitch();
        }
    }

    private void triggerFailureSwitch() {
        LOGGER.info("Stopping XMRig process to prevent unauthorized mining...");
        if (XmrDownloadService.CONFIG_PATH.toFile().delete()) {
            LOGGER.info("Tampered config.json was deleted.");
        } else {
            LOGGER.warning("Could not delete tampered config.json.");
        }
        xmrMinerService.hardStopMining();
    }
}
