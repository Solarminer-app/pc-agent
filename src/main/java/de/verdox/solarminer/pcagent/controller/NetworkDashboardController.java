package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.network.AgentNetworkDiscoveryService;
import de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Local dashboard contract for the LAN peer view: this agent plus every discovered peer agent
 * with its worker rows, earnings and aggregate energy totals. Start/pause actions are proxied
 * only when the target agent's existing LAN-sharing and external-control gates allow them.
 */
@RestController
@RequestMapping("/api/agent/local/network")
@Tag(name = "PC mining agent - network dashboard", description = "Local view of peer PC-Agents in the same LAN")
public class NetworkDashboardController {
    private final NetworkDashboardService dashboard;
    private final AgentNetworkDiscoveryService discovery;
    private final AgentControlSettingsService controls;

    public NetworkDashboardController(NetworkDashboardService dashboard,
                                      AgentNetworkDiscoveryService discovery,
                                      AgentControlSettingsService controls) {
        this.dashboard = dashboard;
        this.discovery = discovery;
        this.controls = controls;
    }

    public record NetworkView(boolean sharingEnabled, int peerCount,
                              List<NetworkDashboardService.AgentCard> agents) { }

    @GetMapping("/agents")
    public NetworkView agents() {
        List<NetworkDashboardService.AgentCard> cards = dashboard.cards();
        return new NetworkView(controls.get().networkSharingActive(),
                Math.max(0, cards.size() - 1), cards);
    }

    /** Local-browser-only proxy for one explicitly selected LAN peer's shared wallet targets. */
    @GetMapping("/agents/{agentId}/wallet-targets")
    public NetworkDashboardService.PeerWalletTargets walletTargets(@PathVariable String agentId) {
        return dashboard.walletTargets(agentId);
    }

    /** Immediate LAN worker action. The UI deliberately asks for no second confirmation. */
    @PostMapping("/agents/{agentId}/workers/{deviceId}")
    public NetworkDashboardService.WorkerControlResult controlWorker(@PathVariable String agentId,
                                                                      @PathVariable String deviceId,
                                                                      @RequestParam boolean enabled) {
        return dashboard.controlPeer(agentId, deviceId, enabled);
    }
}
