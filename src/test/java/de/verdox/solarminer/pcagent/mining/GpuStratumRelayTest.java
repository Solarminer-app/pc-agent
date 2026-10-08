package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class GpuStratumRelayTest {
    @Test
    void preamblePrecedesSubscribeAndBothDirectionsSurviveMinerReconnect() throws Exception {
        var mapper = new ObjectMapper();
        try (var proxy = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             var relay = new GpuStratumRelay("stratum+tcp://127.0.0.1:" + proxy.getLocalPort(), "wallet.sm1.route.rig", mapper)) {
            proxy.setSoTimeout(3000);
            int port = URI.create(relay.localUrl()).getPort();
            for (int connection = 0; connection < 2; connection++) {
                try (var miner = new Socket("127.0.0.1", port); var upstream = proxy.accept()) {
                    miner.setSoTimeout(3000);
                    upstream.setSoTimeout(3000);
                    var fromMiner = new BufferedReader(new InputStreamReader(upstream.getInputStream(), StandardCharsets.UTF_8));
                    var fromPool = new BufferedReader(new InputStreamReader(miner.getInputStream(), StandardCharsets.UTF_8));
                    String subscribe = "{\"id\":17,\"method\":\"mining.subscribe\",\"params\":[]}";
                    miner.getOutputStream().write((subscribe + "\n").getBytes(StandardCharsets.UTF_8));
                    var preamble = mapper.readTree(fromMiner.readLine());
                    assertEquals("solarminer.route", preamble.path("method").asText());
                    assertEquals("wallet.sm1.route.rig", preamble.path("params").get(0).asText());
                    assertEquals(subscribe, fromMiner.readLine());
                    String reply = "{\"id\":17,\"result\":[null,\"abcd\"],\"error\":null}";
                    upstream.getOutputStream().write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                    assertEquals(reply, fromPool.readLine());
                }
            }
        }
    }

    @Test
    void closeClosesListenerAndActiveMinerConnections() throws Exception {
        try (var proxy = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(3000);
            var relay = new GpuStratumRelay("stratum+tcp://127.0.0.1:" + proxy.getLocalPort(), "login", new ObjectMapper());
            int port = URI.create(relay.localUrl()).getPort();
            try (relay; var miner = new Socket("127.0.0.1", port); var upstream = proxy.accept()) {
                miner.setSoTimeout(3000);
                relay.close();
                assertEquals(-1, miner.getInputStream().read());
                assertThrows(java.io.IOException.class, () -> new Socket("127.0.0.1", port));
            }
        }
    }
}
