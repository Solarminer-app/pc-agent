package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
import de.verdox.solarminer.pcagent.mining.fee.FeeTransparencyService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MiningControllerEarningsTest {
    private final MarketDataService.Forecast gross = new MarketDataService.Forecast(
            "pearl", "PRL", true, null, 1_000, 10_000, 1, 60, 10, 2,
            100, 200, Instant.parse("2026-10-13T10:00:00Z"), false, List.of("network"));

    @Test
    void publishesNetForecastOnlyWhenEveryKnownFeeSourceIsResolved() {
        var fees = new FeeTransparencyService.FeeOverview("pearl", "default", "NODE_ATTACHED", true,
                List.of(new FeeTransparencyService.FeePart("SOLARMINER", "SolarMiner", 2.5, true, "fee backend"),
                        new FeeTransparencyService.FeePart("POOL", "Kryptex", 1, true, "Kryptex"),
                        new FeeTransparencyService.FeePart("MINER", "SRBMiner", 2, true, "miner docs")));

        var result = MiningController.withKnownFees(gross, fees);

        assertThat(result.grossCoinsPerDay()).isEqualTo(100);
        assertThat(result.knownFeePercent()).isEqualTo(5.5);
        assertThat(result.netCoinsPerDay()).isEqualTo(94.5);
        assertThat(result.netUsdPerDay()).isEqualTo(189);
        assertThat(result.knownFeesComplete()).isTrue();
    }

    @Test
    void doesNotPresentGrossRevenueAsNetWhenFeeRouteIsUnavailable() {
        var fees = new FeeTransparencyService.FeeOverview("pearl", "default", "STANDALONE", false,
                List.of(new FeeTransparencyService.FeePart("POOL", "Kryptex", 1, true, "Kryptex")));

        var result = MiningController.withKnownFees(gross, fees);

        assertThat(result.netCoinsPerDay()).isNull();
        assertThat(result.netUsdPerDay()).isNull();
        assertThat(result.knownFeePercent()).isNull();
        assertThat(result.knownFeesComplete()).isFalse();
    }
}
