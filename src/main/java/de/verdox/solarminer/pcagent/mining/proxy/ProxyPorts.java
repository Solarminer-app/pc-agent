package de.verdox.solarminer.pcagent.mining.proxy;

import de.verdox.solarminer.pcagent.coin.Coin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * The single source of coin-to-port wiring for the stratum proxy. The managed proxy child
 * ({@link ManagedProxyService}), the proxy gate ({@link ProxyConfigurationService}) and LAN
 * proxy discovery ({@link ProxyDiscoveryService}) all consume this record; none of them may
 * declare its own coin->port mapping. Registering a new coin means adding one property here.
 */
@Component
public record ProxyPorts(int apiPort, int bitcoinPort, Map<Coin, Integer> coinPorts) {

    @Autowired
    public ProxyPorts(
            @Value("${solarminer.agent.proxy.api-port:8090}") int apiPort,
            @Value("${solarminer.agent.proxy.bitcoin-port:3333}") int bitcoinPort,
            @Value("${solarminer.agent.proxy.monero-port:3335}") int moneroPort,
            @Value("${solarminer.agent.proxy.pearl-port:3334}") int pearlPort,
            @Value("${solarminer.agent.proxy.ravencoin-port:3336}") int ravenPort,
            @Value("${solarminer.agent.proxy.ethereumclassic-port:3337}") int etcPort,
            @Value("${solarminer.agent.proxy.decred-port:3338}") int decredPort,
            @Value("${solarminer.agent.proxy.quantus-port:3339}") int quantusPort) {
        this(apiPort, bitcoinPort, portMap(moneroPort, pearlPort, ravenPort, etcPort, decredPort, quantusPort));
    }

    private static Map<Coin, Integer> portMap(int monero, int pearl, int raven, int etc, int decred, int quantus) {
        Map<Coin, Integer> ports = new EnumMap<>(Coin.class);
        ports.put(Coin.MONERO, monero);
        ports.put(Coin.PEARL, pearl);
        ports.put(Coin.RAVENCOIN, raven);
        ports.put(Coin.ETHEREUMCLASSIC, etc);
        ports.put(Coin.DECRED, decred);
        ports.put(Coin.QUANTUS, quantus);
        return Map.copyOf(ports);
    }

    /** The proxy's stratum listener port for a coin, or null when the coin has no listener. */
    public Integer portOrNull(Coin coin) { return coinPorts.get(coin); }

    /** The proxy's stratum listener port for a coin, or -1 when the coin has no listener. */
    public int port(Coin coin) {
        Integer port = coinPorts.get(coin);
        return port == null ? -1 : port;
    }
}
