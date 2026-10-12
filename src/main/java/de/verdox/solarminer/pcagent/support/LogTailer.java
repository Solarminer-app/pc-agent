package de.verdox.solarminer.pcagent.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Bounded chunked reading of a growing log file, shared by the miner consoles and the
 * proxy startup gate (both previously carried their own FileChannel copy). A stale or
 * negative offset restarts from the beginning, matching the operator-console behaviour.
 */
public final class LogTailer {

    private LogTailer() { }

    /**
     * @param nextOffset byte offset to pass into the next read
     * @param bytes      the raw chunk (may be empty when the file is empty)
     * @param hasMore    true when more bytes follow after {@code nextOffset}
     */
    public record Chunk(long nextOffset, byte[] bytes, boolean hasMore) { }

    public static Chunk read(Path path, long offset, int maxBytes) throws IOException {
        if (!Files.isRegularFile(path)) return new Chunk(0, new byte[0], false);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long length = channel.size();
            long start = offset < 0 || offset > length ? 0 : offset;
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(maxBytes, length - start));
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
            int count = buffer.position();
            return new Chunk(start + count, java.util.Arrays.copyOf(buffer.array(), count),
                    start + count < length);
        }
    }
}
