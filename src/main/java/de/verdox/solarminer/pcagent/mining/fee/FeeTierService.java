package de.verdox.solarminer.pcagent.mining.fee;

import de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.miner.CoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import de.verdox.solarminer.pcagent.mining.proxy.ManagedProxyService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;

/**
 * Decides which dev-fee tier this agent's stratum proxy must enforce and pushes
 * it to the proxy immediately on every flip.
 *
 * <p>Tier rule (fail toward the higher fee): the effective tier is {@code node}
 * whenever the operator has enabled Node external control (the local consent hook).
 * Disabling that hook immediately restores the reduced {@code proxy} (1%) tier,
 * because the Node cannot control the agent any longer. The tier is
 * never chosen by the proxy or fee-backend locally; the agent pushes it via
 * {@code POST /api/v1/fees/tier}, and the proxy re-resolves its fee targets
 * instantly, so the very next rolled job uses the new split.</p>
 */
@Service
public class FeeTierService {
    public static final String TIER_NODE = "node";
    public static final String TIER_PROXY = "proxy";

    private static final Logger log = LoggerFactory.getLogger(FeeTierService.class);

    private final AgentControlSettingsService controls;
    private final ProxyConfigurationService proxy;
    private final ManagedProxyService managedProxy;
    /**
     * Lazily resolved: the fee-route guard needs the miner adapters, but the miner chain
     * (XmrMinerService -> PayoutDefaultsService) depends back on this service, so a direct
     * constructor injection would close a Spring bean cycle.
     */
    private final org.springframework.beans.factory.ObjectProvider<MinerFactory> miners;
    private final ObjectMapper mapper;
    private final de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient feeClient;

    private volatile String lastPushedTier;
    private volatile boolean lastPushOk;

    public FeeTierService(AgentControlSettingsService controls, ProxyConfigurationService proxy,
                          ManagedProxyService managedProxy,
                          org.springframework.beans.factory.ObjectProvider<MinerFactory> miners, ObjectMapper mapper) {
        this.controls = controls;
        this.proxy = proxy;
        this.managedProxy = managedProxy;
        this.miners = miners;
        this.mapper = mapper;
        this.feeClient = new de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient(mapper);
        // Every consent-hook flip re-evaluates and pushes the tier immediately.
        controls.setFeeTierListener(this::onControlSettingsChanged);
    }

    /**
     * Called by the external-access filter whenever a Node-facing request passes the gate.
     * Activity is intentionally not a fee signal: revoking consent must take effect at once.
     */
    public void recordNodeActivity() {
        // Kept as the filter's stable integration seam.
    }

    public String effectiveTier() {
        if (controls.get().externalControlEnabled()) return TIER_NODE;
        return TIER_PROXY;
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
     * Safety guard (formerly the standalone StandaloneFeeGuard service, merged here because it
     * is fee-route enforcement like everything else in this service): stops locally managed
     * miners if the bundled proxy loses its fee route.
     */
    @Scheduled(fixedDelay = 10_000)
    public void enforceFeeRoute() {
        if (!proxy.standalone()) return;
        MinerFactory minerFactory = miners.getObject();
        for (Coin coin : Coin.miningCoins()) {
            CoinMiner miner = minerFactory.miner(coin);
            if (miner.running() && !proxy.miningReady(coin.id())) {
                log.warn("Stopping miner for {}: standalone proxy or fee target unavailable", coin.id());
                miner.stopAll();
            }
        }
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
            lastPushOk = feeClient.pushTier(host, proxy.apiPort(), body, Duration.ofSeconds(3));
            if (lastPushOk) lastPushedTier = tier;
            if (changed || !lastPushOk) {
                log.info("Fee tier '{}' pushed to proxy {} ({}) -> {}", tier, host, reason,
                        lastPushOk ? "ok" : "rejected");
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
        return new Status(tier, controls.get().externalControlEnabled(), false,
                lastPushOk, tier.equals(lastPushedTier));
    }

    public record Status(String tier, boolean externalControlEnabled, boolean nodeRecentlyActive,
                         boolean pushedToProxy, boolean tierInSync) { }
}
