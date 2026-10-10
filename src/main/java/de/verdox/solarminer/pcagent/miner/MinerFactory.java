package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
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

    public MinerFactory(XmrMinerService xmr, PearlMinerService pearl, GpuCoinMinerService gpuCoins, LocalGpuPowerService gpuPower) {
        this.xmr = xmr;
        this.pearl = pearl;
        this.gpuCoins = gpuCoins;
        this.gpuPower = gpuPower;
    }

    /** The miner for a known coin id, or empty for unknown ids and {@link Coin#NONE}. */
    public Optional<CoinMiner> miner(String coinId) {
        Coin coin = Coin.byIdOrNull(coinId);
        return coin == null || coin == Coin.NONE ? Optional.empty() : Optional.of(miner(coin));
    }

    public CoinMiner miner(Coin coin) {
        return switch (coin) {
            case MONERO -> xmr;
            case PEARL -> pearl;
            default -> new SrbGpuCoinView(gpuCoins, gpuPower, coin);
        };
    }

    /** The GPU-side miner view for a coin id, or empty when the coin has no GPU adapter. */
    public Optional<GpuCoinMiner> gpuMiner(String coinId) {
        Coin coin = Coin.byIdOrNull(coinId);
        return coin == null || !coin.isGpu() ? Optional.empty() : Optional.of(gpuMiner(coin));
    }

    public GpuCoinMiner gpuMiner(Coin coin) {
        return coin == Coin.PEARL ? pearl : new SrbGpuCoinView(gpuCoins, gpuPower, coin);
    }

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

    public boolean supports(String coinId) { return miner(coinId).isPresent(); }

    /** Direct access for the CPU-specific power-thread machinery that only XMRig exposes. */
    public XmrMinerService cpuBinary() { return xmr; }
}
