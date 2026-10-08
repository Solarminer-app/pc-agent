package de.verdox.solarminer.pcagent.mining;

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

    public Path file(String miner) {
        if (miner.matches("(pearl|ravencoin|ethereumclassic|decred|quantus)-(NVIDIA|AMD)-[0-9]{1,5}"))
            return directory.resolve(miner + "-console.log");
        return directory.resolve(switch (miner) {
            case "monero" -> "xmrig-console.log";
            case "pearl" -> "srbminer-console.log";
            case "ravencoin", "ethereumclassic", "decred", "quantus" -> miner + "-console.log";
            default -> throw new IllegalArgumentException("Unknown miner");
        });
    }

    public record ConsoleChunk(long nextOffset, String runId, String data, boolean hasMore) { }
}
