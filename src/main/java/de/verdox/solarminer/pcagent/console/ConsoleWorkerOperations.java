package de.verdox.solarminer.pcagent.console;

import de.verdox.solarminer.pcagent.miner.MinerStartContext;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService;
import de.verdox.solarminer.pcagent.mining.network.AgentNetworkDiscoveryService;
import de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService;
import org.springframework.stereotype.Service;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;


/** Console command adapter over domain services; never invokes a legacy HTTP controller. */
@Service
public class ConsoleWorkerOperations {
    private final WorkerAssignmentService workers;
    private final MiningService mining;
    private final AgentControlSettingsService controls;
    private final WorkerCoinRevenueModeService revenue;
    private final AgentNetworkDiscoveryService discovery;
    private final NetworkDashboardService network;
    private final LocalRunLock runLock;

    public ConsoleWorkerOperations(WorkerAssignmentService workers, MiningService mining,
                                   AgentControlSettingsService controls, WorkerCoinRevenueModeService revenue,
                                   AgentNetworkDiscoveryService discovery, NetworkDashboardService network, LocalRunLock runLock) {
        this.workers = workers;
        this.mining = mining;
        this.controls = controls;
        this.revenue = revenue;
        this.discovery = discovery;
        this.network = network;
        this.runLock = runLock;
    }

    public String assign(ConsoleCommandService.Target target, ConsoleCommandService.Assignment assignment) {
        if (!discovery.agentId().equals(target.agentId())) return "console_local_configuration_only";
        if (!runLock.tryBegin("operations-assignment")) return "power_mode_busy";
        try {
            workers.assign(target.deviceId(), new WorkerAssignmentService.Assignment(assignment.coin(), assignment.minerSoftwareId()));
            revenue.resetCooldown(target.deviceId());
            return null;
        } catch (java.io.IOException failure) { return "console_preference_failed"; }
        finally { runLock.end(); }
    }

    public String apply(ConsoleCommandService.Target target, boolean enabled) {
        if (!discovery.agentId().equals(target.agentId())) {
            var result = network.controlPeer(target.agentId(), target.deviceId(), enabled);
            return result.applied() ? null : result.error() == null ? "console_action_failed" : result.error();
        }
        String id = target.deviceId();
        if (enabled && !mining.beginWorkerStart(id)) return "worker_start_in_progress";
        boolean locked = enabled && runLock.tryBegin("operations-start");
        if (enabled && !locked) { mining.endWorkerStart(id); return "power_mode_busy"; }
        try {
            var worker = workers.workers().stream().filter(row -> id.equals(row.deviceId())).findFirst().orElse(null);
            if (worker == null) return "console_worker_missing";
            boolean saved = controls.setWorkerManuallyStopped(id, !enabled);
            if (enabled && !saved) return "console_preference_failed";
            if (!enabled) {
                revenue.resetCooldown(id);
            }
            boolean applied = enabled ? MinerStartContext.operator(() -> workers.startWithHeldRunLock(id)) : workers.pause(id);
            if (!applied) return "console_action_failed";
            if (!saved) return "worker_stop_save_failed";
            return null;
        } finally {
            if (locked) runLock.end();
            if (enabled) mining.endWorkerStart(id);
        }
    }
}
