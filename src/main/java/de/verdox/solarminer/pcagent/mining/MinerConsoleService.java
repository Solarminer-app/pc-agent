package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.coin.Coin;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Keeps local diagnostics bounded so an unattended homelab cannot fill its system disk. */
@Service
public class MinerConsoleService {
    private static final Logger LOGGER = Logger.getLogger(MinerConsoleService.class.getName());
    private static final int CHUNK_BYTES = 64 * 1024;
    private static final long MAX_LOG_BYTES = 10L * 1024 * 1024;
    private final Path directory;
    /** Identifies the current process run so a browser can discard output retained before a truncation. */
    private final Map<String, String> runIds = new ConcurrentHashMap<>();

    public MinerConsoleService() {
        this(Path.of("./solarminer-agent/logs").toAbsolutePath().normalize());
    }

    MinerConsoleService(Path directory) {
        this.directory = directory;
    }

    public synchronized void started(String miner) {
        try {
            Files.createDirectories(directory);
            Files.writeString(file(miner), "===== " + Instant.now() + " · New miner start =====\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            runIds.put(miner, UUID.randomUUID().toString());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not reset " + miner + " console output", e);
        }
    }

    public synchronized void append(String miner, String line) {
        try {
            Files.createDirectories(directory);
            Path target = file(miner);
            byte[] next = (line + "\n").getBytes(StandardCharsets.UTF_8);
            rotateIfNeeded(target, next.length);
            Files.write(target, next,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save " + miner + " console output", e);
        }
    }

    /** Retain one previous log rather than silently deleting diagnostics on rotation. */
    private void rotateIfNeeded(Path target, int nextBytes) throws IOException {
        if (!Files.isRegularFile(target) || Files.size(target) + nextBytes <= MAX_LOG_BYTES) return;
        Path previous = target.resolveSibling(target.getFileName() + ".1");
        Files.move(target, previous, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public ConsoleChunk read(String miner, long offset) throws IOException {
        Path path = file(miner);
        if (!Files.isRegularFile(path)) return new ConsoleChunk(0, runIds.getOrDefault(miner, ""), "", false);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long length = channel.size();
            long start = offset < 0 || offset > length ? 0 : offset;
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(CHUNK_BYTES, length - start));
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
            int count = buffer.position();
            return new ConsoleChunk(start + count, runIds.getOrDefault(miner, ""),
                    Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buffer.array(), count)),
                    start + count < length);
        }
    }

    /** Per-GPU console id of any GPU coin, e.g. "pearl-NVIDIA-0". */
    public static boolean isGpuConsoleId(String miner) {
        return miner != null && miner.matches(
                "(" + String.join("|", Coin.gpuCoins().stream().map(Coin::id).toList()) + ")-(NVIDIA|AMD)-[0-9]{1,5}");
    }

    /** True for every console id the agent knows: a mining coin or one of its GPU consoles. */
    public static boolean isKnownConsole(String miner) {
        return miner != null && (Coin.miningCoins().stream().anyMatch(coin -> coin.id().equals(miner)) || isGpuConsoleId(miner));
    }

    public Path file(String miner) {
        if (isGpuConsoleId(miner)) return directory.resolve(miner + "-console.log");
        Coin coin = Coin.byIdOrNull(miner);
        // Historical file names stay as they are: the operator UI and bug reports reference them.
        if (coin == Coin.MONERO) return directory.resolve("xmrig-console.log");
        if (coin == Coin.PEARL) return directory.resolve("srbminer-console.log");
        if (coin != null && coin != Coin.NONE) return directory.resolve(coin.id() + "-console.log");
        throw new IllegalArgumentException("Unknown miner");
    }

    public record ConsoleChunk(long nextOffset, String runId, String data, boolean hasMore) { }
}
