package de.verdox.solarminer.pcagent.mining.proxy;

import de.verdox.solarminer.pcagent.coin.Coin;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory health projection of the stratum proxy (REST audit R1, 2026-10-11). Reachability
 * and per-coin fee readiness are collected exclusively on the background scheduler; every REST
 * read model ({@code GET /local/proxy}, the overview aggregate, the external Node contract)
 * reads the last completed snapshot and never performs proxy HTTP itself. A slow or unreachable
 * proxy therefore slows down the background refresh, never a browser request.
 *
 * <p>The live checks stay in {@link ProxyConfigurationService} because miner start gates must
 * observe the real current state; this service consumes those checks on its own tick and
 * publishes one immutable, atomically swapped snapshot so consumers can never mix a fresh
 * reachability flag with a stale per-coin fee flag.</p>
 */
@Service
public class ProxyHealthService {

    /** Per-coin fee-route readiness as of the last completed collection. */
    public record CoinHealth(boolean feeReady) { }

    /** One consistent proxy health observation; replaced atomically on every refresh. */
    public record Snapshot(boolean reachable, Map<String, CoinHealth> coins, Instant collectedAt,
                           boolean initialized, String lastError) {
        static Snapshot empty() {
            return new Snapshot(false, Map.of(), Instant.EPOCH, false, null);
        }
    }

    private final ProxyConfigurationService proxy;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    /** Set by configuration mutations so the next tick (or an immediate kick) re-collects. */
    private volatile boolean forceRefresh;

    public ProxyHealthService(ProxyConfigurationService proxy) {
        this.proxy = proxy;
    }

    /** The last completed health snapshot; never performs I/O and never waits for the proxy. */
    public Snapshot snapshot() {
        return snapshot.get();
    }

    /** Fee readiness for one coin from the last snapshot; unknown coins read as not ready. */
    public boolean feeReady(String coin) {
        Snapshot current = snapshot.get();
        CoinHealth health = current.coins().get(coin);
        return health != null && health.feeReady();
    }

    /** Reports that proxy configuration changed; the next scheduled tick re-collects immediately. */
    public void invalidate() {
        forceRefresh = true;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmSnapshot() {
        Thread.startVirtualThread(this::refresh);
    }

    /**
     * Background collection loop. The interval is deliberately short (default 2 s) because the
     * dashboard gate and the proxy page read this snapshot; the underlying per-coin fee check in
     * {@link ProxyConfigurationService} keeps its own 3 s HTTP cache, so a tick against a dead
     * proxy costs at most one connect timeout per coin, off the request path. The collection
     * itself runs on a virtual thread (single-flight) so a dead proxy never occupies a shared
     * scheduler thread.
     */
    @Scheduled(fixedDelayString = "${solarminer.agent.proxy-health-refresh-ms:2000}")
    public void refreshScheduled() {
        if (refreshing.get()) return;
        if (!forceRefresh && snapshot.get().initialized()
                && java.time.Duration.between(snapshot.get().collectedAt(), Instant.now()).toMillis() < 1_000)
            return;
        forceRefresh = false;
        Thread.startVirtualThread(this::refresh);
    }

    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();

    /** One collection pass; failures publish a consistent not-ready snapshot instead of throwing. */
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            boolean reachable = proxy.isReachable();
            Map<String, CoinHealth> coins = new LinkedHashMap<>();
            for (Coin coin : Coin.miningCoins()) {
                // feeReady() is TTL-cached inside ProxyConfigurationService; with the proxy
                // unreachable it fails closed quickly and the loop stays bounded.
                coins.put(coin.id(), new CoinHealth(reachable && proxy.feeReady(coin)));
            }
            snapshot.set(new Snapshot(reachable, Map.copyOf(coins), Instant.now(), true, null));
        } catch (RuntimeException failure) {
            Snapshot previous = snapshot.get();
            snapshot.set(new Snapshot(false, previous.coins(), Instant.now(), true,
                    failure.getClass().getSimpleName()));
        } finally {
            refreshing.set(false);
        }
    }
}
