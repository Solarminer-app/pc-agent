package de.verdox.solarminer.pcagent.coin;

/** Worker coin-selection policy persisted per worker. AUTO is an explicit local opt-in. */
public enum WorkerCoinPolicy {
    AUTO, FIXED;

    public static WorkerCoinPolicy from(String value) {
        return "AUTO".equalsIgnoreCase(value == null ? null : value.strip()) ? AUTO : FIXED;
    }
}
