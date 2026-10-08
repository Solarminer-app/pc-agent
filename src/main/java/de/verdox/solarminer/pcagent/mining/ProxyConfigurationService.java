package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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

/** The only Stratum destination that locally managed miners may use. */
@Service
public class ProxyConfigurationService {
    private final Path configFile;
    private final int moneroPort;
    private final int pearlPort;
    private final int ravenPort;
    private final int etcPort;
    private final int decredPort;
    private final int quantusPort;
    private final int apiPort;
    private final Path modeFile;
    private final Path rollModeFile;
    private final ObjectMapper mapper;
    private final ManagedProxyService managedProxy;
    private final ReferralConfigurationService referralConfigurationService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile String host;
    private volatile boolean standalone;
    private volatile boolean modeStored;
    /** Operator choice for how the proxy rolls fee targets per job: "random" or "stateful". */
    private volatile String rollMode = "random";
    private final java.util.concurrent.ConcurrentHashMap<String, Long> feeCheckedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> feeCache = new java.util.concurrent.ConcurrentHashMap<>();

    public ProxyConfigurationService(
            ObjectMapper mapper, ManagedProxyService managedProxy, ReferralConfigurationService referralConfigurationService,
            @Value("${solarminer.agent.proxy-file:./solarminer-agent/proxy-host.txt}") String configPath,
            @Value("${solarminer.agent.proxy.monero-port:3335}") int moneroPort,
            @Value("${solarminer.agent.proxy.pearl-port:3334}") int pearlPort,
            @Value("${solarminer.agent.proxy.ravencoin-port:3336}") int ravenPort,
            @Value("${solarminer.agent.proxy.ethereumclassic-port:3337}") int etcPort,
            @Value("${solarminer.agent.proxy.decred-port:3338}") int decredPort,
            @Value("${solarminer.agent.proxy.quantus-port:3339}") int quantusPort,
            @Value("${solarminer.agent.proxy.api-port:8090}") int apiPort,
            @Value("${solarminer.agent.proxy-mode-file:./solarminer-agent/proxy-mode.txt}") String modePath,
            @Value("${solarminer.agent.standalone:false}") boolean standalone) {
        this.mapper = mapper;
        this.managedProxy = managedProxy;
        this.referralConfigurationService = referralConfigurationService;
        this.configFile = Path.of(configPath).toAbsolutePath().normalize();
        this.moneroPort = moneroPort;
        this.pearlPort = pearlPort;
        this.ravenPort = ravenPort;
        this.etcPort = etcPort;
        this.decredPort = decredPort;
        this.quantusPort = quantusPort;
        this.apiPort = apiPort;
        this.modeFile = Path.of(modePath).toAbsolutePath().normalize();
        this.rollModeFile = this.modeFile.resolveSibling("fee-roll-mode.txt");
        String savedMode = readMode();
        this.modeStored = savedMode != null;
        this.standalone = savedMode == null ? standalone : "local".equals(savedMode);
        this.rollMode = readRollMode();
        try {
            String saved = Files.readString(configFile).strip();
            if (validHost(saved)) host = saved;
        } catch (IOException ignored) { }
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
        if (!"random".equalsIgnoreCase(mode) && !"stateful".equalsIgnoreCase(mode)) return false;
        String normalized = "stateful".equalsIgnoreCase(mode) ? "stateful" : "random";
        if (!writeRollMode(normalized)) return false;
        rollMode = normalized;
        managedProxy.setRollMode(normalized);
        return true;
    }

    public String rollMode() { return rollMode; }

    public synchronized boolean configure(String nextHost) {
        if (standalone) return false;
        if (!validHost(nextHost)) return false;
        try {
            Files.createDirectories(configFile.getParent());
            Path temp = Files.createTempFile(configFile.getParent(), "proxy-", ".tmp");
            try {
                Files.writeString(temp, nextHost);
                try {
                    Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            host = nextHost;
            invalidateFeeCache();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Persists the operator's routing choice; local mode never overwrites the saved external host. */
    public synchronized boolean setMode(String mode) {
        boolean local = "local".equals(mode);
        if (!local && !"external".equals(mode)) return false;
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
    public boolean retryManagedProxy() { return managedProxy.retry(); }
    public String host() { return standalone ? "127.0.0.1" : host; }
    public String moneroUrl() { return url(moneroPort); }
    public String pearlUrl() { return url(pearlPort); }
    public String ravencoinUrl() { return url(ravenPort); }
    public String ethereumclassicUrl() { return url(etcPort); }
    public String decredUrl() { return url(decredPort); }
    public String quantusUrl() { return url(quantusPort); }

    private String url(int port) {
        String currentHost = host();
        return currentHost == null ? null : "stratum+tcp://" + currentHost + ":" + port;
    }

    public boolean matches(String stratumUrl, String coin) {
        String currentHost = host();
        if (currentHost == null || stratumUrl == null) return false;
        try {
            URI uri = URI.create(stratumUrl);
            int expectedPort = switch (coin) {
                case "monero" -> moneroPort;
                case "pearl" -> pearlPort;
                case "ravencoin" -> ravenPort;
                case "ethereumclassic" -> etcPort;
                case "decred" -> decredPort;
                case "quantus" -> quantusPort;
                default -> -1;
            };
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
    public boolean feeReady(String coin) {
        String currentHost = host();
        if (currentHost == null || coin == null || !coin.matches("[a-z0-9_-]{1,32}")) return false;
        long now = System.currentTimeMillis();
        if (now - feeCheckedAt.getOrDefault(coin, 0L) < 3000) return feeCache.getOrDefault(coin, false);
        synchronized (this) {
            now = System.currentTimeMillis();
            if (now - feeCheckedAt.getOrDefault(coin, 0L) < 3000) return feeCache.getOrDefault(coin, false);
            try {
                String referralQuery = referralConfigurationService.get().isBlank() ? "" : "?referral=" + referralConfigurationService.get();
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + currentHost + ":" + apiPort
                                + "/api/v1/fees/" + coin + "/targets" + referralQuery))
                        .timeout(Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                boolean ready = false;
                if (response.statusCode() == 200) {
                    JsonNode targets = mapper.readTree(response.body());
                    if (targets.isArray()) for (JsonNode target : targets) {
                        if (target.path("house").asBoolean(false)
                                && target.path("percentage").asDouble() > 0
                                && !target.path("targetId").asText("").isBlank()
                                && validPoolAddress(target.path("poolAddress").asText(""))
                                && !target.path("workerName").asText("").isBlank()) ready = true;
                    }
                }
                feeCache.put(coin, ready);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                feeCache.put(coin, false);
            }
            feeCheckedAt.put(coin, System.currentTimeMillis());
            return feeCache.getOrDefault(coin, false);
        }
    }

    public synchronized void invalidateFeeCache() {
        feeCache.clear();
        feeCheckedAt.clear();
    }

    public boolean miningReady(String coin) {
        return managedProxy.gateOpen() && (!standalone || managedProxy.running()) && isReachable()
                && (!java.util.Set.of("ravencoin", "ethereumclassic", "decred", "quantus").contains(coin) || stratumReachable(coin))
                && (!java.util.Set.of("ravencoin", "ethereumclassic", "decred", "quantus").contains(coin) || feeReady(coin))
                && (!standalone || feeReady(coin));
    }

    private boolean stratumReachable(String coin) {
        String currentHost = host();
        if (currentHost == null) return false;
        int port = switch (coin) {
            case "ravencoin" -> ravenPort;
            case "ethereumclassic" -> etcPort;
            case "decred" -> decredPort;
            case "quantus" -> quantusPort;
            default -> -1;
        };
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
        try {
            URI uri = URI.create(value);
            return ("stratum+tcp".equals(uri.getScheme()) || "stratum+ssl".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535
                    && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean validHost(String value) {
        return value != null && value.length() <= 253 && value.matches("^[A-Za-z0-9][A-Za-z0-9.-]*$")
                && !value.endsWith(".") && !value.contains("..");
    }

    private String readMode() {
        try {
            String saved = Files.readString(modeFile).strip();
            if ("local".equals(saved) || "external".equals(saved)) return saved;
        } catch (IOException ignored) { }
        return null;
    }

    private String readRollMode() {
        try {
            String saved = Files.readString(rollModeFile).strip();
            if ("stateful".equalsIgnoreCase(saved)) return "stateful";
        } catch (IOException ignored) { }
        return "random";
    }

    private boolean writeRollMode(String mode) {
        try {
            Files.createDirectories(rollModeFile.getParent());
            Path temp = Files.createTempFile(rollModeFile.getParent(), "fee-roll-mode-", ".tmp");
            try {
                Files.writeString(temp, mode);
                try {
                    Files.move(temp, rollModeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, rollModeFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean writeMode(boolean local) {
        try {
            Files.createDirectories(modeFile.getParent());
            Path temp = Files.createTempFile(modeFile.getParent(), "proxy-mode-", ".tmp");
            try {
                Files.writeString(temp, local ? "local" : "external");
                try {
                    Files.move(temp, modeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, modeFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
