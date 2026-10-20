package de.verdox.solarminer.pcagent.console;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, process-local jobs. Accepted writes are never retried automatically. */
@Service
public class ConsoleCommandService {
    private static final int MAX_JOBS = 128;
    private final ConsoleWorkspaceService workspace;
    private final ConsoleWorkerOperations operations;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().name("console-command-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, MutableJob> jobs = new LinkedHashMap<>();
    private final Set<Target> reserved = new HashSet<>();

    public ConsoleCommandService(ConsoleWorkspaceService workspace, ConsoleWorkerOperations operations) {
        this.workspace = workspace;
        this.operations = operations;
    }

    public record Target(String agentId, String deviceId) { }
    public record Request(String action, List<Target> targets, Assignment assignment) {
        public Request(String action, List<Target> targets) { this(action, targets, null); }
    }
    public record Assignment(String coin, String minerSoftwareId) { }
    public record Result(Target target, String state, String error, String detail) { }
    public record Job(String id, String action, Instant createdAt, boolean complete, List<Result> results) { }
    private static class MutableJob {
        final String id = UUID.randomUUID().toString();
        final String action;
        final Instant createdAt = Instant.now();
        final List<Result> results = new ArrayList<>();
        MutableJob(Request request) {
            action = request.action();
            request.targets().forEach(target -> results.add(new Result(target, "queued", null, null)));
        }
        synchronized Job snapshot() {
            return new Job(id, action, createdAt,
                    results.stream().allMatch(result -> Set.of("succeeded", "failed").contains(result.state())),
                    List.copyOf(results));
        }
        synchronized void set(int index, String state, String error, String detail) {
            results.set(index, new Result(results.get(index).target(), state, error, detail));
        }
    }

    public synchronized Job submit(Request request) {
        if (request == null || !Set.of("start", "pause", "assign").contains(Objects.toString(request.action(), ""))
                || request.targets() == null || request.targets().isEmpty() || request.targets().size() > 64
                || request.targets().stream().anyMatch(target -> target == null || target.agentId() == null
                || target.deviceId() == null || target.agentId().isBlank() || target.deviceId().isBlank()
                || target.agentId().length() > 200 || target.deviceId().length() > 200)
                || new HashSet<>(request.targets()).size() != request.targets().size())
            throw new Rejected("console_invalid_command", false);
        if ("assign".equals(request.action()) && (request.assignment() == null || request.assignment().coin() == null
                || request.assignment().coin().length() > 80 || request.assignment().minerSoftwareId() == null
                || request.assignment().minerSoftwareId().length() > 80))
            throw new Rejected("console_invalid_command", false);
        // Copy request input before crossing threads.
        Request accepted = new Request(request.action(), List.copyOf(request.targets()), request.assignment());
        if (accepted.targets().stream().anyMatch(reserved::contains)) throw new Rejected("console_already_pending", true);
        evict();
        if (jobs.size() >= MAX_JOBS) throw new Rejected("console_queue_full", true);
        MutableJob job = new MutableJob(accepted);
        reserved.addAll(accepted.targets());
        jobs.put(job.id, job);
        try { executor.execute(() -> run(job, accepted)); }
        catch (RejectedExecutionException full) {
            jobs.remove(job.id);
            reserved.removeAll(accepted.targets());
            throw new Rejected("console_queue_full", true);
        }
        return job.snapshot();
    }

    private void run(MutableJob job, Request request) {
        for (int index = 0; index < request.targets().size(); index++) {
            Target target = request.targets().get(index);
            job.set(index, "running", null, null);
            try {
                var worker = workspace.find(target.agentId(), target.deviceId()).orElse(null);
                String blocked = worker == null ? "console_worker_missing" : worker.stale() ? "console_stale"
                        : "assign".equals(request.action()) ? worker.own() ? null : "console_local_configuration_only"
                        : "start".equals(request.action()) ? worker.startBlocked() : worker.pauseBlocked();
                if (worker != null && "STARTING".equals(worker.status())) blocked = "worker_start_in_progress";
                String error = blocked != null ? blocked : "assign".equals(request.action())
                        ? operations.assign(target, request.assignment()) : operations.apply(target, "start".equals(request.action()));
                job.set(index, error == null ? "succeeded" : "failed", error, null);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                String message = failure.getMessage();
                String code = ConsoleFailureCodes.forMessage(message);
                job.set(index, "failed", code, "console_action_failed".equals(code) ? message : null);
            } catch (RuntimeException failure) {
                job.set(index, "failed", "console_action_failed", null);
            } finally {
                synchronized (this) { reserved.remove(target); }
            }
        }
        // Refresh is background work; the command response never waits for hardware discovery.
        workspace.collect();
    }

    public synchronized Optional<Job> get(String id) {
        MutableJob job = jobs.get(id);
        return job == null ? Optional.empty() : Optional.of(job.snapshot());
    }
    private void evict() {
        Instant cutoff = Instant.now().minusSeconds(1800);
        jobs.values().removeIf(job -> job.snapshot().complete() && (job.createdAt.isBefore(cutoff) || jobs.size() >= MAX_JOBS));
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
    public static class Rejected extends RuntimeException {
        private final boolean conflict;
        public Rejected(String key, boolean conflict) { super(key); this.conflict = conflict; }
        public boolean conflict() { return conflict; }
    }
}
