package de.verdox.solarminer.pcagent.miner;

import java.io.IOException;

/**
 * Algorithm-specific tuning capability a miner adapter exposes to the local dashboard
 * (refactoring Phase 5d: the former MiningOptimizationController's logic belongs to the
 * adapter, not to a CPU-specific controller). Only adapters whose algorithm understands the
 * optimization implement it; the REST layer resolves it through the MinerFactory and never
 * branches on coins itself.
 */
public interface RandomXCapability {
    /** Same wire shape the old optimization endpoint served to the UI. */
    record Status(boolean hugePagesActive, boolean hugePagesConfigured, boolean oneGbPagesActive,
                  boolean windowsPermissionAvailable, boolean restartRequired) { }

    Status randomXStatus();

    /**
     * Applies one named optimization ("huge-pages" or "1gb-pages") to the adapter's own
     * configuration. Whether the running process must be restarted afterwards is decided by
     * {@link #restartRequiredAfterToggle(boolean)} so the orchestrator keeps lifecycle
     * ownership (the adapter must not call back into the mining service).
     */
    Status applyRandomXOptimization(String setting, boolean enabled) throws IOException, InterruptedException;

    /** True when a toggle of this optimization takes effect only after a miner restart. */
    boolean restartRequiredAfterToggle(boolean enabled);
}
