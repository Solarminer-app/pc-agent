package de.verdox.solarminer.pcagent.mining.economics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoinHoppingLedgerTest {
    @TempDir Path directory;

    @Test
    void comparesModelledHoppingAgainstBestFixedCoinAndSurvivesRestart() {
        Path file = directory.resolve("ledger.json");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        CoinHoppingLedger ledger = new CoinHoppingLedger(json, file.toString());
        Instant t0 = Instant.parse("2026-10-16T10:00:00Z");
        ledger.begin("gpu-1", 180, t0);
        ledger.account(t0.plusSeconds(600), "pearl", t0,
                List.of(candidate("pearl", 2), candidate("quantus", 1)));
        ledger.action(t0.plusSeconds(600), "coin_hopping_switched", "pearl", "quantus", 100, 2, 4);
        ledger.account(t0.plusSeconds(1200), "quantus", t0.plusSeconds(600),
                List.of(candidate("pearl", 1), candidate("quantus", 2)));

        CoinHoppingLedger.Summary summary = new CoinHoppingLedger(json, file.toString()).summary();
        assertEquals(1200, summary.accountedSeconds());
        assertEquals(4 * 600d / 86400d, summary.actualUsd(), 0.000001);
        assertEquals(3 * 600d / 86400d, summary.bestFixedUsd(), 0.000001);
        assertEquals(600d / 86400d, summary.gainUsd(), 0.000001);
        assertEquals(1, summary.actions().size());
        assertTrue(summary.actualCoins().containsKey("quantus"));
    }

    @Test
    void unavailablePeriodIsNotBackfilled() {
        CoinHoppingLedger ledger = new CoinHoppingLedger(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("gaps.json").toString());
        Instant t0 = Instant.parse("2026-10-16T10:00:00Z");
        ledger.begin("gpu-1", 180, t0);
        ledger.mark(t0.plusSeconds(600));
        ledger.account(t0.plusSeconds(1200), "pearl", t0,
                List.of(candidate("pearl", 2), candidate("quantus", 1)));
        assertEquals(600, ledger.summary().accountedSeconds());
    }

    @Test
    void fixedCoinUsesTheSameEnergyAsTheActiveCoin() {
        CoinHoppingLedger ledger = new CoinHoppingLedger(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("energy.json").toString());
        Instant t0 = Instant.parse("2026-10-16T10:00:00Z");
        ledger.begin("gpu-1", 180, t0);
        ledger.account(t0.plusSeconds(1200), "pearl", t0,
                List.of(candidate("pearl", 4, 200), candidate("quantus", 3, 100)));
        CoinHoppingLedger.Summary summary = ledger.summary();
        assertEquals(4d / 72d, summary.actualUsd(), 0.000001);
        // Quantus makes less per day at half the power, but more per kWh.
        assertEquals(3d / 36d, summary.bestFixedUsd(), 0.000001);
        assertEquals("quantus", summary.bestFixedCoin());
        assertEquals(-1d / 36d, summary.gainUsd(), 0.000001);
    }

    private static CoinHoppingService.Candidate candidate(String coin, double usdPerDay) {
        return candidate(coin, usdPerDay, 170);
    }

    private static CoinHoppingService.Candidate candidate(String coin, double usdPerDay, double watts) {
        return new CoinHoppingService.Candidate(coin, 180, watts, 100, usdPerDay / (watts * 24 / 1000),
                Instant.parse("2026-10-16T09:00:00Z"), Instant.parse("2026-10-16T09:59:00Z"),
                "LIVE", usdPerDay, usdPerDay);
    }
}
