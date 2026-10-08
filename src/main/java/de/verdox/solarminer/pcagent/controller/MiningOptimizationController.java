package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.xmr.WindowsHugePagesService;
import de.verdox.solarminer.pcagent.xmr.XmrConfigService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.xmr.download.XmrDownloadService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Map;

/** Program-controlled, algorithm-specific mining settings exposed to the local agent UI. */
@RestController
@RequestMapping("/api/agent/local/optimizations/randomx")
public class MiningOptimizationController {
    private final XmrConfigService config;
    private final WindowsHugePagesService windowsHugePages;
    private final XmrMinerService miner;
    private final MiningService mining;

    public MiningOptimizationController(XmrConfigService config, WindowsHugePagesService windowsHugePages,
                                        XmrMinerService miner, MiningService mining) {
        this.config = config;
        this.windowsHugePages = windowsHugePages;
        this.miner = miner;
        this.mining = mining;
    }

    @GetMapping
    public Status status() {
        boolean hugePages = config.hugePagesEnabled(XmrDownloadService.CONFIG_PATH);
        boolean oneGb = config.randomXOneGbPagesEnabled(XmrDownloadService.CONFIG_PATH);
        boolean permission = windowsHugePages.availableInCurrentSession();
        return new Status(hugePages && (!windowsHugePages.isWindows() || permission), hugePages,
                oneGb && "linux".equals(platform()), permission,
                windowsHugePages.isWindows() && hugePages && !permission);
    }

    @PostMapping("/{setting}")
    public Status activate(@PathVariable String setting, @RequestBody Toggle request) throws IOException, InterruptedException {
        if (request == null) throw new IllegalArgumentException("Optimierungseinstellung fehlt");
        boolean wasRunning = miner.isMiningProcessAlive();
        if ("huge-pages".equals(setting)) {
            if (windowsHugePages.isWindows()) windowsHugePages.configureForCurrentUser(request.enabled());
            config.setHugePages(XmrDownloadService.CONFIG_PATH, request.enabled());
        } else if ("1gb-pages".equals(setting) && "linux".equals(platform())) {
            config.setRandomXOneGbPages(XmrDownloadService.CONFIG_PATH, request.enabled());
        } else {
            if (!"1gb-pages".equals(setting) || request.enabled())
                throw new IllegalArgumentException("Diese RandomX-Optimierung wird auf diesem Betriebssystem nicht unterstützt");
        }
        if (wasRunning && (!request.enabled() || !windowsHugePages.isWindows() || windowsHugePages.availableInCurrentSession())) {
            mining.pauseMining("monero");
            mining.resumeMining("monero");
        }
        return status();
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") ? "linux" : os.contains("win") ? "windows" : "other";
    }

    @ExceptionHandler({IOException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> activationFailed(Exception exception) {
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        String message = exception.getMessage() == null ? "Die Mining-Optimierung konnte nicht geändert werden" : exception.getMessage();
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    public record Toggle(boolean enabled) { }
    public record Status(boolean hugePagesActive, boolean hugePagesConfigured, boolean oneGbPagesActive,
                         boolean windowsPermissionAvailable, boolean restartRequired) { }
}
