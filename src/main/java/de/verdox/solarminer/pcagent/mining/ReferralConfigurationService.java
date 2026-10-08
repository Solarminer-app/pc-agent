package de.verdox.solarminer.pcagent.mining;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** The referral selected locally. A SolarMiner Node may overwrite it at any time. */
@Service
public class ReferralConfigurationService {
    private final Path file;
    /** Empty is the canonical default: it means no referrer, not a "solarminer" referral. */
    private volatile String referral = "";

    public ReferralConfigurationService(@Value("${solarminer.agent.referral-file:./solarminer-agent/referral-key.txt}") String path) {
        file = Path.of(path).toAbsolutePath().normalize();
        try {
            String saved = Files.readString(file).trim();
            // Migrate the former sentinel. It was never a real referral code.
            if ("solarminer".equalsIgnoreCase(saved)) referral = "";
            else if (valid(saved)) referral = saved;
        } catch (IOException ignored) { }
    }

    public String get() { return referral; }

    public synchronized boolean set(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!valid(normalized)) return false;
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "referral-", ".tmp");
            try {
                Files.writeString(temp, normalized);
                try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
            referral = normalized;
            return true;
        } catch (IOException ignored) { return false; }
    }

    private static boolean valid(String value) {
        return value != null && (value.isEmpty() || value.matches("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"));
    }
}
