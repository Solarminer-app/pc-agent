package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.MinerConsoleService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
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

    public AgentPowerController(MiningService mining, LocalGpuPowerService gpus, AgentControlSettingsService controls, BenchmarkSessionService benchmarks, MinerConsoleService consoles, AgentIdentityService agentIdentity) { this.mining = mining; this.gpus = gpus; this.controls = controls; this.benchmarks = benchmarks; this.consoles = consoles; this.agentIdentity = agentIdentity; }

    @GetMapping("/identity")
    public Identity identity() { return new Identity("solarminer-pc-agent", 1, "SolarMiner PC Agent", agentIdentity.name()); }

    /** Local dashboard only. A blank name clears the label and restores the product default. */
    @PostMapping("/identity")
    public Identity rename(@RequestBody IdentityName request) {
        if (!agentIdentity.rename(request == null ? null : request.name()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Name darf höchstens " + AgentIdentityService.MAX_NAME_LENGTH + " Zeichen haben und keine Steuerzeichen enthalten");
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
        List<GpuStatus> cards = gpus.discover().stream().map(g -> new GpuStatus(g.deviceId(), g.vendor(), g.index(), g.model(),
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
                return externalStatus();
            }
            if (!mining.setExternalTarget(watts)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Leistungsziel konnte nicht sicher angewendet werden");
            logExternalChange(watts <= 0 ? "Mining paused remotely (power target 0 W)" : "Power target changed remotely to " + watts + " W");
            return externalStatus();
        }, true);
    }

    public boolean externalPause() { return withControl(() -> {
        boolean success = mining.pauseExternally();
        if (success) logExternalChange("Mining paused remotely");
        return success;
    }, true); }

    public boolean externalResume() { return withControl(() -> {
        boolean success = mining.resumeExternally();
        if (success) logExternalChange("Mining resumed remotely");
        return success;
    }, true); }

    private void logExternalChange(String message) {
        String line = Instant.now() + " [SolarMiner] [Remote Control] " + message;
        if (controls.workerEnabled("cpu")) consoles.append("monero", line);
        var selected = gpus.discover().stream().filter(gpu -> mining.externallySelected(gpu.deviceId())).toList();
        selected.stream().map(gpu -> controls.get().coinFor(gpu.deviceId())).distinct().forEach(coin -> consoles.append(coin, line));
        selected.forEach(gpu -> consoles.append(controls.get().coinFor(gpu.deviceId()) + "-" + gpu.vendor() + "-" + gpu.index(), line));
    }

    @GetMapping("/settings")
    public AgentControlSettingsService.Settings settings() { return controls.get(); }

    @PostMapping("/settings")
    public AgentControlSettingsService.Settings settings(@RequestBody AgentControlSettingsService.Settings value) {
        // Assignments go through /workers/{id}/coin so a running old assignment is stopped first.
        if (value == null || !controls.update(new AgentControlSettingsService.Settings(value.dynamicPowerScalingEnabled(),
                value.externalControlEnabled(), value.workerExternalControl(), controls.get().workerCoins())))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Lokale Steuereinstellungen konnten nicht gespeichert werden");
        return controls.get();
    }

    @PostMapping("/workers/{workerId}/external-control")
    public AgentControlSettingsService.Settings workerControl(@PathVariable String workerId, @RequestParam boolean enabled) {
        if (!"cpu".equals(workerId) && gpus.discover().stream().noneMatch(gpu -> gpu.deviceId().equals(workerId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker wurde nicht erkannt");
        if (!controls.setWorkerEnabled(workerId, enabled))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Worker-Freigabe konnte nicht gespeichert werden");
        String line = Instant.now() + " [SolarMiner] [Remote Control] External control for " + workerId + (enabled ? " enabled" : " disabled");
        if ("cpu".equals(workerId)) consoles.append("monero", line);
        else {
            consoles.append("pearl", line);
            gpus.discover().stream().filter(gpu -> gpu.deviceId().equals(workerId)).findFirst().ifPresent(gpu ->
                    consoles.append("pearl-" + gpu.vendor() + "-" + gpu.index(), line));
        }
        return controls.get();
    }

    /** Persistent local assignment; selecting a tab never changes this profile. */
    @PostMapping("/workers/{workerId}/coin")
    public AgentControlSettingsService.Settings workerCoin(@PathVariable String workerId, @RequestParam String coin) {
        if (!"cpu".equals(workerId) && gpus.discover().stream().noneMatch(g -> g.deviceId().equals(workerId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gerät wurde nicht erkannt");
        if (!java.util.Set.of("none", "monero", "pearl", "ravencoin", "ethereumclassic", "decred", "quantus").contains(coin)
                || ("cpu".equals(workerId) ? !("none".equals(coin) || "monero".equals(coin)) : "monero".equals(coin)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coin passt nicht zur Hardware");
        return withControl(() -> {
            if (!coin.equals(controls.get().coinFor(workerId)) && !mining.stopExternalWorker(workerId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Vorheriger Worker konnte nicht angehalten werden");
            if (!controls.setWorkerCoin(workerId, coin))
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Standard konnte nicht gespeichert werden");
            return controls.get();
        }, false);
    }

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
        if (request == null || !gpus.setUserLimits(deviceId, request.minimumWatts(), request.maximumWatts()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU-Leistungsgrenzen liegen außerhalb der aktuellen Treibergrenzen");
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
