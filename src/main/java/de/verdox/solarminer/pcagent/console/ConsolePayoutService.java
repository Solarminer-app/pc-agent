package de.verdox.solarminer.pcagent.console;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.miner.engine.RouteValidator;
import de.verdox.solarminer.pcagent.miner.engine.WalletRule;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.miner.xmr.XmrConfigService;
import de.verdox.solarminer.pcagent.miner.xmr.download.XmrDownloadService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/** New payout boundary: validates before stopping, derives proxy routes, never switches the active coin. */
@Service
public class ConsolePayoutService {
    private final MinerFactory miners;
    private final ProxyConfigurationService proxy;
    private final PayoutDefaultsService defaults;
    private final XmrConfigService xmr;
    private final LocalGpuPowerService power;
    private final LocalRunLock lock;
    public ConsolePayoutService(MinerFactory miners, ProxyConfigurationService proxy, PayoutDefaultsService defaults,
                                XmrConfigService xmr, LocalGpuPowerService power, LocalRunLock lock) {
        this.miners = miners; this.proxy = proxy; this.defaults = defaults; this.xmr = xmr; this.power = power; this.lock = lock;
    }
    public record Route(String coin, String pool, String wallet, String worker) { }
    public record View(String coin, String name, String ticker, MinerConfig route, boolean usesDefault,
                       PayoutDefaultsService.DefaultView defaultTarget) { }
    public List<View> routes() {
        return Coin.miningCoins().stream().map(coin -> {
            CoinMiner miner = miners.miner(coin);
            MinerConfig route = miner instanceof CpuMiner cpu ? cpu.savedRoute()
                    : miner instanceof GpuCoinMiner gpu ? gpu.configuration() : null;
            return new View(coin.id(), coin.displayName(), coin.ticker(), route,
                    defaults.usesDefault(coin.id()), defaults.view(coin.id()));
        }).toList();
    }
    public void save(Route request) throws IOException {
        if (request == null) throw new IllegalArgumentException("tool_invalid_request");
        Coin coin = Coin.byIdOrNull(request.coin());
        if (coin == null || coin == Coin.NONE) throw new IllegalArgumentException("tool_invalid_request");
        if (!lock.tryBegin("operations-payout")) throw new IllegalStateException("power_mode_busy");
        try {
            String proxyUrl = proxy.coinUrl(coin);
            if (proxyUrl == null) throw new IllegalStateException("console_proxy_unavailable");
            boolean useDefault = request.wallet() == null || request.wallet().isBlank();
            String pool = request.pool(), wallet = request.wallet(), worker = request.worker(), defaultCpuLogin = null;
            if (useDefault) {
                var payout = defaults.resolveCached(coin.id()).orElseThrow(() -> new IllegalStateException("console_default_payout_unavailable"));
                pool = payout.poolUrl(); wallet = payout.walletPart(); worker = Objects.requireNonNullElse(payout.workerPart(), "solarminer");
                if (coin.isCpu()) {
                    defaultCpuLogin=payout.login();
                    int separator=defaultCpuLogin.lastIndexOf('.');
                    wallet=separator<0?defaultCpuLogin:defaultCpuLogin.substring(0,separator);
                    worker=separator<0?"solarminer":defaultCpuLogin.substring(separator+1);
                }
            }
            if (worker == null || !worker.matches(RouteValidator.WORKER_REGEX)) throw new IllegalArgumentException("tool_worker_invalid");
            validatePool(pool);
            if (coin.isCpu()) {
                if (wallet == null || !wallet.matches("^[1-9A-HJ-NP-Za-km-z]{95,120}$")) throw new IllegalArgumentException("tool_wallet_invalid");
                CoinMiner miner = miners.miner(coin);
                if (!miner.stopAll()) throw new IllegalStateException("console_stop_failed");
                xmr.configureXmrig(XmrDownloadService.CONFIG_PATH, proxyUrl, pool + ";" + (defaultCpuLogin==null?wallet + "." + worker:defaultCpuLogin) + ";x", false);
            } else {
                if (!WalletRule.forCoin(coin).valid(wallet)) throw new IllegalArgumentException("tool_wallet_invalid");
                List<LocalGpuPowerService.Gpu> cards = power.snapshot();
                Map<GpuCoinMiner, MinerConfig> plans = new LinkedHashMap<>();
                var assigned = miners.gpuMinerAdapters(coin).stream().filter(adapter -> cards.stream()
                        .anyMatch(card -> miners.workerUses(coin, card.deviceId(), adapter.softwareId()))).toList();
                if (assigned.isEmpty()) {
                    var miner = miners.gpuMiner(coin);
                    if (miner == null) throw new IllegalArgumentException("tool_adapter_missing");
                    // Preserve an existing device scope; otherwise select explicitly detected compatible cards.
                    String software=miners.gpuMinerAdapters(coin).stream().filter(adapter->adapter.miner()==miner).map(MinerFactory.GpuMinerAdapter::softwareId).findFirst().orElse("srbminer-multi");
                    var vendors=MinerCatalogService.supportedGpuVendors(software,coin.id());
                    String devices = miner.configuration() == null ? cards.stream().filter(card->vendors.contains(card.vendor())).map(card -> card.vendor() + ":" + card.index())
                            .collect(java.util.stream.Collectors.joining(",")) : miner.configuration().devices();
                    plans.put(miner, new MinerConfig(pool, proxyUrl, wallet, worker, devices));
                } else for (var adapter : assigned) {
                    String devices = cards.stream().filter(card -> miners.workerUses(coin, card.deviceId(), adapter.softwareId()))
                            .map(card -> card.vendor() + ":" + card.index()).collect(java.util.stream.Collectors.joining(","));
                    plans.put(adapter.miner(), new MinerConfig(pool, proxyUrl, wallet, worker, devices));
                }
                for (var entry : plans.entrySet()) entry.getKey().validateConfig(entry.getValue());
                for (var miner : plans.keySet()) if (!miner.stopAll()) throw new IllegalStateException("console_stop_failed");
                // Driver/process/file writes cannot be atomic across adapters. Report a partial write explicitly.
                try { for (var entry : plans.entrySet()) entry.getKey().applyConfig(entry.getValue()); }
                catch (IOException | RuntimeException failure) { throw new IllegalStateException("tool_route_partial", failure); }
            }
            defaults.markDefault(coin.id(), useDefault);
        } finally { lock.end(); }
    }
    private static void validatePool(String pool) {
        try {
            URI uri = URI.create(pool);
            if (!Set.of("stratum+tcp", "stratum+ssl").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException("tool_pool_invalid");
        } catch (NullPointerException | IllegalArgumentException failure) { throw new IllegalArgumentException("tool_pool_invalid"); }
    }
}
