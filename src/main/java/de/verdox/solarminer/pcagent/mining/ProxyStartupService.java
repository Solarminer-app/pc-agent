package de.verdox.solarminer.pcagent.mining;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Applies the stored routing choice and always starts the downloaded loopback proxy on first start. */
@Service
public class ProxyStartupService {
    private final ProxyConfigurationService proxy;

    public ProxyStartupService(ProxyConfigurationService proxy) {
        this.proxy = proxy;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void configureInitialProxy() {
        if (proxy.hasStoredMode()) {
            proxy.activateStoredMode();
            return;
        }
        // A LAN proxy is only offered in the dashboard; the agent always downloads and starts its
        // own proxy first, so the operator never mines through an unreviewed server by accident.
        proxy.setMode("local");
    }
}
