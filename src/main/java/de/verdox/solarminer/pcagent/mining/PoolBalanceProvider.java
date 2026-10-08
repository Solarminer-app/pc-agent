package de.verdox.solarminer.pcagent.mining;

import java.math.BigDecimal;

/** Pool-specific adapter for reading a wallet's unpaid pool balance. */
public interface PoolBalanceProvider {
    /** Stable provider key used in cache keys and diagnostics. */
    String id();

    /** Whether this adapter recognizes the configured pool URL and canonical coin id. */
    boolean supports(String poolUrl, String coin);

    /** Reads the pool balance in native coin units; failures are reported as unavailable. */
    BigDecimal balance(String coin, String poolUrl, String wallet) throws Exception;
}
