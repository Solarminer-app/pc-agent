package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Decides which dev-fee tier this agent's stratum proxy must enforce and pushes
 * it to the proxy immediately on every flip.
 *
 * <p>Tier rule (fail toward the higher fee): the effective tier is {@code node}
 * whenever a SolarMiner Node steers or reads this agent — that is, whenever the
 * operator has enabled Node external control (the local consent hook), or a Node
 * has called an external endpoint within the recent activity window. Only a
 * genuinely Node-free PC-Agent runs the reduced {@code proxy} tier. The tier is
 * never chosen by the proxy or fee-backend locally; the agent pushes it via
 * {@code POST /api/v1/fees/tier}, and the proxy re-resolves its fee targets
 * instantly, so the very next rolled job uses the new split.</p>
 */
@Service
public class FeeTierService {
    public static final String TIER_NODE = "node";
    public static final String TIER_PROXY = "proxy";
    /** A Node that called an external endpoint more recently than this still forces the node tier. */
    private static final long NODE_ACTIVITY_WINDOW_MS = 15 * 60_000L;

    private static final Logger log = LoggerFactory.getLogger(FeeTierService.class);

    private final AgentControlSettingsService controls;
    private final ProxyConfigurationService proxy;
    private final ManagedProxyService managedProxy;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private volatile long lastNodeActivityAt;
    private volatile String lastPushedTier;
    private volatile boolean lastPushOk;

    public FeeTierService(AgentControlSettingsService controls, ProxyConfigurationService proxy,
                          ManagedProxyService managedProxy, ObjectMapper mapper) {
        this.controls = controls;
        this.proxy = proxy;
        this.managedProxy = managedProxy;
        this.mapper = mapper;
        // Every consent-hook flip re-evaluates and pushes the tier immediately.
        controls.setFeeTierListener(this::onControlSettingsChanged);
    }

    /** Called by the external-access filter whenever a Node-facing request passes the gate. */
    public void recordNodeActivity() {
        lastNodeActivityAt = System.currentTimeMillis();
    }

    public String effectiveTier() {
        if (controls.get().externalControlEnabled()) return TIER_NODE;
        return System.currentTimeMillis() - lastNodeActivityAt < NODE_ACTIVITY_WINDOW_MS ? TIER_NODE : TIER_PROXY;
    }

    private void onControlSettingsChanged() {
        pushTier("control settings changed");
    }

    /** Self-heal: a proxy restart, host switch or lost push never keeps a stale tier. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void keepTierInSync() {
        pushTier("periodic sync");
    }

    /**
     * Pushes the effective tier to the proxy (managed child or external host).
     * Called on every consent-hook flip and periodically as a self-heal, so a
     * proxy restart or host switch never keeps a stale tier.
     */
    public synchronized void pushTier(String reason) {
        String tier = effectiveTier();
        managedProxy.setFeeTier(tier);
        String host = proxy.host();
        if (host == null) return;
        boolean changed = !tier.equals(lastPushedTier);
        if (!changed && lastPushOk) return;
        try {
            String body = mapper.writeValueAsString(Map.of("tier", tier));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + proxy.apiPort()
                            + "/api/v1/fees/tier"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            lastPushOk = response.statusCode() == 200 || response.statusCode() == 204;
            if (lastPushOk) lastPushedTier = tier;
            if (changed || !lastPushOk) {
                log.info("Fee tier '{}' pushed to proxy {} ({}) -> {}", tier, host, reason,
                        lastPushOk ? "ok" : "status " + response.statusCode());
            }
        } catch (Exception e) {
            lastPushOk = false;
            log.debug("Could not push fee tier '{}' to proxy {}: {}", tier, host, e.getMessage());
        }
        proxy.invalidateFeeCache();
    }

    /** Badge/status view for the operator UI. Never hardcodes percentages. */
    public Status status() {
        String tier = effectiveTier();
        return new Status(tier, controls.get().externalControlEnabled(),
                System.currentTimeMillis() - lastNodeActivityAt < NODE_ACTIVITY_WINDOW_MS,
                lastPushOk, tier.equals(lastPushedTier));
    }

    public record Status(String tier, boolean externalControlEnabled, boolean nodeRecentlyActive,
                         boolean pushedToProxy, boolean tierInSync) { }
}
