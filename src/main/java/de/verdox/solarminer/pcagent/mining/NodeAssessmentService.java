package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Stores an assessment produced by the SolarMiner Node. The PC-Agent deliberately does not
 * calculate PV economics itself: it only displays the Node's decision with its reason and time.
 */
@Service
public class NodeAssessmentService {
    private final ObjectMapper json;
    private final Path file;
    private volatile Assessment assessment;
    private volatile Instant lastContact;

    public NodeAssessmentService(ObjectMapper json,
            @Value("${solarminer.agent.node-assessment-file:./solarminer-agent/node-assessment.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.assessment = load();
    }

    public Assessment get() { return assessment; }
    public boolean isConnected() { return lastContact != null && lastContact.isAfter(Instant.now().minusSeconds(120)); }

    public synchronized boolean update(Assessment incoming) {
        if (incoming == null || incoming.decision() == null || incoming.decision().isBlank()
                || incoming.decision().length() > 32 || incoming.reason() != null && incoming.reason().length() > 500)
            return false;
        Assessment saved = new Assessment(incoming.decision().toUpperCase(java.util.Locale.ROOT), incoming.reason(),
                incoming.source() == null || incoming.source().isBlank() ? "SolarMiner Node" : incoming.source(),
                Instant.now());
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "node-assessment-", ".json");
            try {
                json.writeValue(temp.toFile(), saved);
                try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
            assessment = saved;
            lastContact = Instant.now();
            return true;
        } catch (Exception ignored) { return false; }
    }

    private Assessment load() {
        try { return json.readValue(Files.readString(file), Assessment.class); }
        catch (Exception ignored) { return new Assessment("UNKNOWN", null, "SolarMiner Node", null); }
    }

    public record Assessment(String decision, String reason, String source, Instant evaluatedAt) { }
}
