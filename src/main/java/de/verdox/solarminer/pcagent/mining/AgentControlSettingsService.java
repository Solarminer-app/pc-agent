package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Local owner switches. They are checked by every Node-facing command, not only by the UI.
 */
@Service
public class AgentControlSettingsService {
    private final ObjectMapper json;
    private final Path file;
    private volatile Settings settings;

    public record Settings(boolean dynamicPowerScalingEnabled, boolean externalControlEnabled,
                           Map<String, Boolean> workerExternalControl, Map<String, String> workerCoins) {
        public Settings {
            workerExternalControl = workerExternalControl == null ? Map.of() : Map.copyOf(workerExternalControl);
            workerCoins = workerCoins == null ? null : Map.copyOf(workerCoins);
        }

        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers) {
            this(scaling, external, workers, null);
        }

        public String coinFor(String workerId) {
            return workerCoins == null ? ("cpu".equals(workerId) ? "monero" : "pearl")
                    : workerCoins.getOrDefault(workerId, "cpu".equals(workerId) ? "none" : workerCoins.getOrDefault("*", "none"));
        }

        public Settings(boolean dynamicPowerScalingEnabled, boolean externalControlEnabled) {
            this(dynamicPowerScalingEnabled, externalControlEnabled, Map.of());
        }

        public boolean workerEnabled(String workerId) {
            return !Boolean.FALSE.equals(workerExternalControl.get(workerId)) && !"none".equals(coinFor(workerId));
        }
    }

    public AgentControlSettingsService(ObjectMapper json, @Value("${solarminer.agent.control-settings-file:./solarminer-agent/control-settings.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.settings = load();
    }

    public Settings get() {
        return settings;
    }

    public boolean workerEnabled(String workerId) {
        return settings.workerEnabled(workerId);
    }

    public synchronized boolean update(Settings value) {
        if (value == null) return false;
        value = new Settings(value.dynamicPowerScalingEnabled(), value.externalControlEnabled(),
                value.workerExternalControl(), value.workerCoins() == null ? settings.workerCoins() : Map.copyOf(value.workerCoins()));
        if (!persist(value)) return false;
        settings = value;
        return true;
    }

    public synchronized boolean setWorkerEnabled(String workerId, boolean enabled) {
        if (workerId == null || workerId.isBlank()) return false;
        Map<String, Boolean> next = new java.util.HashMap<>(settings.workerExternalControl());
        if (enabled) next.remove(workerId);
        else next.put(workerId, false);
        return update(new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(), next, settings.workerCoins()));
    }

    public synchronized boolean setWorkerCoin(String workerId, String coin) {
        if (!java.util.Set.of("none", "monero", "pearl", "ravencoin", "ethereumclassic", "decred", "quantus").contains(coin)) return false;
        if ("cpu".equals(workerId) ? !("none".equals(coin) || "monero".equals(coin)) : "monero".equals(coin)) return false;
        // Retain the old CPU assignment when migrating a pre-profile settings file.
        Map<String, String> next = new java.util.HashMap<>(settings.workerCoins() == null ? Map.of("cpu", settings.coinFor("cpu"), "*", "pearl") : settings.workerCoins());
        next.put(workerId, coin);
        return update(new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(), settings.workerExternalControl(), next));
    }

    /**
     * A new agent needs explicit local consent before a Node may control it over the LAN.
     */
    private Settings load() {
        try {
            return json.readValue(Files.readString(file), Settings.class);
        } catch (Exception ignored) {
            return new Settings(true, false, Map.of(), Map.of());
        }
    }

    private boolean persist(Settings value) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "agent-control-", ".json");
            try {
                json.writeValue(temp.toFile(), value);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
