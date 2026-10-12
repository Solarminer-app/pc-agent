package de.verdox.solarminer.pcagent.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * The single place that persists operator settings under {@code ./solarminer-agent/}.
 * Every settings store previously carried its own copy of the temp-file, atomic-move,
 * fallback and cleanup block; this helper keeps that semantics once:
 * write to a sibling temp file, move atomically when the filesystem supports it,
 * fall back to a plain replace move otherwise, and always delete the temp file.
 *
 * <p>The write is fail-soft like every former copy: an {@link IOException} yields
 * {@code false} so callers keep their in-memory value and decide how to report.</p>
 */
public final class AtomicFileStore {

    private AtomicFileStore() { }

    /** Atomically (best effort) writes {@code content} to {@code file}. True on success. */
    public static boolean writeText(Path file, String content) {
        return writeText(file, content, file.getFileName().toString() + "-");
    }

    /** Variant with an explicit temp-file prefix for diagnostics (e.g. "referral-"). */
    public static boolean writeText(Path file, String content, String tempPrefix) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), tempPrefix, ".tmp");
            try {
                Files.writeString(temp, content);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /** Reads a settings file, empty when it is missing or unreadable (same fail-soft rule). */
    public static Optional<String> readText(Path file) {
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Serializes {@code value} with {@code mapper} into the same atomic-write path.
     * Fails soft like {@link #writeText}: any serialization or IO error yields false.
     */
    public static boolean writeJson(Path file, Object value, com.fasterxml.jackson.databind.ObjectMapper mapper,
                                    String tempPrefix) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), tempPrefix, ".json");
            try {
                mapper.writeValue(temp.toFile(), value);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
