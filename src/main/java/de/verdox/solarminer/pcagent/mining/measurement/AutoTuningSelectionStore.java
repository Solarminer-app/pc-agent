package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-GPU dimensions enabled for the next automatic calibration. */
@Service
public class AutoTuningSelectionStore {
    private final Path file;
    private final ObjectMapper json;
    private final Map<String, Selection> selections = new LinkedHashMap<>();

    @Autowired
    public AutoTuningSelectionStore(ObjectMapper json,
            @Value("${solarminer.agent.auto-tuning-selection-file:./solarminer-agent/auto-tuning-selection.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        try {
            Map<String, Selection> saved = json.readValue(Files.readString(file), new TypeReference<>() { });
            if (saved != null) selections.putAll(saved);
        } catch (Exception ignored) { }
    }

    /** In-memory defaults for isolated service construction. */
    public AutoTuningSelectionStore() {
        this.json = new ObjectMapper();
        this.file = null;
    }

    public synchronized Selection get(String deviceId) {
        return selections.getOrDefault(deviceId, Selection.DEFAULT);
    }

    public synchronized boolean set(String deviceId, Selection selection) {
        if (deviceId == null || deviceId.isBlank() || selection == null
                || !selection.core() && !selection.memory() && !selection.power()) return false;
        if (file == null) {
            selections.put(deviceId, selection);
            return true;
        }
        Map<String, Selection> updated = new LinkedHashMap<>(selections);
        updated.put(deviceId, selection);
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(file, updated, json,
                file.getFileName().toString())) return false;
        selections.clear();
        selections.putAll(updated);
        return true;
    }

    public record Selection(boolean core, boolean memory, boolean power, Goal goal) {
        public Selection {
            goal = goal == null ? Goal.EFFICIENCY : goal;
        }

        /** Compatibility with saved selections from before the operating goal existed. */
        public Selection(boolean core, boolean memory, boolean power) {
            this(core, memory, power, Goal.EFFICIENCY);
        }

        public static final Selection DEFAULT = new Selection(true, true, true, Goal.EFFICIENCY);
    }

    public enum Goal { EFFICIENCY, PROFIT_PER_DAY }
}
