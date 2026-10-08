package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Loopback-only adapter: supply the route before the unmodified miner subscribes.
 * The sole outbound destination is the configured SolarMiner proxy, never the pool.
 */
public final class GpuStratumRelay implements AutoCloseable {
    private final ServerSocket listener;
    private final InetSocketAddress proxy;
    private final byte[] preamble;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public GpuStratumRelay(String proxyUrl, String login, ObjectMapper mapper) throws IOException {
        URI uri = URI.create(proxyUrl);
        if (!"stratum+tcp".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1)
            throw new IOException("GPU relay requires a TCP SolarMiner proxy");
        proxy = new InetSocketAddress(uri.getHost(), uri.getPort());
        var route = mapper.createObjectNode().put("method", "solarminer.route");
        route.putArray("params").add(login).add("x");
        preamble = (route + "\n").getBytes(StandardCharsets.UTF_8);
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        Thread.ofVirtual().name("gpu-stratum-relay").start(this::accept);
    }

    public String localUrl() { return "stratum+tcp://127.0.0.1:" + listener.getLocalPort(); }

    private void accept() {
        while (!closed) {
            try {
                Socket miner = listener.accept();
                sockets.add(miner);
                if (closed || sockets.size() > 8) { release(miner); continue; }
                Thread.ofVirtual().name("gpu-stratum-connection").start(() -> relay(miner));
            } catch (IOException ignored) { close(); }
        }
    }

    private void relay(Socket miner) {
        Socket upstream = new Socket();
        sockets.add(upstream);
        try {
            if (closed) return;
            upstream.connect(proxy, 5000);
            if (closed) return;
            miner.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            upstream.getOutputStream().write(preamble);
            upstream.getOutputStream().flush();
            Thread.ofVirtual().name("gpu-stratum-replies").start(() -> {
                try { upstream.getInputStream().transferTo(miner.getOutputStream()); }
                catch (IOException ignored) { }
                finally { release(miner); release(upstream); }
            });
            miner.getInputStream().transferTo(upstream.getOutputStream());
        } catch (IOException ignored) { }
        finally { release(miner); release(upstream); }
    }

    private void release(Socket socket) {
        sockets.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        sockets.forEach(this::release);
    }
}
