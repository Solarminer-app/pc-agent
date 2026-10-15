package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.time.Instant;

/** Stable LAN contract for PV controllers. It intentionally hides CPU/GPU allocation details. */
@RestController
@RequestMapping("/api/agent/local/power-control")
public class AgentPowerController {
    private final MiningService mining;
    private final LocalGpuPowerService gpus;
    private final AgentControlSettingsService controls;
    private final BenchmarkSessionService benchmarks;
    private final MinerConsoleService consoles;
    private final AgentIdentityService agentIdentity;
    /** Loading plan P3: mutations report affected sections/channels for the event-driven push. */
    private final AgentStatusService status;

    public AgentPowerController(MiningService mining, LocalGpuPowerService gpus, AgentControlSettingsService controls,
                                BenchmarkSessionService benchmarks, MinerConsoleService consoles,
                                AgentIdentityService agentIdentity,
                                @org.springframework.context.annotation.Lazy AgentStatusService status) {
        this.mining = mining; this.gpus = gpus; this.controls = controls; this.benchmarks = benchmarks;
        this.consoles = consoles; this.agentIdentity = agentIdentity; this.status = status;
    }

    private void reportChanged(String... names) { if (status != null) status.invalidate(names); }

    @GetMapping("/identity")
    public Identity identity() { return new Identity("solarminer-pc-agent", 1, "SolarMiner PC Agent", agentIdentity.name()); }

    /** Local dashboard only. A blank name clears the label and restores the product default. */
    @PostMapping("/identity")
    public Identity rename(@RequestBody IdentityName request) {
        if (!agentIdentity.rename(request == null ? null : request.name()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Name darf höchstens " + AgentIdentityService.MAX_NAME_LENGTH + " Zeichen haben und keine Steuerzeichen enthalten");
        reportChanged("network-agents");
        return identity();
    }

    @GetMapping
    public PowerStatus status() {
        return status(false);
    }

    /** Node-only view omits locally opted-out devices and their identifiers. */
    public PowerStatus externalStatus() {
        return status(true);
    }

    private PowerStatus status(boolean externalView) {
        AgentControlSettingsService.Settings owner = controls.get();
        // REST audit R6: the read model uses the last completed driver snapshot; driver
        // discovery (nvidia-smi/amd-smi processes) stays in the background refresher and in
        // command paths. A slow driver never slows this endpoint down.
        List<GpuStatus> cards = gpus.snapshot().stream().map(g -> new GpuStatus(g.deviceId(), g.vendor(), g.index(), g.model(),
                g.driverMinWatts(), g.driverMaxWatts(), g.minWatts(), g.maxWatts(), g.currentPowerLimitWatts(),
                g.currentWatts(), g.usageMeasurement(), g.supportsDynamicPowerScaling(), g.regulationError(), mining.externallySelected(g.deviceId()))).toList();
        if (externalView) cards = owner.externalControlEnabled()
                ? cards.stream().filter(GpuStatus::externalControlEnabled).toList() : List.of();
        long min = mining.calculateMinPowerTargetFromComponents(), max = mining.calculateMaxPowerTargetFromComponents();
        List<GpuStatus> visibleCards = cards.stream().filter(GpuStatus::externalControlEnabled).toList();
        boolean measured = owner.externalControlEnabled() && mining.desiredCpuPowerTarget() == 0 && !visibleCards.isEmpty()
                && visibleCards.stream().allMatch(c -> "measured".equals(c.usageMeasurement()));
        Long usage = measured ? visibleCards.stream().map(GpuStatus::currentUsageWatts).mapToLong(Math::round).sum() : null;
        var application = mining.powerApplication();
        return new PowerStatus(owner.dynamicPowerScalingEnabled() && cards.stream().anyMatch(g -> g.externalControlEnabled() && g.supportsDynamicPowerScaling()), owner.dynamicPowerScalingEnabled(), owner.externalControlEnabled(), min, max, max == 0 ? 0 : Math.min(max, Math.max(min, max / 2)),
                mining.desiredGlobalPowerTarget(), usage,
                measured ? "measured" : "unavailable", mining.desiredGlobalPowerTarget() == 0,
                externalView ? Map.of() : owner.workerExternalControl(), cards,
                application.requestedWatts(), application.appliedWatts(), application.appliedCpuWatts(), application.appliedGpuWatts(),
                application.status().name(), application.failureReason(), externalView ? Map.of() : (owner.workerCoins() == null ? Map.of() : owner.workerCoins()));
    }

    @PostMapping("/target")
    public PowerStatus target(@RequestParam long watts) {
        return withControl(() -> {
            if (!mining.setTarget(watts)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Leistungsziel konnte nicht sicher angewendet werden");
            reportChanged("overview", "power-control", "workers");
            return status();
        }, false);
    }

    /** Node-only command path; local dashboard actions intentionally use their existing endpoints. */
    public PowerStatus externalTarget(@RequestParam long watts) {
        return withControl(() -> {
            // Dynamic GPU regulation is optional. When it is off, a positive target
            // still means that the Node may start this fixed-power miner.
            if (watts > 0 && !controls.get().dynamicPowerScalingEnabled()) {
                if (!mining.resumeExternally())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Miner konnte nicht gestartet werden");
                logExternalChange("Mining started remotely (power target " + watts + " W)");
                reportChanged("overview", "power-control", "workers");
                return externalStatus();
            }
            if (!mining.setExternalTarget(watts)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Leistungsziel konnte nicht sicher angewendet werden");
            logExternalChange(watts <= 0 ? "Mining paused remotely (power target 0 W)" : "Power target changed remotely to " + watts + " W");
            reportChanged("overview", "power-control", "workers");
            return externalStatus();
        }, true);
    }

    public boolean externalPause() { return withControl(() -> {
        boolean success = mining.pauseExternally();
        if (success) { logExternalChange("Mining paused remotely"); reportChanged("overview", "power-control", "workers"); }
        return success;
    }, true); }

    public boolean externalResume() { return withControl(() -> {
        boolean success = mining.resumeExternally();
        if (success) { logExternalChange("Mining resumed remotely"); reportChanged("overview", "power-control", "workers"); }
        return success;
    }, true); }

    private void logExternalChange(String message) {
        String line = Instant.now() + " [SolarMiner] [Remote Control] " + message;
        if (controls.workerEnabled(de.verdox.solarminer.pcagent.coin.WorkerIds.CPU))
            consoles.append(de.verdox.solarminer.pcagent.coin.Coin.MONERO.id(), line);
        var selected = gpus.snapshot().stream().filter(gpu -> mining.externallySelected(gpu.deviceId())).toList();
        selected.stream().map(gpu -> controls.get().coinFor(gpu.deviceId())).distinct().forEach(coin -> consoles.append(coin, line));
        selected.forEach(gpu -> consoles.append(controls.get().coinFor(gpu.deviceId()) + "-" + gpu.vendor() + "-" + gpu.index(), line));
    }

    @GetMapping("/settings")
    public AgentControlSettingsService.Settings settings() { return controls.get(); }

    @PostMapping("/settings")
    public AgentControlSettingsService.Settings settings(@RequestBody AgentControlSettingsService.Settings value) {
        // Assignments go through /workers/{id}/coin so a running old assignment is stopped first.
        if (value == null || !controls.update(new AgentControlSettingsService.Settings(value.dynamicPowerScalingEnabled(),
                value.externalControlEnabled(), value.workerExternalControl(), controls.get().workerCoins(),
                controls.get().workerCoinPolicies(), value.networkSharingEnabled())))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Lokale Steuereinstellungen konnten nicht gespeichert werden");
        reportChanged("settings", "power-control", "overview", "network-agents");
        return controls.get();
    }

    /**
     * Explicit LAN-visibility consent for the peer dashboard. By itself this only publishes
     * worker/earnings rows and aggregate energy; peer start/pause additionally requires the
     * existing global and per-worker remote-control permissions.
     */
    @PostMapping("/network-sharing")
    public AgentControlSettingsService.Settings networkSharing(@RequestParam boolean enabled) {
        AgentControlSettingsService.Settings current = controls.get();
        if (!controls.update(new AgentControlSettingsService.Settings(current.dynamicPowerScalingEnabled(),
                current.externalControlEnabled(), current.workerExternalControl(), current.workerCoins(),
                current.workerCoinPolicies(), enabled)))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "LAN-Sichtbarkeit konnte nicht gespeichert werden");
        reportChanged("settings", "network-agents");
        return controls.get();
    }

    /** Separate consent because a payout address is more sensitive than worker telemetry. */
    @PostMapping("/wallet-target-sharing")
    public AgentControlSettingsService.Settings walletTargetSharing(@RequestParam boolean enabled) {
        AgentControlSettingsService.Settings current = controls.get();
        if (!controls.update(new AgentControlSettingsService.Settings(current.dynamicPowerScalingEnabled(),
                current.externalControlEnabled(), current.workerExternalControl(), current.workerCoins(),
                current.workerCoinPolicies(), current.networkSharingEnabled(), enabled)))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Wallet-Freigabe im LAN konnte nicht gespeichert werden");
        reportChanged("settings");
        return controls.get();
    }

    // Phase 5d (owner decision O3): the worker-scoped consent/assignment routes that used to
    // live under /workers/* here were merged into WorkerController when the dashboard moved
    // over. /workers/{id}/coin and /workers/{id}/coin-policy had no remaining consumer after
    // the worker page switched to /workers/{id}/assignment and were deleted with the merge.

    private <T> T withControl(java.util.function.Supplier<T> command, boolean external) {
        try {
            return benchmarks.withExternalControl(() -> {
                if (external) requireExternalControl();
                return command.get();
            });
        } catch (BenchmarkSessionService.BenchmarkRunningException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Node-Steuerung ist während eines Benchmarks vorübergehend gesperrt");
        }
    }

    private void requireExternalControl() {
        if (!controls.get().externalControlEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Externe Steuerung wurde lokal deaktiviert");
    }

    @PostMapping("/gpus/{deviceId}/limits")
    public GpuStatus limits(@PathVariable String deviceId, @RequestBody UserLimits request) {
        if (request == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU-Leistungsgrenzen fehlen");
        boolean applied;
        try {
            applied = benchmarks.withExternalControl(() -> gpus.setUserLimits(deviceId, request.minimumWatts(), request.maximumWatts()));
        } catch (BenchmarkSessionService.BenchmarkRunningException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GPU-Leistungsgrenzen können während eines Messlaufs nicht geändert werden");
        }
        if (!applied)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU-Leistungsgrenzen konnten nicht gespeichert oder vom Treiber verifiziert werden");
        reportChanged("power-control", "overview");
        return status().gpus().stream().filter(g -> g.deviceId().equals(deviceId)).findFirst().orElseThrow();
    }

    public record UserLimits(int minimumWatts, int maximumWatts) { }
    /** {@code name} is the operator label and stays null when unset; {@code model} is the fixed product identity. */
    public record Identity(String kind, int powerControlProtocolVersion, String model, String name) { }
    public record IdentityName(String name) { }
    public record PowerStatus(boolean supportsDynamicPowerScaling, boolean dynamicPowerScalingEnabled, boolean externalControlEnabled, long minPowerWatts, long maxPowerWatts,
                              long defaultPowerWatts, long currentTargetWatts, Long currentUsageWatts,
                              String usageMeasurement, boolean miningPaused, Map<String, Boolean> workerExternalControl, List<GpuStatus> gpus,
                              long requestedTargetWatts, long appliedTargetWatts, long appliedCpuTargetWatts, long appliedGpuTargetWatts,
                              String targetApplicationStatus, String targetApplicationFailureReason, Map<String, String> workerCoins) { }
    public record GpuStatus(String deviceId, String vendor, int index, String model, int driverMinPowerLimitWatts,
                            int driverMaxPowerLimitWatts, int userMinPowerLimitWatts, int userMaxPowerLimitWatts,
                            Integer currentPowerLimitWatts, Double currentUsageWatts, String usageMeasurement,
                            boolean supportsDynamicPowerScaling, String regulationError, boolean externalControlEnabled) { }
}
