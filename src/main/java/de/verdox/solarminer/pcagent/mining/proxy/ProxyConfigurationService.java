package de.verdox.solarminer.pcagent.mining.proxy;

import de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient;
import de.verdox.solarminer.pcagent.mining.fee.ReferralConfigurationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.FeeRollMode;
import de.verdox.solarminer.pcagent.coin.ProxyMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

/** The only Stratum destination that locally managed miners may use. */
@Service
public class ProxyConfigurationService {
    private final Path configFile;
    /** Coin->port wiring lives only in {@link ProxyPorts}; this service consumes it. */
    private final Map<Coin, Integer> coinPorts;
    private final int apiPort;
    private final Path modeFile;
    private final Path rollModeFile;
    private final ObjectMapper mapper;
    private final ManagedProxyService managedProxy;
    private final ReferralConfigurationService referralConfigurationService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient feeClient;
    private volatile String host;
    private volatile boolean standalone;
    private volatile boolean modeStored;
    /** Operator choice for how the proxy rolls fee targets per job: "random" or "stateful". */
    private volatile String rollMode = FeeRollMode.RANDOM.wireName();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> feeCheckedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> feeCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** One refresh at a time per coin, without serializing unrelated dashboard routes. */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> feeLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public ProxyConfigurationService(
            ObjectMapper mapper, ManagedProxyService managedProxy, ReferralConfigurationService referralConfigurationService,
            ProxyPorts ports,
            @Value("${solarminer.agent.proxy-file:./solarminer-agent/proxy-host.txt}") String configPath,
            @Value("${solarminer.agent.proxy-mode-file:./solarminer-agent/proxy-mode.txt}") String modePath,
            @Value("${solarminer.agent.standalone:false}") boolean standalone) {
        this.mapper = mapper;
        this.managedProxy = managedProxy;
        this.referralConfigurationService = referralConfigurationService;
        this.feeClient = new de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient(mapper);
        this.coinPorts = ports.coinPorts();
        this.configFile = Path.of(configPath).toAbsolutePath().normalize();
        this.apiPort = ports.apiPort();
        this.modeFile = Path.of(modePath).toAbsolutePath().normalize();
        this.rollModeFile = this.modeFile.resolveSibling("fee-roll-mode.txt");
        String savedMode = readMode();
        this.modeStored = savedMode != null;
        this.standalone = savedMode == null ? standalone : ProxyMode.LOCAL.wireName().equals(savedMode);
        this.rollMode = readRollMode();
        try {
            String saved = Files.readString(configFile).strip();
            if (validHost(saved)) host = saved;
        } catch (IOException ignored) { }
    }

    /** Applies the stored routing choice; without a stored choice the agent always runs its own proxy first. */
    @EventListener(ApplicationReadyEvent.class)
    public void configureInitialProxy() {
        if (hasStoredMode()) {
            activateStoredMode();
            return;
        }
        // A LAN proxy is only offered in the dashboard; the agent always downloads and starts its
        // own proxy first, so the operator never mines through an unreviewed server by accident.
        setMode("local");
    }

    public void activateStoredMode() {
        managedProxy.setRollMode(rollMode);
        managedProxy.setStandalone(standalone);
    }

    /**
     * Persists the operator's fee-roll choice and applies it to the managed proxy. The proxy
     * child restarts with the new flag, so the caller must pause miners first, like a mode switch.
     */
    public synchronized boolean setRollMode(String mode) {
        FeeRollMode parsed = FeeRollMode.from(mode);
        if (parsed == null) return false;
        String normalized = parsed.wireName();
        if (!writeRollMode(normalized)) return false;
        rollMode = normalized;
        managedProxy.setRollMode(normalized);
        return true;
    }

    public String rollMode() { return rollMode; }

    public synchronized boolean configure(String nextHost) {
        if (!validHost(nextHost)) return false;
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeText(configFile, nextHost, "proxy-")) {
            return false;
        }
        host = nextHost;
        invalidateFeeCache();
        return true;
    }

    /** Persists the operator's routing choice; local mode never overwrites the saved external host. */
    public synchronized boolean setMode(String mode) {
        ProxyMode parsed = ProxyMode.from(mode);
        if (parsed == null) return false;
        boolean local = parsed == ProxyMode.LOCAL;
        managedProxy.setRollMode(rollMode);
        if (local == standalone) {
            if (local && !managedProxy.setStandalone(true)) return false;
            if (!writeMode(local)) return false;
            modeStored = true;
            invalidateFeeCache();
            return true;
        }
        if (local && !managedProxy.setStandalone(true)) return false;
        if (!writeMode(local)) {
            if (local) managedProxy.setStandalone(false);
            return false;
        }
        standalone = local;
        modeStored = true;
        invalidateFeeCache();
        if (!local) managedProxy.setStandalone(false);
        return true;
    }

    public boolean configured() { return standalone || host != null; }
    public boolean hasStoredMode() { return modeStored; }
    public boolean standalone() { return standalone; }
    public String managedStatus() { return managedProxy.status(); }
    public String managedDetail() { return managedProxy.detail(); }
    public String managedVersion() { return managedProxy.version(); }
    public ManagedProxyService.ProxyGate managedGate() { return managedProxy.gate(); }
    public ManagedProxyService.ProxyLogChunk managedProxyLog(long offset) throws IOException { return managedProxy.readLog(offset); }
    public boolean retryManagedProxy() { return managedProxy.retry(); }
    public String host() { return standalone ? "127.0.0.1" : host; }
    public int apiPort() { return apiPort; }

    /** The proxy's stratum endpoint for a coin, or null when the coin has no listener. */
    public String coinUrl(Coin coin) {
        Integer port = coinPorts.get(coin);
        return port == null ? null : url(port);
    }

    private String url(int port) {
        String currentHost = host();
        return currentHost == null ? null : "stratum+tcp://" + currentHost + ":" + port;
    }

    /** Typed variant of {@link #matches(String, String)} for orchestration code. */
    public boolean matches(String stratumUrl, Coin coin) { return coin != null && matches(stratumUrl, coin.id()); }

    public boolean matches(String stratumUrl, String coin) {
        String currentHost = host();
        if (currentHost == null || stratumUrl == null) return false;
        try {
            URI uri = URI.create(stratumUrl);
            int expectedPort = portFor(coin);
            return "stratum+tcp".equals(uri.getScheme()) && currentHost.equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == expectedPort && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isReachable() {
        String currentHost = host();
        if (currentHost == null) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + currentHost + ":" + apiPort + "/api/network/ip"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && !response.body().isBlank();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * A miner may start only when the fee service has a usable SolarMiner house
     * target for this coin. Unknown coins and missing/unavailable targets fail closed.
     */
    public boolean feeReady(Coin coin) { return coin != null && feeReady(coin.id()); }

    public boolean feeReady(String coin) {
        String currentHost = host();
        if (currentHost == null || coin == null || !coin.matches("[a-z0-9_-]{1,32}")) return false;
        long now = System.currentTimeMillis();
        if (now - feeCheckedAt.getOrDefault(coin, 0L) < 3000) return feeCache.getOrDefault(coin, false);
        // A whole-agent lock here turned a missing proxy into N × 3-second waits: the
        // dashboard checks every coin route. Keep the duplicate-read protection, but scope it
        // to one coin so all independent fee endpoints can time out or succeed together.
        synchronized (feeLocks.computeIfAbsent(coin, ignored -> new Object())) {
            now = System.currentTimeMillis();
            if (now - feeCheckedAt.getOrDefault(coin, 0L) < 3000) return feeCache.getOrDefault(coin, false);
            boolean ready = false;
            for (var target : feeClient.feeTargets(currentHost, apiPort, coin, null,
                    referralConfigurationService.get(), Duration.ofSeconds(3))) {
                if (target.house() && target.percentage() > 0 && !target.targetId().isBlank()
                        && de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient.validPoolAddress(target.poolAddress())
                        && !target.workerName().isBlank()) ready = true;
            }
            feeCache.put(coin, ready);
            feeCheckedAt.put(coin, System.currentTimeMillis());
            return feeCache.getOrDefault(coin, false);
        }
    }

    public synchronized void invalidateFeeCache() {
        feeCache.clear();
        feeCheckedAt.clear();
        feeLocks.clear();
    }

    public boolean miningReady(Coin coin) { return coin != null && miningReady(coin.id()); }

    public boolean miningReady(String coin) {
        // A remote SolarMiner proxy owns its own lifecycle, but must prove the same listener and
        // fee route as a managed local proxy before a miner receives its credentials.
        return (!standalone || managedProxy.gateOpen() && managedProxy.running()) && isReachable()
                && stratumReachable(coin) && feeReady(coin);
    }

    private int portFor(String coin) {
        Coin parsed = Coin.byIdOrNull(coin);
        Integer port = parsed == null ? null : coinPorts.get(parsed);
        return port == null ? -1 : port;
    }

    private boolean stratumReachable(String coin) {
        String currentHost = host();
        if (currentHost == null) return false;
        int port = portFor(coin);
        try {
            // TCP connect/close is indistinguishable from a miner to Stratum V1.
            // The dashboard reports the server's actual bound listener state.
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + currentHost + ":" + apiPort + "/api/dashboard"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            JsonNode coins = mapper.readTree(response.body()).path("coins");
            if (!coins.isArray()) return false;
            for (JsonNode entry : coins) {
                if (coin.equals(entry.path("coin").asText()) && port == entry.path("port").asInt()
                        && "online".equals(entry.path("listenerStatus").asText())) return true;
            }
            return false;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean validPoolAddress(String value) {
        return de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient.validPoolAddress(value);
    }

    private static boolean validHost(String value) {
        return value != null && value.length() <= 253 && value.matches("^[A-Za-z0-9][A-Za-z0-9.-]*$")
                && !value.endsWith(".") && !value.contains("..");
    }

    private String readMode() {
        try {
            String saved = Files.readString(modeFile).strip();
            ProxyMode parsed = ProxyMode.from(saved);
            if (parsed != null) return parsed.wireName();
        } catch (IOException ignored) { }
        return null;
    }

    private String readRollMode() {
        try {
            String saved = Files.readString(rollModeFile).strip();
            FeeRollMode parsed = FeeRollMode.from(saved);
            if (parsed != null) return parsed.wireName();
        } catch (IOException ignored) { }
        return FeeRollMode.RANDOM.wireName();
    }

    private boolean writeRollMode(String mode) {
        return de.verdox.solarminer.pcagent.support.AtomicFileStore.writeText(rollModeFile, mode, "fee-roll-mode-");
    }

    private boolean writeMode(boolean local) {
        return de.verdox.solarminer.pcagent.support.AtomicFileStore.writeText(modeFile,
                (local ? ProxyMode.LOCAL : ProxyMode.EXTERNAL).wireName(), "proxy-mode-");
    }
}
