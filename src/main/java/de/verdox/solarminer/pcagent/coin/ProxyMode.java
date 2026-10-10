package de.verdox.solarminer.pcagent.coin;

/** How the agent reaches its Stratum proxy: the bundled local proxy or a remote host. */
public enum ProxyMode {
    LOCAL("local"), EXTERNAL("external");

    private final String wireName;

    ProxyMode(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static ProxyMode from(String value) {
        if (value == null) return null;
        for (ProxyMode mode : values())
            if (mode.wireName.equalsIgnoreCase(value.strip())) return mode;
        return null;
    }
}
