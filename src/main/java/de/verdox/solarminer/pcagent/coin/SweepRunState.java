package de.verdox.solarminer.pcagent.coin;

/**
 * Lifecycle of one efficiency-sweep measurement run (one coin x GPU slot). The wire names are
 * part of the local REST contract with the operator UI and of persisted sweep checkpoints.
 */
public enum SweepRunState {
    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    COMPLETE("COMPLETE"),
    FAILED("FAILED"),
    SKIPPED("SKIPPED"),
    CANCELLED("CANCELLED");

    private final String wireName;

    SweepRunState(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    /** True when the run will never change state again. */
    public boolean terminal() { return this == COMPLETE || this == FAILED || this == SKIPPED || this == CANCELLED; }
}
