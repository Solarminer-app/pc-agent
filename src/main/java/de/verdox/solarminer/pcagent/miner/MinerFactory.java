package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolMinerService;
import de.verdox.solarminer.pcagent.miner.peak.PeakMinerService;
import de.verdox.solarminer.pcagent.miner.wildrig.WildRigMinerService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The single seam through which orchestration code obtains miners. The main code asks for a
 * coin by identity and receives the adapter that knows its binary, parameters and APIs; it
 * never branches on which coin is being mined. Adding a coin means registering one adapter —
 * nothing in the services below this seam changes.
 */
@Component
public class MinerFactory {
    private final XmrMinerService xmr;
    private final PearlMinerService pearl;
    private final GpuCoinMinerService gpuCoins;
    private final LocalGpuPowerService gpuPower;
    private final QuanPoolMinerService quanPool;
    private final PeakMinerService peak;
    private final WildRigMinerService wildRig;
    private final MinerCatalogService catalog;
    private final AgentControlSettingsService controls;
    private final java.util.concurrent.ConcurrentMap<String, String> benchmarkSoftware = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public MinerFactory(XmrMinerService xmr, PearlMinerService pearl, GpuCoinMinerService gpuCoins,
                        LocalGpuPowerService gpuPower, QuanPoolMinerService quanPool,
                        PeakMinerService peak, WildRigMinerService wildRig, MinerCatalogService catalog,
                        AgentControlSettingsService controls) {
        this.xmr = xmr;
        this.pearl = pearl;
        this.gpuCoins = gpuCoins;
        this.gpuPower = gpuPower;
        this.quanPool = quanPool;
        this.peak = peak;
        this.wildRig = wildRig;
        this.catalog = catalog;
        this.controls = controls;
    }

    /** Test/backward-compatible constructor without optional miner selection. */
    public MinerFactory(XmrMinerService xmr, PearlMinerService pearl, GpuCoinMinerService gpuCoins,
                        LocalGpuPowerService gpuPower) {
        this(xmr, pearl, gpuCoins, gpuPower, null, null, null, null, null);
    }

    /** Focused-test compatibility constructor from before worker-specific miner profiles. */
    public MinerFactory(XmrMinerService xmr, PearlMinerService pearl, GpuCoinMinerService gpuCoins,
                        LocalGpuPowerService gpuPower, QuanPoolMinerService quanPool, MinerCatalogService catalog) {
        this(xmr, pearl, gpuCoins, gpuPower, quanPool, null, null, catalog, null);
    }

    public MinerFactory(XmrMinerService xmr, PearlMinerService pearl, GpuCoinMinerService gpuCoins,
                        LocalGpuPowerService gpuPower, QuanPoolMinerService quanPool, MinerCatalogService catalog,
                        AgentControlSettingsService controls) {
        this(xmr, pearl, gpuCoins, gpuPower, quanPool, null, null, catalog, controls);
    }

    /** The miner for a known coin id, or empty for unknown ids and {@link Coin#NONE}. */
    public Optional<CoinMiner> miner(String coinId) {
        Coin coin = Coin.byIdOrNull(coinId);
        return coin == null || coin == Coin.NONE ? Optional.empty() : Optional.of(miner(coin));
    }

    public CoinMiner miner(Coin coin) {
        return switch (coin) {
            case MONERO -> xmr;
            case PEARL -> gpuMiner(coin);
            default -> gpuMiner(coin);
        };
    }

    /** The GPU-side miner view for a coin id, or empty when the coin has no GPU adapter. */
    public Optional<GpuCoinMiner> gpuMiner(String coinId) {
        Coin coin = Coin.byIdOrNull(coinId);
        return coin == null || !coin.isGpu() ? Optional.empty() : Optional.of(gpuMiner(coin));
    }

    public GpuCoinMiner gpuMiner(Coin coin) {
        String selected = catalog == null || catalog.selected(coin.id()) == null
                ? "srbminer-multi" : catalog.selected(coin.id()).id();
        return gpuMiner(coin, selected);
    }

    /** Resolves a concrete software adapter without changing the coin-wide legacy default. */
    public GpuCoinMiner gpuMiner(Coin coin, String minerSoftwareId) {
        if (coin == Coin.PEARL) return peak != null && "peakminer".equals(minerSoftwareId) ? peak : pearl;
        if (coin == Coin.QUANTUS && quanPool != null && "quanpool-miner".equals(minerSoftwareId)) return quanPool;
        if (coin == Coin.QUANTUS && wildRig != null && "wildrig-ng".equals(minerSoftwareId)) return wildRig;
        return new SrbGpuCoinView(gpuCoins, gpuPower, coin);
    }

    public Optional<GpuCoinMiner> gpuMinerForWorker(String coinId, String deviceId) {
        Coin coin = Coin.byIdOrNull(coinId);
        if (coin == null || !coin.isGpu()) return Optional.empty();
        String fallback = catalog == null || catalog.selected(coin.id()) == null
                ? "srbminer-multi" : catalog.selected(coin.id()).id();
        String minerId = benchmarkSoftware.getOrDefault(deviceId,
                controls == null ? fallback : controls.get().minerFor(deviceId, fallback));
        return Optional.of(gpuMiner(coin, minerId));
    }

    public String minerSoftwareForWorker(Coin coin, String deviceId) {
        String fallback = catalog == null || catalog.selected(coin.id()) == null
                ? (coin == Coin.MONERO ? "xmrig" : "srbminer-multi") : catalog.selected(coin.id()).id();
        return benchmarkSoftware.getOrDefault(deviceId,
                controls == null ? fallback : controls.get().minerFor(deviceId, fallback));
    }

    /** A different coin's measurement uses its catalog default rather than the worker's saved miner. */
    public String minerSoftwareForMeasurement(Coin coin, String deviceId) {
        String fallback = catalog == null || catalog.selected(coin.id()) == null
                ? "srbminer-multi" : catalog.selected(coin.id()).id();
        return controls != null && coin.id().equals(controls.get().coinFor(deviceId))
                ? controls.get().minerFor(deviceId, fallback) : fallback;
    }

    /** Only the locked benchmark may temporarily attribute a GPU to another installed adapter. */
    public void setBenchmarkSoftware(String deviceId, String softwareId) { benchmarkSoftware.put(deviceId, softwareId); }
    public void clearBenchmarkSoftware(String deviceId) { benchmarkSoftware.remove(deviceId); }

    public boolean workerUses(Coin coin, String deviceId, String minerSoftwareId) {
        return controls != null && coin.id().equals(controls.get().coinFor(deviceId))
                && minerSoftwareId.equals(minerSoftwareForWorker(coin, deviceId));
    }

    /** Every concrete adapter available for a coin; used for mixed-miner stop and telemetry. */
    public List<GpuMinerAdapter> gpuMinerAdapters(Coin coin) {
        if (coin == Coin.PEARL && peak != null)
            return List.of(new GpuMinerAdapter("srbminer-multi", pearl),
                    new GpuMinerAdapter("peakminer", peak));
        if (coin == Coin.QUANTUS && quanPool != null && wildRig != null)
            return List.of(new GpuMinerAdapter("srbminer-multi", gpuMiner(coin, "srbminer-multi")),
                    new GpuMinerAdapter("quanpool-miner", quanPool),
                    new GpuMinerAdapter("wildrig-ng", wildRig));
        if (coin == Coin.QUANTUS && quanPool != null)
            return List.of(new GpuMinerAdapter("srbminer-multi", gpuMiner(coin, "srbminer-multi")),
                    new GpuMinerAdapter("quanpool-miner", quanPool));
        return List.of(new GpuMinerAdapter("srbminer-multi", gpuMiner(coin, "srbminer-multi")));
    }

    public record GpuMinerAdapter(String softwareId, GpuCoinMiner miner) { }

    /** The CPU-side miner view for a coin id, or empty when the coin has no CPU adapter. */
    public Optional<CpuMiner> cpuMiner(String coinId) {
        Coin coin = Coin.byIdOrNull(coinId);
        return coin == null || !coin.isCpu() ? Optional.empty() : Optional.of((CpuMiner) miner(coin));
    }

    /** The GPU view for coins served by the shared SRBMiner adapter; Pearl has its own adapter. */
    public Optional<GpuCoinMiner> srbGpuMiner(String coinId) {
        Coin coin = Coin.byIdOrNull(coinId);
        return coin == null || coin == Coin.PEARL || !coin.isGpu() ? Optional.empty() : Optional.of(gpuMiner(coin));
    }

    /** Every GPU adapter, in coin registration order. */
    public List<GpuCoinMiner> gpuMiners() {
        return Coin.gpuCoins().stream().map(this::gpuMiner).toList();
    }

    /**
     * The shared SRBMiner installation gate (Phase 3 reference-binary predicate): one
     * versioned binary serves Pearl and every SRB GPU coin, so orchestration asks the
     * seam instead of naming Pearl.
     */
    public boolean srbBinaryAvailable() { return pearl.binaryAvailable(); }

    /** True when an SRBMiner process started outside the agent occupies the shared binary. */
    public boolean srbExternalMinerRunning() { return pearl.hasExternalMinerProcess(); }

    public boolean supports(String coinId) { return miner(coinId).isPresent(); }

    /** Direct access for the CPU-specific power-thread machinery that only XMRig exposes. */
    public XmrMinerService cpuBinary() { return xmr; }
}
