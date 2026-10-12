package de.verdox.solarminer.pcagent.miner.engine;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the shared route-validation and wallet-rule behaviour introduced in refactoring Phase 2. */
class RouteValidatorTest {

    @Test
    void poolAndProxyRegexesMatchTheHistoricalEngineValidators() {
        assertDoesNotThrow(() -> RouteValidator.validateRoute(
                new MinerConfig("stratum+ssl://pool.example:5555", "stratum+tcp://127.0.0.1:3336",
                        "R", "pc", "NVIDIA:0"),
                wallet -> true, "pool", "proxy", "wallet", "worker"));
        // A plain tcp pool is valid, a plain (non-stratum) pool or a TLS proxy is not.
        assertThrows(IllegalArgumentException.class, () -> RouteValidator.validateRoute(
                new MinerConfig("tcp://pool.example:3333", "stratum+tcp://127.0.0.1:3336",
                        "R", "pc", "NVIDIA:0"),
                wallet -> true, "pool", "proxy", "wallet", "worker"));
        assertThrows(IllegalArgumentException.class, () -> RouteValidator.validateRoute(
                new MinerConfig("stratum+tcp://pool.example:3333", "stratum+ssl://127.0.0.1:3336",
                        "R", "pc", "NVIDIA:0"),
                wallet -> true, "pool", "proxy", "wallet", "worker"));
        // Out-of-range ports must fail even though the digit regex would accept 5 digits.
        assertThrows(IllegalArgumentException.class, () -> RouteValidator.validateRoute(
                new MinerConfig("stratum+tcp://pool.example:99999", "stratum+tcp://127.0.0.1:3336",
                        "R", "pc", "NVIDIA:0"),
                wallet -> true, "pool", "proxy", "wallet", "worker"));
        // Worker bounds and the wallet hook being consulted exactly once.
        assertEquals("wallet", assertThrows(IllegalArgumentException.class, () -> RouteValidator.validateRoute(
                new MinerConfig("stratum+tcp://pool.example:3333", "stratum+tcp://127.0.0.1:3336",
                        "nope", "pc", "NVIDIA:0"),
                wallet -> false, "pool", "proxy", "wallet", "worker")).getMessage());
        assertEquals("worker", assertThrows(IllegalArgumentException.class, () -> RouteValidator.validateRoute(
                new MinerConfig("stratum+tcp://pool.example:3333", "stratum+tcp://127.0.0.1:3336",
                        "R", "way too long worker name for the shared limit!!", "NVIDIA:0"),
                wallet -> true, "pool", "proxy", "wallet", "worker")).getMessage());
    }

    @Test
    void deviceSyntaxDiffersPerEngineAsBefore() {
        // Pearl accepts 'all' and bare indexes; GPU coins require explicit vendor:index.
        assertDoesNotThrow(() -> RouteValidator.validateDevices("all",
                "^(all|((NVIDIA|AMD):)?[0-9]+(,((NVIDIA|AMD):)?[0-9]+)*)$", "pearl devices"));
        assertThrows(IllegalArgumentException.class, () -> RouteValidator.validateDevices("all",
                "^((NVIDIA|AMD):[0-9]+)(,((NVIDIA|AMD):[0-9]+))*$", "gpu devices"));
        assertThrows(IllegalArgumentException.class, () -> RouteValidator.validateDevices(null,
                "^((NVIDIA|AMD):[0-9]+)(,((NVIDIA|AMD):[0-9]+))*$", "gpu devices"));
    }

    @Test
    void withProxyRouteOnlyChangesTheProxyEndpoint() {
        MinerConfig current = new MinerConfig("stratum+tcp://pool.example:3333",
                "stratum+tcp://127.0.0.1:3336", "wallet", "pc", "NVIDIA:0");
        assertNull(RouteValidator.withProxyRoute(null, "stratum+tcp://127.0.0.1:3336"));
        assertNull(RouteValidator.withProxyRoute(current, current.proxyUrl()));
        MinerConfig updated = RouteValidator.withProxyRoute(current, "stratum+tcp://192.168.1.5:3336");
        assertNotNull(updated);
        assertEquals("stratum+tcp://192.168.1.5:3336", updated.proxyUrl());
        assertEquals(current.poolUrl(), updated.poolUrl());
        assertEquals(current.wallet(), updated.wallet());
        assertEquals(current.worker(), updated.worker());
        assertEquals(current.devices(), updated.devices());
    }

    @Test
    void walletRulesCoverEverySharedSrbCoinAndPearl() {
        assertTrue(WalletRule.forCoin(Coin.PEARL)
                .valid("prl1qz7q0q7q8f9h0q7q8f9h0q7q8f9h0q7q8f9h0q7q8f9h0q7q8f9h0q7q8f9h0q7q8"));
        assertFalse(WalletRule.forCoin(Coin.PEARL).valid("0x" + "a".repeat(40)));
        assertTrue(WalletRule.forCoin(Coin.RAVENCOIN).valid("RHaGK3iARQdKgZ6VPDP4N5chP3aVgUUfz7"));
        assertTrue(WalletRule.forCoin(Coin.ETHEREUMCLASSIC).valid("0x" + "a".repeat(40)));
        assertFalse(WalletRule.forCoin(Coin.ETHEREUMCLASSIC).valid("0x" + "a".repeat(39)));
        // Quantus keeps its loose bech32-style body rule.
        assertTrue(WalletRule.forCoin(Coin.QUANTUS).valid("qz" + "a".repeat(40)));
        // Unknown coins and missing identities never validate.
        assertFalse(WalletRule.forCoin(Coin.MONERO).valid("anything"));
        assertFalse(WalletRule.forCoin(null).valid("anything"));
    }
}
