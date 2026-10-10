package de.verdox.solarminer.pcagent.coin;

/**
 * How one efficiency-sweep run measures: FULL builds the complete reference curve for a
 * cohort; VALIDATION only re-checks sibling devices against that reference. The wire names
 * are part of the local REST contract and of persisted sweep checkpoints.
 */
public enum SweepMode {
    FULL("FULL"),
    VALIDATION("VALIDATION");

    private final String wireName;

    SweepMode(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }
}
