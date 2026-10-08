package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds SolarMiner proxies with a short LAN-only UDP broadcast, then verifies each hit over HTTP. */
@Service
public class ProxyDiscoveryService {
    private static final String REQUEST = "SOLARMINER_PROXY_DISCOVER_V1";
    private static final String SERVICE = "solarminer-stratum-proxy";
    private static final int PROTOCOL_VERSION = 1;
    private static final int DISCOVERY_TIMEOUT_MS = 2200;
    private final ObjectMapper mapper;
    private final int discoveryPort;
    private final int expectedApiPort;
    private final int expectedMoneroPort;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build();

    public ProxyDiscoveryService(ObjectMapper mapper,
            @Value("${solarminer.agent.proxy.discovery-port:8091}") int discoveryPort,
            @Value("${solarminer.agent.proxy.api-port:8090}") int expectedApiPort,
            @Value("${solarminer.agent.proxy.monero-port:3335}") int expectedMoneroPort) {
        this.mapper = mapper;
        this.discoveryPort = discoveryPort;
        this.expectedApiPort = expectedApiPort;
        this.expectedMoneroPort = expectedMoneroPort;
    }

    public List<ProxyCandidate> discover() throws java.io.IOException {
        byte[] query = REQUEST.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Map<String, ProxyCandidate> candidates = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new java.net.InetSocketAddress(0));
            for (InetAddress broadcast : broadcastAddresses()) {
                socket.send(new DatagramPacket(query, query.length, broadcast, discoveryPort));
            }
            long deadline = System.nanoTime() + Duration.ofMillis(DISCOVERY_TIMEOUT_MS).toNanos();
            byte[] buffer = new byte[2048];
            while (System.nanoTime() < deadline && candidates.size() < 32) {
                long remainingMs = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
                socket.setSoTimeout((int) Math.max(1, Math.min(300, remainingMs)));
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                if (!(packet.getAddress() instanceof Inet4Address)) continue;
                ProxyCandidate candidate = parseCandidate(packet);
                if (candidate == null || !isHttpProxy(candidate)) continue;
                candidates.putIfAbsent(candidate.host() + ":" + candidate.apiPort(), candidate);
            }
        }
        return List.copyOf(candidates.values());
    }

    private ProxyCandidate parseCandidate(DatagramPacket packet) {
        try {
            JsonNode json = mapper.readTree(packet.getData(), packet.getOffset(), packet.getLength());
            if (!SERVICE.equals(json.path("service").asText()) || json.path("protocolVersion").asInt() != PROTOCOL_VERSION)
                return null;
            int apiPort = json.path("apiPort").asInt(8090);
            if (apiPort != expectedApiPort) return null;
            Map<String, Integer> ports = new LinkedHashMap<>();
            JsonNode coins = json.path("coins");
            if (!coins.isObject()) return null;
            coins.fields().forEachRemaining(entry -> {
                int port = entry.getValue().asInt(-1);
                if (port > 0 && port <= 65535) ports.put(entry.getKey(), port);
            });
            if (ports.getOrDefault("monero", -1) != expectedMoneroPort) return null;
            return new ProxyCandidate(packet.getAddress().getHostAddress(), apiPort,
                    ports.get("monero"), ports.getOrDefault("pearl", 0), ports);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isHttpProxy(ProxyCandidate candidate) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + candidate.host() + ":"
                            + candidate.apiPort() + "/api/network/ip"))
                    .timeout(Duration.ofSeconds(1)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && !response.body().isBlank();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Set<InetAddress> broadcastAddresses() throws java.io.IOException {
        Set<InetAddress> broadcasts = new java.util.LinkedHashSet<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            String name = network.getName().toLowerCase(Locale.ROOT);
            if (!network.isUp() || network.isLoopback() || network.isVirtual()
                    || name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth")
                    || name.startsWith("tailscale") || name.startsWith("wsl")) continue;
            network.getInterfaceAddresses().stream()
                    .filter(address -> address.getAddress() instanceof Inet4Address && address.getBroadcast() != null)
                    .map(java.net.InterfaceAddress::getBroadcast).forEach(broadcasts::add);
        }
        broadcasts.add(InetAddress.getByName("255.255.255.255"));
        return broadcasts;
    }

    public record ProxyCandidate(String host, int apiPort, int moneroPort, int pearlPort,
                                 Map<String, Integer> stratumPorts) { }
}
