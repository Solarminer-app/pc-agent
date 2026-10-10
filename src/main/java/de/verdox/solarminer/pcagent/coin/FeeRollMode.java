package de.verdox.solarminer.pcagent.coin;

/** How the proxy rolls fee targets per job. */
public enum FeeRollMode {
    RANDOM("random"), STATEFUL("stateful");

    private final String wireName;

    FeeRollMode(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static FeeRollMode from(String value) {
        if (value == null) return null;
        for (FeeRollMode mode : values())
            if (mode.wireName.equalsIgnoreCase(value.strip())) return mode;
        return null;
    }
}
