package de.verdox.solarminer.pcagent.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Section-demand background collection with retained source age; no driver/network I/O on GET. */
@Service
public class ConsoleToolsReadService {
    public static final Set<String> SECTIONS=Set.of("wallets","measurements","hardware","tuning","updates","logs");
    private final ConsoleToolsService tools;
    private final ObjectMapper json;
    private final Map<String, Snapshot> snapshots=new ConcurrentHashMap<>();
    private final Map<String, Long> watched=new ConcurrentHashMap<>();
    private final Set<String> collecting=ConcurrentHashMap.newKeySet();
    private final ExecutorService executor=Executors.newFixedThreadPool(2,Thread.ofPlatform().name("operations-snapshot-",0).factory());
    public ConsoleToolsReadService(ConsoleToolsService tools,ObjectMapper json) { this.tools=tools; this.json=json; }
    public record Snapshot(Instant collectedAt, JsonNode payload, String error) { }
    public synchronized Snapshot read(String section,String device) {
        if (!SECTIONS.contains(section)) throw new IllegalArgumentException("tool_invalid_request");
        String key="tuning".equals(section)?section+"\n"+Objects.requireNonNullElse(device,""):section;
        if (key.length()>240) throw new IllegalArgumentException("tool_invalid_request");
        long now=System.currentTimeMillis();
        watched.entrySet().removeIf(entry->{ if (now-entry.getValue()>60000) { snapshots.remove(entry.getKey()); return true; } return false; });
        snapshots.keySet().retainAll(watched.keySet());
        if (!watched.containsKey(key) && watched.size()>=128) throw new IllegalStateException("console_queue_full");
        watched.put(key,now);
        Snapshot value=snapshots.get(key);
        if (value==null) schedule(key);
        if (value==null) return new Snapshot(null,null,null);
        return project(value,device);
    }
    private Snapshot project(Snapshot snapshot,String device) {
        if (!(snapshot.payload() instanceof ObjectNode object)) return snapshot;
        ObjectNode result=json.createObjectNode();
        object.fields().forEachRemaining(entry->result.set(entry.getKey(),entry.getValue()));
        for (String field:List.of("workers","gpus","capabilities","sessions","profiles","measurements","benchmarks","efficiency")) {
            if (!result.path(field).isArray()) continue;
            var values=json.createArrayNode(); int total=0;
            for (JsonNode value:result.path(field)) {
                String rowDevice=value.path("deviceId").asText(value.path("workerId").asText(""));
                if (device!=null && !device.isBlank() && !device.equals(rowDevice)) continue;
                total++;
                values.add(value);
                if (values.size()>200) values.remove(0);
            }
            result.set(field,values); result.put(field+"Total", Math.max(total,object.path(field+"Total").asInt(total)));
        }
        if(result.path("benchmark") instanceof ObjectNode benchmark) {
            ObjectNode bounded=benchmark.deepCopy();
            for(String field:List.of("results","liveSeries","autoStartResults")) if(bounded.path(field).isArray()) {
                var values=json.createArrayNode();var original=bounded.path(field);
                for(int index=Math.max(0,original.size()-50);index<original.size();index++) {
                    JsonNode value=original.get(index);
                    if(field.equals("liveSeries") && value instanceof ObjectNode series) {
                        var trace=series.deepCopy(); var points=trace.path("points");
                        trace.put("sampleCount",points.size());var latest=json.createArrayNode();
                        if(!points.isEmpty()) latest.add(points.get(points.size()-1)); trace.set("points",latest);value=trace;
                    }
                    values.add(value);
                }
                bounded.put(field+"Total",original.size());bounded.set(field,values);
            }
            result.set("benchmark",bounded);
        }
        if(result.path("sweep") instanceof ObjectNode sweep) {
            var bounded=sweep.deepCopy();var runs=bounded.path("runs");var values=json.createArrayNode();
            for(int index=Math.max(0,runs.size()-50);index<runs.size();index++) values.add(runs.get(index));
            bounded.put("runsTotal",runs.size());bounded.set("runs",values);result.set("sweep",bounded);
        }
        // Calibration measurements are intentionally device-scoped; unrelated GPU history never enters the browser.
        if (result.has("selections") && device!=null && !device.isBlank()) result.set("selection",result.path("selections").path(device));
        result.remove("selections");
        return new Snapshot(snapshot.collectedAt(),result,snapshot.error());
    }
    @Scheduled(fixedDelay=2000) public void refresh() {
        long now=System.currentTimeMillis();
        watched.forEach((section,last)->{ if (now-last<30_000) schedule(section); });
    }
    private void schedule(String section) {
        if (!collecting.add(section)) return;
        executor.execute(()->{
            try {
                JsonNode value=json.valueToTree(tools.collect(section.split("\n",2)[0],section.contains("\n")?section.split("\n",2)[1]:null));
                snapshots.put(section,new Snapshot(Instant.now(),value,null));
            }
            catch (Exception failure) {
                Snapshot previous=snapshots.get(section);
                snapshots.put(section,new Snapshot(previous==null?null:previous.collectedAt(),previous==null?null:previous.payload(),"tool_load_failed"));
            } finally { collecting.remove(section); }
        });
    }
    public Object export(String section) {
        return tools.export(section);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
