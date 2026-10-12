package de.verdox.solarminer.pcagent.miner.engine;

import de.verdox.solarminer.pcagent.coin.Coin;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Coin-owned payout-address rule (refactoring Phase 2). Replaces the per-coin wallet switch
 * that lived inside the GPU-coin validator: a new SRB coin adds one entry here (plus its
 * dialect flags) instead of editing engine validation code.
 */
@FunctionalInterface
public interface WalletRule {

    /** Pearl payout addresses: bech32-style {@code prl1} with a restricted character set. */
    WalletRule PEARL = wallet -> wallet != null
            && wallet.matches("^prl1[023456789acdefghjklmnpqrstuvwxyz]{20,120}$");
    /** Ravencoin: base58check P2PKH (R…) / P2SH (r…) with full checksum verification. */
    WalletRule RAVENCOIN = RouteValidation::ravencoinAddress;
    /** Ethereum Classic: 0x + 40 hex characters. */
    WalletRule ETHEREUMCLASSIC = wallet -> wallet != null && wallet.matches("^0x[0-9a-fA-F]{40}$");
    /** Decred: base58check main-net P2PKH/P2SH with full checksum verification. */
    WalletRule DECRED = RouteValidation::decredAddress;
    /** Quantus: bech32-style {@code qz} address body. */
    WalletRule QUANTUS = wallet -> wallet != null && wallet.matches("qz[1-9A-HJ-NP-Za-km-z]{38,58}");
    /** Unknown coins never validate; the owning adapter must provide a real rule. */
    WalletRule NONE = wallet -> false;

    boolean valid(String wallet);

    /** The single wallet-rule table keyed by the closed {@link Coin} identity. */
    static WalletRule forCoin(Coin coin) {
        if (coin == null) return NONE;
        return switch (coin) {
            case PEARL -> PEARL;
            case RAVENCOIN -> RAVENCOIN;
            case ETHEREUMCLASSIC -> ETHEREUMCLASSIC;
            case DECRED -> DECRED;
            case QUANTUS -> QUANTUS;
            default -> NONE;
        };
    }

    /** Base58 and checksum helpers shared by the UTXO-style coin rules. */
    final class RouteValidation {
        private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

        private RouteValidation() { }

        static boolean ravencoinAddress(String address) {
            byte[] decoded = base58Decoded(address, 26, 40);
            if (decoded == null || decoded.length != 25
                    || (decoded[0] & 0xff) != 60 && (decoded[0] & 0xff) != 122) return false;
            return checksumOk(decoded, 21);
        }

        static boolean decredAddress(String address) {
            byte[] decoded = base58Decoded(address, 30, 40);
            if (decoded == null || decoded.length != 26 || (decoded[0] & 0xff) != 0x07
                    || ((decoded[1] & 0xff) != 0x3f && (decoded[1] & 0xff) != 0x1a)) return false;
            return checksumOk(decoded, 22);
        }

        /** Base58-decodes {@code address} (length bounds included) or returns null. */
        private static byte[] base58Decoded(String address, int minLength, int maxLength) {
            if (address == null || address.length() < minLength || address.length() > maxLength) return null;
            BigInteger value = BigInteger.ZERO;
            for (char letter : address.toCharArray()) {
                int digit = BASE58.indexOf(letter);
                if (digit < 0) return null;
                value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
            }
            byte[] bytes = value.toByteArray();
            if (bytes.length > 0 && bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
            int zeros = 0;
            while (zeros < address.length() && address.charAt(zeros) == '1') zeros++;
            byte[] decoded = new byte[zeros + bytes.length];
            System.arraycopy(bytes, 0, decoded, zeros, bytes.length);
            return decoded;
        }

        /** Double-SHA-256 checksum over the first {@code payloadLength} bytes vs. the trailing 4. */
        private static boolean checksumOk(byte[] decoded, int payloadLength) {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                byte[] checksum = sha.digest(sha.digest(Arrays.copyOf(decoded, payloadLength)));
                return Arrays.equals(Arrays.copyOfRange(decoded, payloadLength, payloadLength + 4),
                        Arrays.copyOf(checksum, 4));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }
}
