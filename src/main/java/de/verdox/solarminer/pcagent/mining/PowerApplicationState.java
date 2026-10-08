package de.verdox.solarminer.pcagent.mining;

/**
 * Last result of applying a global power request. This state is deliberately in-memory: after a
 * process restart no prior request is replayed and no miner is started without fresh consent.
 */
public final class PowerApplicationState {
    public enum Status { IDLE, APPLIED, PARTIALLY_APPLIED, PAUSED, REJECTED, FAILED }

    public record Snapshot(long requestedWatts, long plannedWatts, long appliedWatts,
                           long appliedCpuWatts, long appliedGpuWatts,
                           Status status, String failureReason) { }

    private Snapshot snapshot = new Snapshot(0, 0, 0, 0, 0, Status.IDLE, null);

    public synchronized Snapshot snapshot() { return snapshot; }

    public synchronized void applied(PowerBudgetPlanner.Plan plan) {
        snapshot = new Snapshot(plan.requestedWatts(), plan.plannedWatts(), plan.plannedWatts(),
                plan.cpuWatts(), plan.gpuWatts(), Status.APPLIED, null);
    }

    public synchronized void partiallyApplied(PowerBudgetPlanner.Plan requestedPlan,
                                               PowerBudgetPlanner.Plan appliedPlan, String reason) {
        snapshot = new Snapshot(requestedPlan.requestedWatts(), requestedPlan.plannedWatts(),
                appliedPlan.plannedWatts(), appliedPlan.cpuWatts(), appliedPlan.gpuWatts(),
                Status.PARTIALLY_APPLIED, reason);
    }

    public synchronized void paused(long requestedWatts) {
        snapshot = new Snapshot(requestedWatts, 0, 0, 0, 0, Status.PAUSED, null);
    }

    public synchronized void rejected(PowerBudgetPlanner.Plan plan) {
        snapshot = new Snapshot(plan.requestedWatts(), 0, 0, 0, 0, Status.REJECTED, plan.reason());
    }

    public synchronized void failed(PowerBudgetPlanner.Plan plan, String reason) {
        snapshot = new Snapshot(plan.requestedWatts(), plan.plannedWatts(), 0, 0, 0, Status.FAILED, reason);
    }
}
