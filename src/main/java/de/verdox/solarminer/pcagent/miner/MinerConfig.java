package de.verdox.solarminer.pcagent.miner;

/**
 * The coin-independent miner route configuration shared by every adapter. All locally managed
 * miners take exactly these five fields; coin-specific meaning (wallet format, device syntax)
 * is validated by the owning adapter, never by orchestration code.
 */
public record MinerConfig(String poolUrl, String proxyUrl, String wallet, String worker, String devices) {
    /** Device selection alone does not change the command of any retained worker. */
    public boolean sameRoute(MinerConfig other) {
        return other != null && java.util.Objects.equals(poolUrl, other.poolUrl)
                && java.util.Objects.equals(proxyUrl, other.proxyUrl)
                && java.util.Objects.equals(wallet, other.wallet)
                && java.util.Objects.equals(worker, other.worker);
    }

    public boolean selects(String vendor, int index) {
        return devices != null && java.util.Arrays.stream(devices.split(","))
                .anyMatch(device -> device.equals(vendor + ":" + index));
    }
}
