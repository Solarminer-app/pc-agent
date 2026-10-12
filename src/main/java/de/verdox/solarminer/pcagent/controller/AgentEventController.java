package de.verdox.solarminer.pcagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Pushes local agent state to the browser as Server-Sent Events so the UI never needs to poll.
 * Each named channel re-serializes its snapshot on its own tick and emits only when the
 * serialized value changed; a page subscribes to exactly the channels it renders via
 * {@code GET /api/agent/local/events?channels=overview,telemetry}. The initial connect
 * delivers every subscribed channel once, which also heals gaps after a reconnect.
 *
 * <p>Tick intervals are deliberate: cheap in-memory state (overview, settings, benchmarks)
 * is checked every second, while channels that touch drivers, files or the network
 * (power-control, miner-options, energy, node-assessment) are checked slower. Telemetry
 * snapshots are collected at most once per second inside the sensor service. This is
 * local-agent UI state only; it introduces no Node, pool, fee or cross-repository wire
 * contract.</p>
 */
@RestController
@RequestMapping("/api/agent/local/events")
public class AgentEventController {

    private static final class Channel {
        final String name;
        final Supplier<Object> producer;
        final long intervalMs;
        final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        long lastEvalMs;
        String lastValue;

        Channel(String name, Supplier<Object> producer, long intervalMs) {
            this.name = name;
            this.producer = producer;
            this.intervalMs = intervalMs;
        }
    }

    private final List<Channel> channels = new ArrayList<>();
    private final AgentStatusService status;
    private final ObjectMapper mapper;
    private final AtomicLong revision = new AtomicLong();
    /**
     * An SSE response must return its headers before expensive initial snapshots (wallets,
     * proxy state, telemetry) are collected. Otherwise every page navigation waits for the
     * slowest subscribed producer before EventSource can reach its open state.
     */
    private final ExecutorService initialSnapshotExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AgentEventController(MiningController mining, TelemetryController telemetry,
                                WorkerController workers, AgentPowerController power,
                                BenchmarkController benchmarks,
                                NodeAssessmentController assessment,
                                de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService networkDashboard,
                                AgentStatusService status, EfficiencySweepController sweeps,
                                ObjectMapper mapper) {
        this.status = status;
        this.mapper = mapper;
        // REST/SSE loading plan P1 (2026-10-11): the dead "coin-catalog" and "status"
        // aggregate channels were deleted after a consumer grep over static/js confirmed
        // no page subscribes to them (workers.js/wallets.js read the REST
        // /status/coin-catalog; the aggregate stays REST-only as GET /local/status).
        // Phase 5b: the aggregated sections are collected once per tick inside
        // AgentStatusService and shared between the individual channels — the section-backed
        // producers below are cache reads, not re-computations.
        // overview already covers proxy, coin and worker stats and is the primary dashboard channel.
        add("overview", () -> status.payload("overview"), 1_000);
        add("fee-tier", () -> status.payload("fee-tier"), 5_000);
        add("benchmarks", benchmarks::status, 1_000);
        // Phase 5c: the efficiency sweep rides the same SSE stream as the benchmark session,
        // replacing the benchmark page's 1 s REST polling of /efficiency.
        add("efficiency", sweeps::status, 1_000);
        add("settings", () -> status.payload("settings"), 1_000);
        add("telemetry", () -> status.payload("telemetry"), 1_000);
        add("proxy-gate", () -> status.payload("proxy-gate"), 1_000);
        add("workers", workers::workers, 2_000);
        add("benchmark-upload", benchmarks::uploadStatus, 2_000);
        // power-control reads driver limits; keep driver traffic at the previous poll rate.
        add("power-control", power::status, 5_000);
        // miner-options checks installed files on disk.
        add("miner-options", mining::minerOptions, 5_000);
        add("energy", () -> status.payload("energy"), 5_000);
        add("node-assessment", () -> status.payload("node-assessment"), 5_000);
        // The wallet strip's three light reads, batched so it never pulls the full overview.
        add("wallets", () -> status.payload("wallets"), 15_000);
        // LAN peer agents: one aggregate read per tick; peer fetches are cached inside the service.
        add("network-agents", networkDashboard::cards, 5_000);
        // Derived alert/summary rows (Phase 5c): the overview page renders these directly
        // instead of re-computing alert rules from raw fields in JS.
        add("ui", status::uiState, 1_000);
    }

    private void add(String name, Supplier<Object> producer, long intervalMs) {
        channels.add(new Channel(name, producer, intervalMs));
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam(name = "channels", required = false) String requested) {
        Map<String, Channel> selected = select(requested);
        SseEmitter emitter = new SseEmitter(0L);
        for (Channel channel : selected.values()) {
            channel.subscribers.add(emitter);
        }
        emitter.onCompletion(() -> unsubscribe(emitter));
        emitter.onTimeout(() -> unsubscribe(emitter));
        emitter.onError(ignored -> unsubscribe(emitter));
        // Return immediately so EventSource.onopen is independent of producer latency. The
        // first payloads retain their existing per-channel shape and failure handling.
        // Do not queue lightweight settings/telemetry behind a cold overview collection. Each
        // channel has an independent cached producer, and SSE message order is intentionally
        // not a UI dependency; clients apply every channel independently.
        for (Channel channel : selected.values()) {
            initialSnapshotExecutor.submit(() -> send(emitter, channel.name, snapshot(channel)));
        }
        return emitter;
    }

    @PreDestroy
    void stopInitialSnapshotExecutor() {
        initialSnapshotExecutor.close();
    }

    private Map<String, Channel> select(String requested) {
        Map<String, Channel> selected = new LinkedHashMap<>();
        if (requested != null && !requested.isBlank()) {
            for (String name : requested.split(",")) {
                String key = name.trim();
                for (Channel channel : channels) if (channel.name.equals(key)) selected.put(key, channel);
            }
        }
        if (selected.isEmpty()) {
            // Backwards-compatible default: pages that predate the channels parameter only
            // ever listened for "overview".
            for (Channel channel : channels) if (channel.name.equals("overview")) selected.put(channel.name, channel);
        }
        return selected;
    }

    private void unsubscribe(SseEmitter emitter) {
        for (Channel channel : channels) channel.subscribers.remove(emitter);
    }

    /**
     * Emits every channel whose serialized snapshot changed since its own last check.
     * Dirty-flags (loading plan P2) take precedence over the interval: a channel reported
     * dirty by a local mutation is evaluated on the very next sweep; the interval/TTL stays
     * as the safety net for unreported changes (proxy heartbeat, Node pushes, download
     * progress, sensors).
     */
    @Scheduled(fixedDelayString = "${solarminer.agent.events.interval-ms:1000}")
    public void publishChanges() {
        long now = System.currentTimeMillis();
        for (Channel channel : channels) {
            if (channel.subscribers.isEmpty()) continue;
            boolean dirty = status.isDirty(channel.name);
            if (!dirty && now - channel.lastEvalMs < channel.intervalMs) continue;
            channel.lastEvalMs = now;
            String next = snapshot(channel);
            // Clear only after the snapshot: payload() consults the dirty set itself, so an
            // early clear would let the section cache answer and swallow the wake-up.
            if (dirty) status.clearDirty(channel.name);
            if (next.equals(channel.lastValue)) continue;
            channel.lastValue = next;
            for (SseEmitter emitter : channel.subscribers) send(emitter, channel.name, next);
        }
    }

    private String snapshot(Channel channel) {
        try {
            return mapper.writeValueAsString(channel.producer.get());
        } catch (Exception failure) {
            return "{\"error\":\"Agent channel " + channel.name + " could not be collected\"}";
        }
    }

    private void send(SseEmitter emitter, String channel, String data) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(revision.incrementAndGet()))
                    .name(channel).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException disconnected) {
            unsubscribe(emitter);
            emitter.complete();
        }
    }
}
