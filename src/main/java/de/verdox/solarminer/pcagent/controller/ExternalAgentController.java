package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.lowlevel.sensor.HardwareTelemetryService;
import de.verdox.solarminer.pcagent.mining.agent.NodeAssessmentService;
import de.verdox.solarminer.pcagent.mining.economics.EconomicPlanningService;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.List;
import de.verdox.solarminer.pcagent.miner.MinerConfig;

/** SolarMiner-Node contract plus explicitly gated LAN peer snapshot/control exceptions. */
@RestController
@RequestMapping("/api/agent/external")
@Tag(name = "PC mining agent - external LAN", description = "Gated SolarMiner Node and peer-agent contract")
public class ExternalAgentController {
    private final MiningController mining;
    private final AgentPowerController power;
    private final TelemetryController telemetry;
    private final NodeAssessmentController assessment;
    private final EconomicPlanningService economicPlanning;
    private final de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService networkDashboard;

    @Autowired
    public ExternalAgentController(MiningController mining, AgentPowerController power,
                                   TelemetryController telemetry, NodeAssessmentController assessment,
                                   EconomicPlanningService economicPlanning,
                                   de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService networkDashboard) {
        this.mining = mining;
        this.power = power;
        this.telemetry = telemetry;
        this.assessment = assessment;
        this.economicPlanning = economicPlanning;
        this.networkDashboard = networkDashboard;
    }

    /** Compatibility constructor for focused controller tests from the pre-C10 contract. */
    ExternalAgentController(MiningController mining, AgentPowerController power,
                            TelemetryController telemetry, NodeAssessmentController assessment) {
        this(mining, power, telemetry, assessment, null, null);
    }

    /**
     * Read-only LAN visibility snapshot for the peer dashboard on other PC-Agents: worker
     * rows, per-coin earnings and aggregate energy totals for this machine only. The filter
     * gates it behind the explicit network-sharing consent; session history, wallets and config
     * never appear. Worker start/pause uses separate POST routes and additional control gates.
     */
    @GetMapping("/network-snapshot")
    public de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService.PeerSnapshot networkSnapshot() {
        return networkDashboard == null
                ? new de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService.PeerSnapshot(List.of(), List.of(),
                        new de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService.Totals(0, 0, 0, 0), null, false)
                : networkDashboard.ownSnapshot();
    }

    /** Immediate peer-dashboard action; the sharing and remote-control gates are enforced by the filter. */
    @PostMapping("/network-workers/{deviceId}/start")
    public boolean startNetworkWorker(@org.springframework.web.bind.annotation.PathVariable String deviceId) {
        return networkDashboard != null && networkDashboard.controlOwnWorker(deviceId, true);
    }

    @PostMapping("/network-workers/{deviceId}/pause")
    public boolean pauseNetworkWorker(@org.springframework.web.bind.annotation.PathVariable String deviceId) {
        return networkDashboard != null && networkDashboard.controlOwnWorker(deviceId, false);
    }

    @GetMapping("/identity")
    public AgentPowerController.Identity identity() { return power.identity(); }

    /** Read-only LAN import contract; the access filter requires explicit wallet sharing consent. */
    @GetMapping("/wallet-targets")
    public List<MiningController.WalletTarget> walletTargets() { return mining.walletTargets(); }

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
    public List<MiningController.NodeEarningsForecast> earnings() {
        return mining.nodeEarnings();
    }

    /** Versioned economic-dispatch contract; all writes remain subject to the local opt-in gate. */
    @GetMapping("/capabilities")
    public EconomicPlanningService.Capabilities capabilities() { return economicPlanning.capabilities(); }

    @PostMapping("/economic-plan")
    public EconomicPlanningService.PlanResult economicPlan(@RequestBody EconomicPlanningService.PlanRequest request) {
        return economicPlanning.apply(request);
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
    public boolean pearl(@RequestBody MinerConfig request) throws IOException {
        return mining.setPearlConfiguration(request);
    }

    /** Node route for the Agent's generic GPU adapters; the owning adapter validates the config. */
    @PostMapping("/gpu-coin/{coin}/configuration")
    public boolean gpuCoin(@org.springframework.web.bind.annotation.PathVariable String coin,
                           @RequestBody MinerConfig request) throws IOException {
        Coin parsed = Coin.byIdOrNull(coin);
        if (parsed == null || !parsed.isGpu()) return false;
        return mining.setCoinConfiguration(coin, request);
    }

    @PostMapping("/node-assessment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void nodeAssessment(@RequestBody NodeAssessmentService.Assessment request) {
        assessment.update(request);
    }
}
