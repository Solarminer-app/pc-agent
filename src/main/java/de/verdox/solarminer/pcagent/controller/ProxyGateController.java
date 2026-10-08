package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.ManagedProxyService;
import de.verdox.solarminer.pcagent.mining.ProxyConfigurationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Reports the boot gate that keeps the dashboard closed until the downloaded Stratum proxy is
 * running. Dashboard-only, like every other local agent route.
 */
@RestController
@RequestMapping("/api/agent/local/proxy-gate")
@Tag(name = "PC mining agent")
public class ProxyGateController {
    private final ProxyConfigurationService proxyConfigurationService;

    public ProxyGateController(ProxyConfigurationService proxyConfigurationService) {
        this.proxyConfigurationService = proxyConfigurationService;
    }

    @GetMapping
    public ManagedProxyService.ProxyGate gate() { return proxyConfigurationService.managedGate(); }

    @GetMapping("/log")
    public ManagedProxyService.ProxyLogChunk log(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long offset)
            throws IOException {
        return proxyConfigurationService.managedProxyLog(offset);
    }

    @PostMapping("/retry")
    public boolean retry() { return proxyConfigurationService.retryManagedProxy(); }
}
