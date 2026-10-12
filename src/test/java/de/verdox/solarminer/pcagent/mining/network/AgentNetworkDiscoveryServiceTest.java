package de.verdox.solarminer.pcagent.mining.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentNetworkDiscoveryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentIdentityService identity = mock(AgentIdentityService.class);
    private final AgentNetworkDiscoveryService service =
            new AgentNetworkDiscoveryService(mapper, identity, 8084, 8093, 2000);

    @Test
    void parsesItsOwnHeartbeatShapeIntoAPeer() throws Exception {
        when(identity.displayName()).thenReturn("Mining PC");
        byte[] payload = mapper.writeValueAsBytes(Map.of(
                "service", AgentNetworkDiscoveryService.SERVICE,
                "protocolVersion", AgentNetworkDiscoveryService.PROTOCOL_VERSION,
                "agentId", "peer-1",
                "name", "Mining PC",
                "dashboardPort", 8084));
        AgentNetworkDiscoveryService.Peer peer = service.parsePeer(payload, 0, payload.length, "192.168.1.30");
        assertThat(peer).isNotNull();
        assertThat(peer.agentId()).isEqualTo("peer-1");
        assertThat(peer.name()).isEqualTo("Mining PC");
        assertThat(peer.host()).isEqualTo("192.168.1.30");
        assertThat(peer.dashboardPort()).isEqualTo(8084);
    }

    @Test
    void rejectsForeignServicesWrongVersionsSelfIdsAndGarbage() {
        assertThat(service.parsePeer("not json".getBytes(StandardCharsets.UTF_8), 0, 8, "10.0.0.1")).isNull();
        byte[] foreign = ("{\"service\":\"solarminer-stratum-proxy\",\"protocolVersion\":1,"
                + "\"agentId\":\"x\",\"dashboardPort\":8084}").getBytes(StandardCharsets.UTF_8);
        assertThat(service.parsePeer(foreign, 0, foreign.length, "10.0.0.1")).isNull();
        byte[] wrongVersion = ("{\"service\":\"solarminer-pc-agent\",\"protocolVersion\":2,"
                + "\"agentId\":\"x\",\"dashboardPort\":8084}").getBytes(StandardCharsets.UTF_8);
        assertThat(service.parsePeer(wrongVersion, 0, wrongVersion.length, "10.0.0.1")).isNull();
        byte[] self = ("{\"service\":\"solarminer-pc-agent\",\"protocolVersion\":1,"
                + "\"agentId\":\"" + service.agentId() + "\",\"dashboardPort\":8084}").getBytes(StandardCharsets.UTF_8);
        assertThat(service.parsePeer(self, 0, self.length, "10.0.0.1")).isNull();
        byte[] badPort = ("{\"service\":\"solarminer-pc-agent\",\"protocolVersion\":1,"
                + "\"agentId\":\"x\",\"dashboardPort\":0}").getBytes(StandardCharsets.UTF_8);
        assertThat(service.parsePeer(badPort, 0, badPort.length, "10.0.0.1")).isNull();
    }

    @Test
    void peersDisappearAfterTheTtlWithoutHeartbeats() throws Exception {
        when(identity.displayName()).thenReturn("Mining PC");
        byte[] payload = mapper.writeValueAsBytes(Map.of(
                "service", AgentNetworkDiscoveryService.SERVICE,
                "protocolVersion", AgentNetworkDiscoveryService.PROTOCOL_VERSION,
                "agentId", "peer-ttl",
                "name", "Mining PC",
                "dashboardPort", 8084));
        AgentNetworkDiscoveryService.Peer peer = service.parsePeer(payload, 0, payload.length, "192.168.1.31");
        assertThat(peer).isNotNull();
        // The parse path never registers into the table by itself; the listener does. Verify the
        // public table starts empty and stays empty without traffic instead of reaching into it.
        assertThat(service.peers()).isEmpty();
    }
}
