package de.verdox.solarminer.pcagent.mining.economics;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.support.AtomicFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable modelled revenue comparison; it is not pool payout accounting. */
@Service
public class CoinHoppingLedger {
    private static final int MAX_ACTIONS = 200;
    private static final Duration MAX_INTERVAL = Duration.ofMinutes(20);
    private final ObjectMapper json;
    private final Path file;
    private State state;

    public CoinHoppingLedger(ObjectMapper json,
                             @Value("${solarminer.agent.coin-hopping-ledger-file:./solarminer-agent/coin-hopping-ledger.json}") String path) {
        this.json = json;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.state = load();
    }

    private State load() {
        try {
            State saved = json.readValue(Files.readString(file), State.class);
            return saved == null ? State.empty() : saved;
        } catch (Exception ignored) {
            return State.empty();
        }
    }

    public synchronized void begin(String deviceId, int targetWatts, Instant now) {
        if (deviceId == null || targetWatts <= 0) return;
        if (deviceId.equals(state.deviceId()) && targetWatts == state.targetWatts()) return;
        persist(new State(deviceId, targetWatts, now, now, 0, 0, Map.of(), Map.of(), List.of()));
    }

    /** Break continuity across unavailable market data, measurement runs and stopped workers. */
    public synchronized void mark(Instant now) {
        if (state.deviceId() == null) return;
        persist(new State(state.deviceId(), state.targetWatts(), state.startedAt(), now,
                state.accountedSeconds(), state.actualUsd(), state.actualCoins(), state.fixed(), state.actions()));
    }

    public synchronized void account(Instant now, String currentCoin, Instant activeStartedAt,
                                     List<CoinHoppingService.Candidate> candidates) {
        if (state.deviceId() == null) return;
        CoinHoppingService.Candidate actual = candidates.stream()
                .filter(value -> value.coin().equals(currentCoin)).findFirst().orElse(null);
        if (actual == null || activeStartedAt == null || state.lastEvaluatedAt() == null) {
            mark(now); return;
        }
        Instant start = state.lastEvaluatedAt();
        if (activeStartedAt.isAfter(start)) start = activeStartedAt;
        if (now.minus(MAX_INTERVAL).isAfter(start)) start = now.minus(MAX_INTERVAL);
        long seconds = Math.max(0, Duration.between(start, now).toSeconds());
        if (seconds == 0) { mark(now); return; }
        // Compare every fixed coin against the same estimated energy consumed by the
        // active coin. Equal driver limits can still produce different measured watts.
        double energyKwh = actual.measuredWatts() * seconds / 3_600_000.0;
        Map<String, Double> actualCoins = new LinkedHashMap<>(state.actualCoins());
        actualCoins.merge(currentCoin, coinsPerKwh(actual) * energyKwh, Double::sum);
        Map<String, FixedEstimate> fixed = new LinkedHashMap<>(state.fixed());
        for (CoinHoppingService.Candidate candidate : candidates) {
            FixedEstimate previous = fixed.getOrDefault(candidate.coin(), new FixedEstimate(0, 0, 0));
            fixed.put(candidate.coin(), new FixedEstimate(previous.coveredSeconds() + seconds,
                    previous.usd() + candidate.usdPerKwh() * energyKwh,
                    previous.coins() + coinsPerKwh(candidate) * energyKwh));
        }
        persist(new State(state.deviceId(), state.targetWatts(), state.startedAt(), now,
                state.accountedSeconds() + seconds, state.actualUsd() + actual.usdPerKwh() * energyKwh,
                Map.copyOf(actualCoins), Map.copyOf(fixed), state.actions()));
    }

    private static double coinsPerKwh(CoinHoppingService.Candidate candidate) {
        return candidate.coinsPerDay() / (candidate.measuredWatts() * 24.0 / 1000.0);
    }

    public synchronized void action(Instant at, String outcome, String fromCoin, String toCoin,
                                    double gainPercent, double fromUsdPerKwh, double toUsdPerKwh) {
        if (state.deviceId() == null) return;
        List<Action> actions = new ArrayList<>(state.actions());
        actions.add(new Action(at, outcome, fromCoin, toCoin, gainPercent, state.targetWatts(),
                fromUsdPerKwh, toUsdPerKwh));
        if (actions.size() > MAX_ACTIONS) actions = actions.subList(actions.size() - MAX_ACTIONS, actions.size());
        persist(new State(state.deviceId(), state.targetWatts(), state.startedAt(), state.lastEvaluatedAt(),
                state.accountedSeconds(), state.actualUsd(), state.actualCoins(), state.fixed(), List.copyOf(actions)));
    }

    public synchronized Summary summary() {
        FixedEstimate best = state.fixed().values().stream()
                .filter(value -> value.coveredSeconds() == state.accountedSeconds() && state.accountedSeconds() > 0)
                .max(Comparator.comparingDouble(FixedEstimate::usd)).orElse(null);
        String coin = best == null ? null : state.fixed().entrySet().stream()
                .filter(entry -> entry.getValue().equals(best)).map(Map.Entry::getKey).findFirst().orElse(null);
        return new Summary(state.startedAt(), state.accountedSeconds(), state.actualUsd(), state.actualCoins(),
                coin, best == null ? null : best.usd(), best == null ? null : best.coins(),
                best == null ? null : state.actualUsd() - best.usd(),
                state.actions().reversed());
    }

    private void persist(State next) {
        if (!AtomicFileStore.writeJson(file, next, json, "coin-hopping-ledger-"))
            throw new IllegalStateException("coin_hopping_ledger_save_failed");
        state = next;
    }

    private record State(String deviceId, int targetWatts, Instant startedAt, Instant lastEvaluatedAt,
                         long accountedSeconds, double actualUsd, Map<String, Double> actualCoins,
                         Map<String, FixedEstimate> fixed, List<Action> actions) {
        static State empty() { return new State(null, 0, null, null, 0, 0, Map.of(), Map.of(), List.of()); }
    }
    private record FixedEstimate(long coveredSeconds, double usd, double coins) { }
    public record Action(Instant at, String outcome, String fromCoin, String toCoin,
                         double gainPercent, int targetWatts, double fromUsdPerKwh, double toUsdPerKwh) { }
    public record Summary(Instant startedAt, long accountedSeconds, double actualUsd, Map<String, Double> actualCoins,
                          String bestFixedCoin, Double bestFixedUsd, Double bestFixedCoins, Double gainUsd,
                          List<Action> actions) { }
}
