package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Independent configuration boundary; no delegation to the existing UI controllers. */
@RestController
@RequestMapping("/api/agent/local/operations/configuration")
public class ConsoleConfigurationController {
    private final MinerCatalogService miners;
    private final AgentControlSettingsService controls;
    private final AgentIdentityService identity;
    public ConsoleConfigurationController(MinerCatalogService miners, AgentControlSettingsService controls,
                                          AgentIdentityService identity) {
        this.miners = miners; this.controls = controls; this.identity = identity;
    }
    public record Software(String id, String coin, String coinName, String name, String hardware,
                           boolean installed, boolean installable, boolean selectable, boolean experimental,
                           String downloadStatus, String unavailableReason) { }
    public record Settings(String name, boolean sharing, boolean externalControl) { }
    public record Installation(String coin, String minerSoftwareId) { }

    @GetMapping("/software")
    public ResponseEntity<List<Software>> software() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(miners.allOptions().stream().map(option ->
                new Software(option.id(), option.coin(), option.coinName(), option.name(),
                        Coin.byIdOrNull(option.coin()) != null && Coin.byIdOrNull(option.coin()).isGpu() ? "GPU" : "CPU",
                        option.installed(), option.installable(), option.selectable(), option.experimental(),
                        option.downloadStatus(), unavailableKey(option))).toList());
    }
    private static String unavailableKey(MinerCatalogService.MinerOption option) {
        if (option.unavailableReason() == null) return null;
        if (option.unavailableReason().contains("x86-64")) return "console_platform_required";
        if ("NOT_INTEGRATED".equals(option.downloadStatus())) return "console_adapter_unavailable";
        return "software_not_ready";
    }
    @PostMapping("/software")
    public ResponseEntity<Map<String, String>> install(@RequestBody Installation request) {
        if (request == null || request.coin() == null || request.minerSoftwareId() == null)
            return ResponseEntity.badRequest().body(Map.of("error", "console_invalid_command"));
        boolean accepted = miners.download(request.coin(), request.minerSoftwareId());
        return ResponseEntity.status(accepted ? 202 : 409).body(Map.of(accepted ? "status" : "error",
                accepted ? "console_install_accepted" : "console_install_rejected"));
    }
    @GetMapping
    public ResponseEntity<Settings> settings() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Settings(identity.displayName(),
                controls.get().networkSharingActive(), controls.get().externalControlEnabled()));
    }
    @PutMapping
    public synchronized ResponseEntity<Map<String, String>> settings(@RequestBody Settings request) {
        if (request == null || request.name() == null || request.name().length() > AgentIdentityService.MAX_NAME_LENGTH
                || request.name().chars().anyMatch(Character::isISOControl))
            return ResponseEntity.badRequest().body(Map.of("error", "console_invalid_command"));
        var current = controls.get();
        boolean saved = controls.update(new AgentControlSettingsService.Settings(current.dynamicPowerScalingEnabled(),
                request.externalControl(), current.workerExternalControl(), current.workerCoins(), current.workerCoinPolicies(),
                request.sharing(), current.walletTargetSharingEnabled()));
        if (!saved || !identity.rename(request.name()))
            return ResponseEntity.internalServerError().body(Map.of("error", "console_settings_partial"));
        return ResponseEntity.ok(Map.of("status", "console_settings_saved"));
    }
}
