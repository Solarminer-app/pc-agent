package de.verdox.solarminer.pcagent.mining.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerCoinPolicy;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import lombok.Setter;
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
public class AgentControlSettingsService implements de.verdox.solarminer.pcagent.miner.WorkerStartPolicy {
    private final ObjectMapper json;
    private final Path file;
    private volatile Settings settings;
    private final Map<String, Object> stopRevisions = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * -- SETTER --
     * Lets the fee-tier machinery react to consent-hook flips without a polling loop.
     */
    @Setter
    private volatile Runnable feeTierListener;

    public record Settings(boolean dynamicPowerScalingEnabled, boolean externalControlEnabled,
                           Map<String, Boolean> workerExternalControl, Map<String, String> workerCoins,
                           Map<String, String> workerCoinPolicies, Boolean networkSharingEnabled,
                           Boolean walletTargetSharingEnabled, Map<String, String> workerMiners,
                           Map<String, String> workerPowerModes, Map<String, Boolean> workerStartOnBoot,
                           Map<String, Boolean> workerManualStop) {
        public Settings {
            workerExternalControl = workerExternalControl == null ? Map.of() : Map.copyOf(workerExternalControl);
            workerCoins = workerCoins == null ? null : Map.copyOf(workerCoins);
            workerCoinPolicies = workerCoinPolicies == null ? Map.of() : Map.copyOf(workerCoinPolicies);
            workerMiners = workerMiners == null ? Map.of() : Map.copyOf(workerMiners);
            workerPowerModes = workerPowerModes == null ? Map.of() : Map.copyOf(workerPowerModes);
            workerStartOnBoot = workerStartOnBoot == null ? Map.of() : Map.copyOf(workerStartOnBoot);
            workerManualStop = workerManualStop == null ? Map.of() : Map.copyOf(workerManualStop);
        }

        /** Existing profile/API constructor; startup settings are local and preserved by update(). */
        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers, Map<String, String> coins,
                        Map<String, String> policies, Boolean networkSharing, Boolean walletSharing,
                        Map<String, String> miners, Map<String, String> powerModes) {
            this(scaling, external, workers, coins, policies, networkSharing, walletSharing, miners,
                    powerModes, Map.of(), Map.of());
        }

        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers, Map<String, String> coins,
                        Map<String, String> policies, Boolean networkSharing, Boolean walletSharing,
                        Map<String, String> miners) {
            this(scaling, external, workers, coins, policies, networkSharing, walletSharing, miners, Map.of());
        }

        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers) {
            this(scaling, external, workers, null, Map.of(), null, null, Map.of());
        }

        /** Kept for persisted pre-economic-plan settings and existing API callers. */
        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers, Map<String, String> coins) {
            this(scaling, external, workers, coins, Map.of(), null, null, Map.of());
        }

        /** Pre-network-sharing arity; a missing flag means "keep the persisted value". */
        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers, Map<String, String> coins,
                        Map<String, String> policies) {
            this(scaling, external, workers, coins, policies, null, null, Map.of());
        }

        /** Pre-wallet-target-sharing arity; a missing flag keeps the persisted setting. */
        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers, Map<String, String> coins,
                        Map<String, String> policies, Boolean networkSharing) {
            this(scaling, external, workers, coins, policies, networkSharing, null, Map.of());
        }

        /** Pre-worker-miner arity retained for persisted/API compatibility. */
        public Settings(boolean scaling, boolean external, Map<String, Boolean> workers, Map<String, String> coins,
                        Map<String, String> policies, Boolean networkSharing, Boolean walletSharing) {
            this(scaling, external, workers, coins, policies, networkSharing, walletSharing, Map.of());
        }

        /** LAN visibility is on by default; only an explicit false opts this agent out. */
        public boolean networkSharingActive() {
            return !Boolean.FALSE.equals(networkSharingEnabled);
        }

        /** Wallet targets are private until their owner explicitly shares them on the LAN. */
        public boolean walletTargetSharingActive() {
            return Boolean.TRUE.equals(walletTargetSharingEnabled);
        }

        public String coinFor(String workerId) {
            return workerCoins == null
                    ? (WorkerIds.isCpu(workerId) ? Coin.MONERO.id() : Coin.PEARL.id())
                    : workerCoins.getOrDefault(workerId, WorkerIds.isCpu(workerId)
                            ? Coin.NONE.id() : workerCoins.getOrDefault(WorkerIds.WILDCARD, Coin.NONE.id()));
        }

        /** A missing entry intentionally falls back to the coin-wide legacy/default selection. */
        public String minerFor(String workerId, String fallback) {
            return workerMiners.getOrDefault(workerId, fallback);
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

        public String powerModeFor(String workerId) {
            return workerPowerModes.getOrDefault(workerId, "MANUAL");
        }

        public boolean startsOnBoot(String workerId) {
            return Boolean.TRUE.equals(workerStartOnBoot.get(workerId));
        }

        public boolean manuallyStopped(String workerId) {
            return Boolean.TRUE.equals(workerManualStop.get(workerId));
        }

        public boolean hasAutomaticPowerMode() {
            return workerPowerModes.entrySet().stream().anyMatch(entry -> !"MANUAL".equals(entry.getValue())
                    && !Coin.NONE.id().equals(coinFor(entry.getKey())));
        }

    }

    public AgentControlSettingsService(ObjectMapper json, @Value("${solarminer.agent.control-settings-file:./solarminer-agent/control-settings.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.settings = load();
    }

    @Override public boolean mayStart(String deviceId) {
        return !settings.manuallyStopped(deviceId);
    }

    /** Capture once at measurement admission, including for workers already paused. */
    public synchronized java.util.function.BooleanSupplier measurementStartPermit(String deviceId) {
        Object revision = stopRevisions.computeIfAbsent(deviceId, ignored -> new Object());
        return () -> stopRevisions.get(deviceId) == revision;
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
                value.networkSharingEnabled() == null ? settings.networkSharingEnabled() : value.networkSharingEnabled(),
                value.walletTargetSharingEnabled() == null ? settings.walletTargetSharingEnabled() : value.walletTargetSharingEnabled(),
                settings.workerMiners(), settings.workerPowerModes(),
                settings.workerStartOnBoot(), settings.workerManualStop());
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
        return update(new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(), next,
                settings.workerCoins(), settings.workerCoinPolicies(), settings.networkSharingEnabled(),
                settings.walletTargetSharingEnabled(), settings.workerMiners()));
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
        return update(new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(),
                settings.workerExternalControl(), next, settings.workerCoinPolicies(), settings.networkSharingEnabled(),
                settings.walletTargetSharingEnabled(), settings.workerMiners()));
    }

    /** Persists the coin and concrete miner as one worker profile update. */
    public synchronized boolean setWorkerAssignment(String workerId, String coin, String minerSoftwareId) {
        Coin parsed = Coin.byIdOrNull(coin);
        if (parsed == null || !parsed.assignableTo(WorkerIds.isCpu(workerId))) return false;
        Map<String, String> coins = new java.util.HashMap<>(settings.workerCoins() == null
                ? Map.of(WorkerIds.CPU, settings.coinFor(WorkerIds.CPU), WorkerIds.WILDCARD, Coin.PEARL.id())
                : settings.workerCoins());
        Map<String, String> miners = new java.util.HashMap<>(settings.workerMiners());
        coins.put(workerId, coin);
        if (parsed == Coin.NONE || minerSoftwareId == null || minerSoftwareId.isBlank()) miners.remove(workerId);
        else miners.put(workerId, minerSoftwareId);
        Settings next = new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(),
                settings.workerExternalControl(), coins, settings.workerCoinPolicies(), settings.networkSharingEnabled(),
                settings.walletTargetSharingEnabled(), miners, settings.workerPowerModes(),
                settings.workerStartOnBoot(), settings.workerManualStop());
        if (!persist(next)) return false;
        settings = next;
        return true;
    }

    public synchronized boolean setWorkerCoinPolicy(String workerId, String policy) {
        if (workerId == null || workerId.isBlank() || policy == null
                || (!WorkerCoinPolicy.AUTO.name().equals(policy) && !WorkerCoinPolicy.FIXED.name().equals(policy))) return false;
        Map<String, String> next = new java.util.HashMap<>(settings.workerCoinPolicies());
        if (WorkerCoinPolicy.FIXED.name().equals(policy)) next.remove(workerId);
        else next.put(workerId, policy);
        return update(new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(),
                settings.workerExternalControl(), settings.workerCoins(), next, settings.networkSharingEnabled(),
                settings.walletTargetSharingEnabled(), settings.workerMiners()));
    }

    public synchronized boolean setWorkerPowerMode(String workerId, String mode) {
        if (workerId == null || workerId.isBlank() || !java.util.Set.of("MANUAL", "EFFICIENCY", "MAX_HASHRATE", "AUTO_TUNING",
                "REVENUE_PER_KWH", "COIN_REVENUE_PER_KWH", "PROFIT_PER_DAY", "COIN_PROFIT_PER_DAY").contains(mode)) return false;
        Map<String, String> next = new java.util.HashMap<>(settings.workerPowerModes());
        if ("MANUAL".equals(mode)) next.remove(workerId);
        else next.put(workerId, mode);
        Settings updated = new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(),
                settings.workerExternalControl(), settings.workerCoins(), settings.workerCoinPolicies(),
                settings.networkSharingEnabled(), settings.walletTargetSharingEnabled(), settings.workerMiners(), next,
                settings.workerStartOnBoot(), settings.workerManualStop());
        if (!persist(updated)) return false;
        settings = updated;
        return true;
    }

    /** Boot opt-in per physical worker. A manual stop remains authoritative until an explicit start. */
    public synchronized boolean setWorkerStartOnBoot(String workerId, boolean enabled) {
        if (workerId == null || workerId.isBlank()) return false;
        Map<String, Boolean> starts = new java.util.HashMap<>(settings.workerStartOnBoot());
        Map<String, Boolean> stopped = new java.util.HashMap<>(settings.workerManualStop());
        if (enabled) starts.put(workerId, true);
        else starts.remove(workerId);
        return saveStartupState(starts, stopped);
    }

    /** A local operator pause blocks automatic starts until an explicit operator start. */
    public synchronized boolean setWorkerManuallyStopped(String workerId, boolean stopped) {
        if (workerId == null || workerId.isBlank()) return false;
        return setWorkersManuallyStopped(java.util.Set.of(workerId), stopped);
    }

    /** Group buttons persist one complete intent before they touch any processes. */
    public synchronized boolean setWorkersManuallyStopped(java.util.Set<String> workerIds, boolean stopped) {
        Map<String, Boolean> next = new java.util.HashMap<>(settings.workerManualStop());
        for (String workerId : workerIds) {
            if (workerId == null || workerId.isBlank()) return false;
            if (stopped) next.put(workerId, true);
            else next.remove(workerId);
        }
        // Repeated pauses revoke existing measurement authority even if already stopped,
        // including when persistence fails. Other devices keep their permits.
        if (stopped) workerIds.forEach(id -> stopRevisions.put(id, new Object()));
        return saveStartupState(settings.workerStartOnBoot(), next, stopped);
    }

    private boolean saveStartupState(Map<String, Boolean> starts, Map<String, Boolean> stopped) {
        return saveStartupState(starts, stopped, false);
    }

    private boolean saveStartupState(Map<String, Boolean> starts, Map<String, Boolean> stopped, boolean failClosed) {
        Settings next = new Settings(settings.dynamicPowerScalingEnabled(), settings.externalControlEnabled(),
                settings.workerExternalControl(), settings.workerCoins(), settings.workerCoinPolicies(),
                settings.networkSharingEnabled(), settings.walletTargetSharingEnabled(), settings.workerMiners(),
                settings.workerPowerModes(), starts, stopped);
        boolean saved = persist(next);
        // Disk failure must not authorize recovery after an operator stop in this process.
        if (saved || failClosed) settings = next;
        return saved;
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
            return new Settings(true, false, Map.of(), Map.of(), Map.of(), true, false, Map.of());
        }
    }

    private boolean persist(Settings value) {
        return de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(file, value, json, "agent-control-");
    }
}
