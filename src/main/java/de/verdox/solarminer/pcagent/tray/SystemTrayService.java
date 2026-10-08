package de.verdox.solarminer.pcagent.tray;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class SystemTrayService {

    private static final Logger log = Logger.getLogger(SystemTrayService.class.getName());

    private final ApplicationContext context;

    public SystemTrayService(ApplicationContext context) {
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void setupSystemTray() {
        if (!SystemTray.isSupported()) {
            log.warning("System tray is not supported on this operating system.");
            return;
        }

        try {
            InputStream iconStream = getClass().getResourceAsStream("/icon.png");
            if (iconStream == null) {
                log.severe("Could not find icon.png in resources. Tray icon will not be created.");
                return;
            }
            Image image = ImageIO.read(iconStream);

            PopupMenu popup = new PopupMenu();

            MenuItem infoItem = new MenuItem("XMRig Status: Running");
            infoItem.setEnabled(false);
            popup.add(infoItem);

            popup.addSeparator();

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> exitApplication());
            popup.add(exitItem);

            TrayIcon trayIcon = new TrayIcon(image, "Solarminer.app - Agent", popup);
            trayIcon.setImageAutoSize(true);

            trayIcon.addActionListener(e -> log.info("Tray icon was double-clicked."));

            SystemTray tray = SystemTray.getSystemTray();
            tray.add(trayIcon);

            log.info("Successfully registered application in the OS system tray.");

        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to initialize system tray: " + e.getMessage(), e);
        }
    }

    private void exitApplication() {
        log.info("Exit requested via System Tray. Shutting down...");
        int exitCode = SpringApplication.exit(context, () -> 0);
        System.exit(exitCode);
    }
}