package de.verdox.solarminer.pcagent.mining.measurement;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Compact latest-run chart projection. Search and partial samples never become confirmed profiles. */
@Service
public class TuningCurveService {
    private final AutoTuningProfileStore store;
    public TuningCurveService(AutoTuningProfileStore store) { this.store = store; }

    public List<Point> points(String deviceId) {
        var rows = store.measurements().stream().filter(row -> deviceId.equals(row.deviceId())).toList();
        Map<String, AutoTuningProfileStore.Measurement> latest = new HashMap<>();
        for (var row : rows) if (row.at() != null) latest.merge(row.coinId() + "|" + row.softwareId(), row,
                (a, b) -> a.at().isAfter(b.at()) ? a : b);
        Map<Setting, Window> windows = new LinkedHashMap<>();
        List<Point> points = new ArrayList<>();
        for (var row : rows) {
            var last = latest.get(row.coinId() + "|" + row.softwareId());
            if (last == null || row.at() == null || !Objects.equals(last.runId(), row.runId())) continue;
            var key = new Setting(row.runId(), row.coinId(), row.softwareId(), row.powerLimitWatts(),
                    row.coreMhz(), row.memoryMhz(), row.memoryOffsetMhz(), row.memoryOffsetPstate());
            var window = windows.computeIfAbsent(key, ignored -> new Window());
            if ("SAMPLE".equals(row.type())) {
                if (positive(row.hashrateHs()) && positive(row.measuredWatts())) {
                    window.samples.add(row);
                    if (window.samples.size() > 32) window.samples.removeFirst();
                }
            } else if ("RESULT".equals(row.type()) || "SEARCH_RESULT".equals(row.type())) {
                window.samples.clear();
                if (positive(row.hashrateHs()) && positive(row.measuredWatts())) points.add(point(row, row.type(),
                        row.hashrateHs(), row.measuredWatts(), row.temperatureC(), row.observedCoreMhz(), row.observedMemoryMhz(),
                        row.validSamples() == null ? 0 : row.validSamples(), row.stable()));
            }
        }
        for (var window : windows.values()) if (!window.samples.isEmpty()) {
            var last = window.samples.getLast();
            points.add(point(last, "SAMPLE", median(window.samples.stream().mapToDouble(row -> row.hashrateHs()).toArray()),
                    window.samples.stream().mapToDouble(row -> row.measuredWatts()).average().orElseThrow(),
                    window.samples.stream().map(AutoTuningProfileStore.Measurement::temperatureC).filter(TuningCurveService::positive)
                            .max(Double::compare).orElse(null),
                    clock(window.samples.stream().map(AutoTuningProfileStore.Measurement::observedCoreMhz).toList()),
                    clock(window.samples.stream().map(AutoTuningProfileStore.Measurement::observedMemoryMhz).toList()),
                    (int) window.samples.stream().filter(row -> row.reason() == null && positive(row.temperatureC())).count(),
                    window.samples.stream().anyMatch(row -> row.reason() != null || !positive(row.temperatureC())) ? false : null));
        }
        return points.stream().sorted(Comparator.comparing(Point::at)).toList();
    }
    private static Integer clock(List<Integer> clocks) {
        double[] valid = clocks.stream().filter(value -> value != null && value > 0).mapToDouble(Integer::doubleValue).toArray();
        return valid.length == 0 ? null : (int) Math.round(median(valid));
    }
    private static double median(double[] values) { return SampleCollector.median(values); }
    private static boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }
    private static Point point(AutoTuningProfileStore.Measurement row, String stage, double rate, double watts,
                               Double temperature, Integer core, Integer memory, int samples, Boolean stable) {
        return new Point(row.runId(), row.at(), row.coinId(), row.softwareId(), row.softwareVersion(), row.powerLimitWatts(),
                rate, watts, core, memory, row.coreMhz(), row.memoryMhz(), row.memoryOffsetMhz(), row.memoryOffsetPstate(),
                temperature, stage, stable, samples);
    }
    private record Setting(String run, String coin, String software, int watts, Integer core, Integer memory, Integer offset, Integer pstate) { }
    private static class Window { final List<AutoTuningProfileStore.Measurement> samples = new ArrayList<>(); }
    public record Point(String runId, Instant at, String coin, String softwareId, String softwareVersion, int limitWatts,
                        double hashrateHs, double measuredWatts, Integer coreMhz, Integer memoryMhz,
                        Integer targetCoreMhz, Integer targetMemoryMhz, Integer memoryOffsetMhz, Integer memoryOffsetPstate,
                        Double temperatureC, String stage, Boolean stable, int samples) { }
}
