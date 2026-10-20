package de.verdox.solarminer.pcagent.console;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Bounded tool writes with client-chosen IDs: a lost acceptance response can be read back without repeating a write. */
@Service
public class ConsoleToolJobs {
    private final ConsoleToolsService tools;
    private final Map<String, Entry> jobs = new LinkedHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().name("operations-tool-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    public ConsoleToolJobs(ConsoleToolsService tools) { this.tools = tools; }
    public record Request(String id, String action, JsonNode arguments) { }
    public record Job(String id, String action, Instant createdAt, String state, String error, String detail) { }
    private static class Entry {
        final Request request;
        volatile Job job;
        Entry(Request request) { this.request = request; job = new Job(request.id(), request.action(), Instant.now(), "queued", null, null); }
    }
    public synchronized Job submit(Request request) {
        if (request == null || request.id() == null || !request.id().matches("[a-zA-Z0-9-]{16,80}")
                || request.action()==null || !ConsoleToolsService.ACTIONS.contains(request.action()) || request.arguments() == null
                || !request.arguments().isObject() || request.arguments().toString().length() > 32_768)
            throw new IllegalArgumentException("tool_invalid_request");
        Entry previous = jobs.get(request.id());
        if (previous != null) {
            if (!previous.request.equals(request)) throw new IllegalArgumentException("tool_request_id_conflict");
            return previous.job;
        }
        jobs.values().removeIf(entry -> Set.of("succeeded", "failed").contains(entry.job.state())
                && (entry.job.createdAt().isBefore(Instant.now().minusSeconds(1800)) || jobs.size() >= 128));
        if (jobs.size() >= 128) throw new IllegalStateException("console_queue_full");
        Entry entry = new Entry(new Request(request.id(), request.action(), request.arguments().deepCopy()));
        jobs.put(request.id(), entry);
        try { executor.execute(() -> execute(entry)); }
        catch (RejectedExecutionException full) { jobs.remove(request.id()); throw new IllegalStateException("console_queue_full"); }
        return entry.job;
    }
    private void execute(Entry entry) {
        entry.job = new Job(entry.job.id(), entry.job.action(), entry.job.createdAt(), "running", null, null);
        try {
            tools.execute(entry.request.action(), entry.request.arguments());
            entry.job = new Job(entry.job.id(), entry.job.action(), entry.job.createdAt(), "succeeded", null, null);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            String message = failure.getMessage();
            String code = message != null && message.matches("(?:tool|auto_tuning|gpu|power_mode|benchmark|update|console)_[a-z0-9_]+")
                    ? message : ConsoleFailureCodes.forMessage(message);
            entry.job = new Job(entry.job.id(), entry.job.action(), entry.job.createdAt(), "failed", code,
                    "console_action_failed".equals(code) ? message : null);
        }
    }
    public synchronized Optional<Job> get(String id) { return Optional.ofNullable(jobs.get(id)).map(entry -> entry.job); }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
