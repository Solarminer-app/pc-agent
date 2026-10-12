package de.verdox.solarminer.pcagent.mining.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers peer PC-Agents on the same LAN. Mirrors the stratum proxy's discovery design: every
 * sharing-enabled agent periodically broadcasts a versioned heartbeat and answers a versioned
 * query, while each agent keeps a live peer table from everything it hears. This is read-only
 * visibility only — discovery carries identity and the dashboard port, never control data.
 *
 * <p>One socket on {@code heartbeatPort} (SO_REUSEADDR) receives peer heartbeats and probe
 * queries. Probes go to the same heartbeat port; a peer's unicast reply comes back to the
 * sender's socket, so a single receive loop feeds the peer table. Peers older than the TTL drop
 * out of {@link #peers()} without extra bookkeeping.</p>
 */
@Service
public class AgentNetworkDiscoveryService {
    public static final String REQUEST = "SOLARMINER_AGENT_DISCOVER_V1";
    static final String SERVICE = "solarminer-pc-agent";
    static final int PROTOCOL_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(AgentNetworkDiscoveryService.class);
    private static final Duration PEER_TTL = Duration.ofSeconds(20);
    private static final int PROBE_INTERVAL_TICKS = 15; // every ~30 s at the default heartbeat tick
    private static final int RECEIVE_TIMEOUT_MS = 500;

    private final ObjectMapper mapper;
    private final AgentIdentityService identity;
    private final String agentId = UUID.randomUUID().toString();
    private final int dashboardPort;
    private final int heartbeatPort;
    private final long heartbeatIntervalMs;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private volatile DatagramSocket socket;

    /** A peer agent heard on the LAN. {@code host} is the packet source address. */
    public record Peer(String agentId, String name, String host, int dashboardPort, Instant lastSeen) { }

    public AgentNetworkDiscoveryService(ObjectMapper mapper, AgentIdentityService identity,
            @Value("${server.port:8084}") int dashboardPort,
            @Value("${solarminer.agent.network.heartbeat-port:8093}") int heartbeatPort,
            @Value("${solarminer.agent.network.heartbeat-interval-ms:2000}") long heartbeatIntervalMs) {
        this.mapper = mapper;
        this.identity = identity;
        this.dashboardPort = dashboardPort;
        this.heartbeatPort = heartbeatPort;
        this.heartbeatIntervalMs = Math.max(500, heartbeatIntervalMs);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            DatagramSocket listener = new DatagramSocket(null);
            listener.setReuseAddress(true);
            listener.setBroadcast(true);
            listener.bind(new java.net.InetSocketAddress(heartbeatPort));
            listener.setSoTimeout(RECEIVE_TIMEOUT_MS);
            socket = listener;
            log.info("LAN agent discovery listening on UDP {}", heartbeatPort);
        } catch (IOException e) {
            log.warn("LAN agent discovery socket could not be opened; the network dashboard stays degraded", e);
            return;
        }
        Thread.ofVirtual().name("solarminer-agent-discovery-listener").start(this::listen);
        Thread.ofVirtual().name("solarminer-agent-discovery-publisher").start(this::publish);
    }

    /** Live peers only: fresh heartbeats keep entries, silence drops them after the TTL. */
    public List<Peer> peers() {
        Instant cutoff = Instant.now().minus(PEER_TTL);
        peers.entrySet().removeIf(entry -> entry.getValue().lastSeen().isBefore(cutoff));
        return peers.values().stream()
                .sorted((a, b) -> a.host().compareTo(b.host()))
                .toList();
    }

    public String agentId() { return agentId; }

    /** Accepts peer heartbeats and answers probe queries on the shared socket. */
    private void listen() {
        DatagramSocket listener = socket;
        if (listener == null) return;
        byte[] buffer = new byte[2048];
        byte[] query = REQUEST.getBytes(StandardCharsets.US_ASCII);
        try {
            while (!listener.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    listener.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                if (!(packet.getAddress() instanceof Inet4Address)) continue;
                int length = packet.getLength();
                if (length == query.length
                        && java.util.Arrays.equals(query, 0, query.length,
                                packet.getData(), packet.getOffset(), packet.getOffset() + length)) {
                    // Identity is always answered: visibility consent gates the DATA endpoint,
                    // not discovery itself, so opted-out machines still appear as peers.
                    byte[] response = payload();
                    listener.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                    continue;
                }
                Peer peer = parsePeer(packet.getData(), packet.getOffset(), length,
                        packet.getAddress().getHostAddress());
                if (peer != null) peers.put(peer.agentId(), peer);
            }
        } catch (IOException e) {
            if (!listener.isClosed()) log.warn("LAN agent discovery listener stopped unexpectedly", e);
        }
    }

    /** Broadcasts our heartbeat while sharing is on and periodically probes for peers. */
    private void publish() {
        DatagramSocket sender = socket;
        if (sender == null) return;
        byte[] query = REQUEST.getBytes(StandardCharsets.US_ASCII);
        try {
            for (int tick = 0; ; tick++) {
                // Identity heartbeats run regardless of the sharing consent: the consent hides
                // worker/earnings data on the HTTP endpoint, never the machine itself.
                byte[] payload = payload();
                for (InetAddress broadcast : broadcastAddresses()) {
                    try {
                        sender.send(new DatagramPacket(payload, payload.length, broadcast, heartbeatPort));
                    } catch (IOException e) {
                        log.debug("Could not publish agent heartbeat to {}", broadcast, e);
                    }
                }
                if (tick % PROBE_INTERVAL_TICKS == 0) {
                    for (InetAddress broadcast : broadcastAddresses()) {
                        try {
                            sender.send(new DatagramPacket(query, query.length, broadcast, heartbeatPort));
                        } catch (IOException e) {
                            log.debug("Could not probe for peer agents on {}", broadcast, e);
                        }
                    }
                }
                Thread.sleep(heartbeatIntervalMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!sender.isClosed()) log.warn("LAN agent heartbeat publisher stopped unexpectedly", e);
        }
    }

    private byte[] payload() throws IOException {
        return mapper.writeValueAsBytes(Map.of(
                "service", SERVICE,
                "protocolVersion", PROTOCOL_VERSION,
                "agentId", agentId,
                "name", identity.displayName(),
                "dashboardPort", dashboardPort));
    }

    /** Parses one discovery packet; package-private so unit tests can drive it directly. */
    Peer parsePeer(byte[] data, int offset, int length, String host) {
        try {
            JsonNode json = mapper.readTree(data, offset, length);
            if (!SERVICE.equals(json.path("service").asText())
                    || json.path("protocolVersion").asInt() != PROTOCOL_VERSION) return null;
            String peerId = json.path("agentId").asText();
            if (peerId.isBlank() || peerId.equals(agentId)) return null; // never list ourselves
            String name = json.path("name").asText("");
            int port = json.path("dashboardPort").asInt(8084);
            if (port < 1 || port > 65535) return null;
            return new Peer(peerId, name.isBlank() ? null : name, host, port, Instant.now());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Set<InetAddress> broadcastAddresses() throws IOException {
        Set<InetAddress> broadcasts = new LinkedHashSet<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            String name = network.getName().toLowerCase(Locale.ROOT);
            if (!network.isUp() || network.isLoopback() || network.isVirtual()
                    || name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth")
                    || name.startsWith("tailscale") || name.startsWith("wsl")) continue;
            network.getInterfaceAddresses().stream()
                    .filter(address -> address.getAddress() instanceof Inet4Address && address.getBroadcast() != null)
                    .map(InterfaceAddress::getBroadcast).forEach(broadcasts::add);
        }
        broadcasts.add(InetAddress.getByName("255.255.255.255"));
        return broadcasts;
    }

    @PreDestroy
    public void stop() {
        DatagramSocket current = socket;
        if (current != null) current.close();
    }
}
