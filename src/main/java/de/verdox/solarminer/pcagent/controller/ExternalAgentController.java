package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.sensor.HardwareTelemetryService;
import de.verdox.solarminer.pcagent.mining.NodeAssessmentService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/** Exclusive SolarMiner-Node contract. The global external-control gate protects this namespace. */
@RestController
@RequestMapping("/api/agent/external")
@Tag(name = "PC mining agent - SolarMiner Node", description = "Exclusive externally gated Node contract")
public class ExternalAgentController {
    private final MiningController mining;
    private final AgentPowerController power;
    private final TelemetryController telemetry;
    private final NodeAssessmentController assessment;

    public ExternalAgentController(MiningController mining, AgentPowerController power,
                                   TelemetryController telemetry, NodeAssessmentController assessment) {
        this.mining = mining;
        this.power = power;
        this.telemetry = telemetry;
        this.assessment = assessment;
    }

    @GetMapping("/identity")
    public AgentPowerController.Identity identity() { return power.identity(); }

    @GetMapping("/status")
    public MinerStats status() { return mining.getMiningStats(); }

    @GetMapping("/power-control")
    public AgentPowerController.PowerStatus powerControl() { return power.externalStatus(); }

    @GetMapping("/telemetry")
    public HardwareTelemetryService.Snapshot telemetry() { return telemetry.telemetry(); }

    @GetMapping("/overview")
    public MiningController.AgentOverview overview() { return mining.overview(); }

    @GetMapping("/proxy")
    public MiningController.ProxyOverview proxy() { return mining.proxy(); }

    @GetMapping("/earnings")
    public List<de.verdox.solarminer.pcagent.mining.EarningsForecastService.Forecast> earnings() {
        return mining.earnings();
    }

    @PostMapping("/pause")
    public boolean pause() { return power.externalPause(); }

    @PostMapping("/resume")
    public boolean resume() { return power.externalResume(); }

    @PostMapping("/power-target")
    public AgentPowerController.PowerStatus target(@RequestParam long watts) { return power.externalTarget(watts); }

    @PostMapping("/power-target/increment")
    public boolean increment(@RequestParam long watts) { return mining.increasePowerTarget(watts); }

    @PostMapping("/power-target/decrement")
    public boolean decrement(@RequestParam long watts) { return mining.decreasePowerTarget(watts); }

    @PostMapping("/proxy")
    public boolean configureProxy(@RequestParam String host) { return mining.configureProxy(host); }

    @PostMapping("/referral")
    public boolean referral(@RequestParam String key) { return mining.setReferral(key); }

    @PostMapping("/pool-configuration")
    public boolean poolConfiguration(@RequestParam String poolUrl, @RequestParam String poolUser,
                                     @RequestParam double devFeePercentage) throws IOException {
        return mining.setPoolConfiguration(poolUrl, poolUser, devFeePercentage);
    }

    @PostMapping("/monero/configuration")
    public boolean monero(@RequestBody MiningController.MoneroConfiguration request) throws IOException {
        return mining.setMoneroConfiguration(request);
    }

    @PostMapping("/pearl/configuration")
    public boolean pearl(@RequestBody PearlMinerService.Config request) throws IOException {
        return mining.setPearlConfiguration(request);
    }

    @PostMapping("/node-assessment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void nodeAssessment(@RequestBody NodeAssessmentService.Assessment request) {
        assessment.update(request);
    }
}
