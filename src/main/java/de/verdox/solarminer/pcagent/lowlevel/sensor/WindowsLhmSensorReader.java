package de.verdox.solarminer.pcagent.lowlevel.sensor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.HardwareIdentityService;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WindowsLhmSensorReader implements HardwareSensorReader {

    private static final Logger LOGGER = Logger.getLogger(WindowsLhmSensorReader.class.getName());
    private final HttpClient httpClient;
    private final HardwareIdentityService hardwareIdentityService;
    private final ObjectMapper objectMapper;
    private final WindowsLhmBootstrapService bootstrapService;

    private double cachedWatts = -1.0;
    private double cachedTemp = -1.0;
    private JsonNode cachedRoot;
    private long lastFetchTime = 0;

    public WindowsLhmSensorReader(HardwareIdentityService hardwareIdentityService, ObjectMapper objectMapper,
                                  WindowsLhmBootstrapService bootstrapService) {
        this.hardwareIdentityService = hardwareIdentityService;
        this.objectMapper = objectMapper;
        this.bootstrapService = bootstrapService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override
    public double getCpuPowerWatts() {
        fetchDataIfNeeded();
        return cachedWatts;
    }

    @Override
    public double getCpuTemperatureCelsius() {
        fetchDataIfNeeded();
        return cachedTemp;
    }

    @Override
    public boolean isAccurate() {
        return true;
    }

    public synchronized List<SensorReading> getAvailableSensors() {
        fetchDataIfNeeded();
        if (cachedRoot == null) return List.of();
        List<SensorReading> readings = new ArrayList<>();
        collectSensors(cachedRoot, "system", readings);
        return List.copyOf(readings);
    }

    public record SensorReading(String key, double value, String unit, String hardwareName) { }

    private synchronized void fetchDataIfNeeded() {
        if (System.currentTimeMillis() - lastFetchTime < 2000) {
            return;
        }

        try {

            HttpRequest.Builder builder = HttpRequest.newBuilder(bootstrapService.dataUri())
                    .timeout(Duration.ofSeconds(2)).GET();
            String authorization = bootstrapService.authorizationHeader();
            if (authorization != null) builder.header("Authorization", authorization);
            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                cachedRoot = root;
                parseLhmJson(root);
            } else {
                cachedRoot = null;
                cachedWatts = -1.0;
                cachedTemp = -1.0;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not connect to LibreHardwareMonitor on port 8085. Is it running?");
            cachedWatts = -1.0;
            cachedTemp = -1.0;
            cachedRoot = null;
        } finally {
            lastFetchTime = System.currentTimeMillis();
        }
    }

    private void collectSensors(JsonNode node, String hardware, List<SensorReading> readings) {
        String owner = node.has("HardwareId") ? node.path("Text").asText(hardware) : hardware;
        if (node.has("SensorId") && node.has("Type") && node.path("RawValue").isNumber()) {
            String type = node.path("Type").asText("");
            String unit = unitFor(type);
            double value = node.path("RawValue").asDouble(Double.NaN);
            if (unit != null && Double.isFinite(value) && value >= 0) {
                String label = node.path("Text").asText(type);
                readings.add(new SensorReading(safeKey(owner + "." + label + "." + type), value, unit, owner));
            }
        }
        JsonNode children = node.path("Children");
        if (children.isArray()) for (JsonNode child : children) collectSensors(child, owner, readings);
    }

    private static String unitFor(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "temperature" -> "°C";
            case "power" -> "W";
            case "energy" -> "Wh";
            case "load" -> "%";
            case "fan" -> "rpm";
            case "voltage" -> "V";
            case "current" -> "A";
            case "clock" -> "MHz";
            case "frequency" -> "Hz";
            case "data" -> "B";
            case "smalldata" -> "B";
            case "throughput" -> "B/s";
            case "flow" -> "L/h";
            case "humidity", "level", "control" -> "%";
            case "noise" -> "dB";
            case "factor" -> "1";
            default -> null;
        };
    }

    private static String safeKey(String label) {
        String key = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return "hardware." + (key.isBlank() ? "sensor" : key);
    }

    private void parseLhmJson(JsonNode rootNode) {
        JsonNode cpuNode = findNodeByNameOrType(rootNode, hardwareIdentityService.getProcessor());
        if (cpuNode != null) {
            JsonNode tempCategory = findNodeByNameOrType(cpuNode, "Temperatures");
            if (tempCategory != null && tempCategory.has("Children")) {
                JsonNode firstTemp = tempCategory.get("Children").get(0);
                cachedTemp = parseValueString(firstTemp.path("Value").asText());
            }

            JsonNode powerCategory = findNodeByNameOrType(cpuNode, "Powers");
            if (powerCategory != null && powerCategory.has("Children")) {
                for (JsonNode sensor : powerCategory.get("Children")) {
                    if (sensor.path("Text").asText().toLowerCase().contains("package")) {
                        cachedWatts = parseValueString(sensor.path("Value").asText());
                        break;
                    }
                }
            }
        }
    }

    private JsonNode findNodeByNameOrType(JsonNode current, String keyword) {
        String text = current.path("Text").asText("").toLowerCase();
        String image = current.path("ImageURL").asText("").toLowerCase();
        String term = keyword.toLowerCase();
        if ((!text.isBlank() && (text.contains(term) || term.contains(text))) || image.contains(term)) {
            return current;
        }
        if (current.has("Children")) {
            for (JsonNode child : current.get("Children")) {
                JsonNode found = findNodeByNameOrType(child, keyword);
                if (found != null) return found;
            }
        }
        return null;
    }

    private double parseValueString(String val) {
        if (val == null || val.isBlank() || val.equals("-")) return -1.0;
        String cleanNumber = val.replaceAll("[^0-9.,-]", "");
        if (cleanNumber.contains(",") && cleanNumber.contains(".")) {
            cleanNumber = cleanNumber.replace(",", "");
        } else {
            cleanNumber = cleanNumber.replace(',', '.');
        }
        try {
            return Double.parseDouble(cleanNumber);
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }
}
