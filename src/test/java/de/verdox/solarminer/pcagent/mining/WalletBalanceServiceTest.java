package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletBalanceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsPoolCoinUnitsWithoutAddingConfirmedTwice() throws Exception {
        var response = mapper.readTree("""
                {"total":0.06753581431297939,"unconfirmed":0.02312316644250534,"confirmed":0.044412647870474053}
                """);
        assertEquals(new BigDecimal("0.06753581431297939"), WalletBalanceService.parsePoolBalance(response));
    }

    @Test
    void convertsPearlGrainsToCoinUnits() throws Exception {
        var response = mapper.readTree("{\"balance\":231011}");
        assertEquals(new BigDecimal("0.00231011"), WalletBalanceService.parsePearlBalance(response));
    }

    @Test
    void missingBalanceIsUnavailableRatherThanZero() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> WalletBalanceService.parsePoolBalance(mapper.readTree("{}")));
        assertThrows(IllegalArgumentException.class,
                () -> WalletBalanceService.parsePearlBalance(mapper.readTree("{}")));
    }
}
