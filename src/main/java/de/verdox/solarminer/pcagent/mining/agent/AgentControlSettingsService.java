package de.verdox.solarminer.pcagent.mining.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerCoinPolicy;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
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
    private volatile Runnable feeTierListener;

    /** Lets the fee-tier machinery react to consent-hook flips without a polling loop. */
    public void setFeeTierListener(Runnable listener) {
        this.feeTierListener = listener;
    }

    public record Settings(boolean dynamicPowerScalingEnabled, boolean externalControlEnabled,
                           Map<String, Boolean> workerExternalControl, Map<String, String> workerCoins,
                           Map<String, String> workerCoinPolicies, Boolean networkSharingEnabled) {
        public Settings {
            workerExternalControl = workerExternalControl == null ? Map.of() : Map.copyOf(workerExternalControl);
            workerCoins = workerCoins == null ? null : Map.copyOf(workerCoins);
            workerCoinPolicies = workerCoinPolicies == null ? Map.of() : Map.copyOf(workerCoinPolicies);
        }

        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers) {
            this(scaling, external, workers, null, Map.of(), null);
        }

        /** Kept for persisted pre-economic-plan settings and existing API callers. */
        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers, Map<String, String> coins) {
            this(scaling, external, workers, coins, Map.of(), null);
        }

        /** Pre-network-sharing arity; a missing flag means "keep the persisted value". */
        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers, Map<String, String> coins,
                        Map<String, String> policies) {
            this(scaling, external, workers, coins, policies, null);
        }

        /** LAN visibility is on by default; only an explicit false opts this agent out. */
        public boolean networkSharingActive() {
            return !Boolean.FALSE.equals(networkSharingEnabled);
        }

        public String coinFor(String workerId) {
            return workerCoins == null
                    ? (WorkerIds.isCpu(workerId) ? Coin.MONERO.id() : Coin.PEARL.id())
                    : workerCoins.getOrDefault(workerId, WorkerIds.isCpu(workerId)
                            ? Coin.NONE.id() : workerCoins.getOrDefault(WorkerIds.WILDCARD, Coin.NONE.id()));
        }

        public Settings(boolean dynamicPowerScalingEnabled, boolean externalControlEnabled) {
            this(dynamicPowerScalingEnabled, externalControlEnabled, Map.of());
        }

        public boolean workerEnabled(String workerId) {
            return !Boolean.FALSE.equals(workerExternalControl.get(workerId)) && !Coin.NONE.id().equals(coinFor(workerId));
        }

        /** AUTO is an explicit local opt-in; legacy and newly discovered workers stay FIXED. */
        public String coinPolicyFor(String workerId) {
            return WorkerCoinPolicy.from(workerCoinPolicies.get(workerId)).name();
        }

        public boolean permitsEconomicSelection(String workerId) {
            return workerEnabled(workerId) && WorkerCoinPolicy.AUTO.name().equals(coinPolicyFor(workerId));
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
                value.workerExternalControl(), value.workerCoins() == null ? settings.workerCoins() : Map.copyOf(value.workerCoins()),
                value.workerCoinPolicies() == null ? settings.workerCoinPolicies() : Map.copyOf(value.workerCoinPolicies()),
                value.networkSharingEnabled() == null ? settings.networkSharingEnabled() : value.networkSharingEnabled());
        if (!persist(value)) return false;
        settings = value;
        Runnable listener = feeTierListener;
        if (listener != null) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
        return true;
    }

    public synchronized boolean setWorkerEnabled(String workerId, boolean enabled) {
        if (workerId == null || workerId.isBlank()) return false;
        Map<String, Boolean> next = new java.util.HashMap<>(settings.workerExternalControl());
        if (enabled) next.remove(workerId);
        else next.put(workerId, false);
        return update(new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(), next, settings.workerCoins(), settings.workerCoinPolicies()));
    }

    public synchronized boolean setWorkerCoin(String workerId, String coin) {
        Coin parsed = Coin.byIdOrNull(coin);
        if (parsed == null) return false;
        boolean cpuWorker = WorkerIds.isCpu(workerId);
        if (!parsed.assignableTo(cpuWorker)) return false;
        // Retain the old CPU assignment when migrating a pre-profile settings file.
        Map<String, String> next = new java.util.HashMap<>(settings.workerCoins() == null
                ? Map.of(WorkerIds.CPU, settings.coinFor(WorkerIds.CPU), WorkerIds.WILDCARD, Coin.PEARL.id())
                : settings.workerCoins());
        next.put(workerId, coin);
        return update(new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(), settings.workerExternalControl(), next, settings.workerCoinPolicies()));
    }

    public synchronized boolean setWorkerCoinPolicy(String workerId, String policy) {
        if (workerId == null || workerId.isBlank() || policy == null
                || (!WorkerCoinPolicy.AUTO.name().equals(policy) && !WorkerCoinPolicy.FIXED.name().equals(policy))) return false;
        Map<String, String> next = new java.util.HashMap<>(settings.workerCoinPolicies());
        if (WorkerCoinPolicy.FIXED.name().equals(policy)) next.remove(workerId);
        else next.put(workerId, policy);
        return update(new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(),
                settings.workerExternalControl(), settings.workerCoins(), next));
    }

    /**
     * A new agent needs explicit local consent before a Node may control it over the LAN.
     */
    private Settings load() {
        try {
            return json.readValue(Files.readString(file), Settings.class);
        } catch (Exception ignored) {
            // A first installation participates in the read-only LAN dashboard by default.
            // Existing explicit opt-outs are retained by the normal JSON load path above.
            return new Settings(true, false, Map.of(), Map.of(), Map.of(), true);
        }
    }

    private boolean persist(Settings value) {
        return de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(file, value, json, "agent-control-");
    }
}
