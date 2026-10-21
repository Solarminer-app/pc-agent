package de.verdox.solarminer.pcagent.miner;

/** Operator authority checked by every adapter, including measurement restoration. */
@FunctionalInterface
public interface WorkerStartPolicy {
    boolean mayStart(String deviceId);
}
