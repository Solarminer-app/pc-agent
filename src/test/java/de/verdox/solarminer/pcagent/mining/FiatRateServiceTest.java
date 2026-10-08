package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FiatRateServiceTest {
    @Test
    void parsesSupportedUsdRatesUsingUppercaseUiCodes() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 6);
        FiatRateService.Snapshot snapshot = FiatRateService.parse(
                new ObjectMapper().readTree("{\"eur\":0.86,\"chf\":0.81,\"gbp\":0.75}"), date);

        assertEquals("USD", snapshot.baseCurrency());
        assertEquals(1.0, snapshot.rates().get("USD"));
        assertEquals(0.86, snapshot.rates().get("EUR"));
        assertEquals(0.81, snapshot.rates().get("CHF"));
        assertEquals(date, snapshot.dataUtcDate());
        assertFalse(snapshot.stale());
    }
}
