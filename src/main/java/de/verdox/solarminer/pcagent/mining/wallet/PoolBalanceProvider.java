package de.verdox.solarminer.pcagent.mining.wallet;

import java.math.BigDecimal;

/** Pool/chain-specific adapter for reading wallet balances. */
public interface PoolBalanceProvider {
    /** Stable provider key used in cache keys and diagnostics. */
    String id();

    /** Whether this adapter recognizes the configured pool URL and canonical coin id. */
    boolean supports(String poolUrl, String coin);

    /** Reads the pool balance in native coin units; failures are reported as unavailable. */
    default BigDecimal balance(String coin, String poolUrl, String wallet) throws Exception {
        throw new UnsupportedOperationException(id() + " has no pool balance read");
    }

    /**
     * Whether this provider exposes an on-chain (non-pool) balance read for the coin,
     * independent of which pool the operator configured. The default is false, so coins
     * without a chain explorer stay "NOT_SUPPORTED" without any coin branch in the
     * coordinator (refactoring Phase 4: the Pearl special case became
     * {@code PearlChainBalanceProvider}).
     */
    default boolean supportsOnChain(String coin) { return false; }

    /** Reads the on-chain balance in native coin units; failures are reported as unavailable. */
    default BigDecimal onChainBalance(String coin, String wallet) throws Exception {
        throw new UnsupportedOperationException(id() + " has no on-chain balance read");
    }
}
