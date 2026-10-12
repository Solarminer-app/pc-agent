package de.verdox.solarminer.pcagent.miner.engine;

import de.verdox.solarminer.pcagent.miner.MinerConfig;

import java.net.URI;

/**
 * Shared route validation for the SRBMiner-based engines (refactoring Phase 2). The Pearl
 * and GPU-coin validators carried byte-identical pool/proxy/worker/device regexes; only the
 * wallet rule and the device syntax differ per coin, and those stay coin-owned via
 * {@link WalletRule} and the caller-supplied device pattern/messages.
 *
 * <p>The message strings are REST-visible: they surface in UI error responses and are
 * resolved through the closed i18n catalog, so callers pass their historical wording
 * verbatim (Pearl English, GPU coins German).</p>
 */
public final class RouteValidator {

    /** Pool endpoint: stratum+tcp or stratum+ssl with host and 1..65535 port digits. */
    public static final String POOL_URL_REGEX = "^stratum\\+(tcp|ssl)://[A-Za-z0-9.-]+:[1-9][0-9]{0,4}$";
    /** Proxy endpoint: plain stratum+tcp only (the local proxy never speaks TLS to miners). */
    public static final String PROXY_URL_REGEX = "^stratum\\+tcp://[A-Za-z0-9.-]+:[1-9][0-9]{0,4}$";
    /** Worker name shared by every SRBMiner engine. */
    public static final String WORKER_REGEX = "^[A-Za-z0-9_-]{1,32}$";

    private RouteValidator() { }

    /**
     * Validates pool, proxy, wallet and worker exactly once for both SRBMiner engines.
     * Device validation is caller-owned (Pearl accepts {@code all} and bare indexes,
     * GPU coins require explicit {@code VENDOR:index} lists), as is the wallet rule.
     */
    public static void validateRoute(MinerConfig value, WalletRule wallet,
                                     String poolMessage, String proxyMessage,
                                     String walletMessage, String workerMessage) {
        if (value == null || value.poolUrl() == null || !value.poolUrl().matches(POOL_URL_REGEX))
            throw new IllegalArgumentException(poolMessage);
        int port = URI.create(value.poolUrl()).getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException(poolMessage);
        if (value.proxyUrl() == null || !value.proxyUrl().matches(PROXY_URL_REGEX)
                || URI.create(value.proxyUrl()).getPort() > 65535)
            throw new IllegalArgumentException(proxyMessage);
        if (!wallet.valid(value.wallet())) throw new IllegalArgumentException(walletMessage);
        if (value.worker() == null || !value.worker().matches(WORKER_REGEX))
            throw new IllegalArgumentException(workerMessage);
    }

    /** Validates the device list; both engines keep their own syntax and message. */
    public static void validateDevices(String devices, String regex, String message) {
        if (devices == null || !devices.matches(regex)) throw new IllegalArgumentException(message);
    }

    /**
     * The shared {@code updateProxyRoute} body: swaps only the proxy endpoint of an
     * existing configuration and returns the updated copy, or {@code null} when there is
     * nothing to change (no stored config or the proxy is already identical).
     */
    public static MinerConfig withProxyRoute(MinerConfig current, String proxyUrl) {
        if (current == null || current.proxyUrl().equals(proxyUrl)) return null;
        return new MinerConfig(current.poolUrl(), proxyUrl, current.wallet(), current.worker(), current.devices());
    }
}
