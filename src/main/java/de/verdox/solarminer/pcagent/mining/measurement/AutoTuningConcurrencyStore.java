package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.support.AtomicFileStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/** Persists the locally chosen number of GPUs that may calibrate at once. */
@Service
public class AutoTuningConcurrencyStore {
    public static final int MINIMUM = 1;
    public static final int MAXIMUM = 64;
    private final Path file;
    private final ObjectMapper json;
    private int maxConcurrent = MINIMUM;

    @Autowired
    public AutoTuningConcurrencyStore(ObjectMapper json,
            @Value("${solarminer.agent.auto-tuning-concurrency-file:./solarminer-agent/auto-tuning-concurrency.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        try {
            Settings saved = json.readValue(Files.readString(file), Settings.class);
            if (saved != null && valid(saved.maxConcurrent())) maxConcurrent = saved.maxConcurrent();
        } catch (Exception ignored) { }
    }

    /** For isolated service tests. */
    public AutoTuningConcurrencyStore() { this.file = null; this.json = new ObjectMapper(); }

    public synchronized int get() { return maxConcurrent; }

    public synchronized boolean set(int value) {
        if (!valid(value)) return false;
        if (file != null && !AtomicFileStore.writeJson(file, new Settings(value), json, file.getFileName().toString())) return false;
        maxConcurrent = value;
        return true;
    }

    public static boolean valid(int value) { return value >= MINIMUM && value <= MAXIMUM; }
    public record Settings(int maxConcurrent) { }
}
