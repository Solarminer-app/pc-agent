package de.verdox.solarminer.pcagent.mining.wallet;

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

    @Test
    void convertsQuanPoolPendingAndPaidPlanckToQtc() throws Exception {
        var response = mapper.readTree("""
                {"seen":true,"balance":{"pending":"110000000000","paid_total":"2250000000000"}}
                """);
        PoolBalanceProvider.PoolAccount account = QuanPoolBalanceProvider.parseAccount(response);
        assertEquals(new BigDecimal("0.11"), account.pending());
        assertEquals(new BigDecimal("2.25"), account.paidTotal());
    }

    @Test
    void treatsValidUnseenQuanPoolAddressAsKnownZero() throws Exception {
        PoolBalanceProvider.PoolAccount account = QuanPoolBalanceProvider.parseAccount(
                mapper.readTree("{\"seen\":false}"));
        assertEquals(BigDecimal.ZERO, account.pending());
        assertEquals(BigDecimal.ZERO, account.paidTotal());
    }

    @Test
    void rejectsIncompleteOrFractionalQuanPoolPlanckValues() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> QuanPoolBalanceProvider.parseAccount(mapper.readTree("{\"balance\":{}}")));
        assertThrows(IllegalArgumentException.class,
                () -> QuanPoolBalanceProvider.parseAccount(mapper.readTree(
                        "{\"seen\":true,\"balance\":{\"pending\":\"0.5\",\"paid_total\":\"0\"}}")));
    }

    @Test
    void recognizesOnlyQuantusOnQuanPoolHosts() {
        QuanPoolBalanceProvider provider = new QuanPoolBalanceProvider(mapper);
        assertEquals(true, provider.supports("stratum+tcp://global.quanpool.com:9854", "quantus"));
        assertEquals(false, provider.supports("stratum+tcp://evilquanpool.com:9854", "quantus"));
        assertEquals(false, provider.supports("stratum+tcp://global.quanpool.com:9854", "pearl"));
    }
}
