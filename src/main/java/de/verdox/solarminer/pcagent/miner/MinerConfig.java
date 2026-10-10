package de.verdox.solarminer.pcagent.miner;

/**
 * The coin-independent miner route configuration shared by every adapter. All locally managed
 * miners take exactly these five fields; coin-specific meaning (wallet format, device syntax)
 * is validated by the owning adapter, never by orchestration code.
 */
public record MinerConfig(String poolUrl, String proxyUrl, String wallet, String worker, String devices) { }
