package de.verdox.solarminer.pcagent.mining.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Operator-chosen label for this agent. The SolarMiner Node lists the agent under this label
 * instead of the product name, so it stays short and free of control characters. The product
 * identity in {@code Identity.kind} and the pool worker naming are deliberately untouched.
 */
@Service
public class AgentIdentityService {
    public static final String DEFAULT_NAME = "SolarMiner PC Agent";
    public static final int MAX_NAME_LENGTH = 40;

    private final ObjectMapper json;
    private final Path file;
    private volatile String name;

    public AgentIdentityService(ObjectMapper json,
            @Value("${solarminer.agent.identity-file:./solarminer-agent/agent-identity.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.name = load();
    }

    /** Null while the operator has not named this agent; consumers then show {@link #DEFAULT_NAME}. */
    public String name() {
        return name;
    }

    public String displayName() {
        return name == null ? DEFAULT_NAME : name;
    }

    /** A blank request clears the label; an invalid one is rejected without changing anything. */
    public synchronized boolean rename(String requested) {
        if (requested != null && requested.chars().anyMatch(Character::isISOControl)) return false;
        String normalized = requested == null ? "" : requested.replaceAll("\\s+", " ").strip();
        if (normalized.length() > MAX_NAME_LENGTH) return false;
        return persist(normalized.isEmpty() ? null : normalized);
    }

    private String load() {
        try {
            Stored stored = json.readValue(Files.readString(file), Stored.class);
            return stored == null ? null : normalize(stored.name());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean persist(String value) {
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(file, new Stored(value), json, "agent-identity-")) {
            return false;
        }
        name = value;
        return true;
    }

    private static String normalize(String value) {
        if (value == null || value.chars().anyMatch(Character::isISOControl)) return null;
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH ? null : normalized;
    }

    private record Stored(String name) { }
}
