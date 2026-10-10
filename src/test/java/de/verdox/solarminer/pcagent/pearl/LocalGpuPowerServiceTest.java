package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalGpuPowerServiceTest {
    @TempDir Path temp;

    @Test
    void nvidiaIsExposedOnlyAfterSameValueWriteAndReadback() {
        NvidiaRunner runner = new NvidiaRunner(Map.of("GPU-one", 250));
        LocalGpuPowerService service = service(runner);

        LocalGpuPowerService.Gpu gpu = service.discover().getFirst();

        assertTrue(gpu.supportsDynamicPowerScaling());
        assertNull(gpu.regulationError());
        assertTrue(runner.commands.contains(List.of("nvidia-smi", "-i", "GPU-one", "-pl", "250")));
        assertTrue(service.setTotalPowerTarget(180, List.of(gpu)));
        assertEquals(180, runner.limits.get("GPU-one"));
        assertEquals(180, service.discover().getFirst().currentPowerLimitWatts());
    }

    @Test
    void readableNvidiaCardWithoutWritePermissionIsNotAdvertisedAsControllable() {
        NvidiaRunner runner = new NvidiaRunner(Map.of("GPU-one", 250));
        runner.denyWrites = true;

        LocalGpuPowerService.Gpu gpu = service(runner).discover().getFirst();

        assertFalse(gpu.supportsDynamicPowerScaling());
        assertTrue(gpu.regulationError().contains("Administrator/root"));
    }

    @Test
    void multiGpuFailureRollsBackEveryAlreadyModifiedCardToLiveSnapshot() {
        NvidiaRunner runner = new NvidiaRunner(Map.of("GPU-one", 250, "GPU-two", 240));
        LocalGpuPowerService service = service(runner);
        List<LocalGpuPowerService.Gpu> cards = service.discover();
        runner.failChangedWriteFor = "GPU-two";

        assertFalse(service.setTotalPowerTarget(300, cards));
        assertEquals(250, runner.limits.get("GPU-one"));
        assertEquals(240, runner.limits.get("GPU-two"));
    }

    @Test
    void amdSmiUsesStablePciBdfAndVerifiesPowerCap() {
        AmdRunner runner = new AmdRunner();
        LocalGpuPowerService service = service(runner);

        LocalGpuPowerService.Gpu gpu = service.discover().getFirst();

        assertEquals("AMD", gpu.vendor());
        assertEquals("0000:03:00.0", gpu.deviceId());
        assertEquals("Radeon Test", gpu.model());
        assertEquals(80, gpu.driverMinWatts());
        assertEquals(250, gpu.driverMaxWatts());
        assertTrue(gpu.supportsDynamicPowerScaling());
        assertTrue(service.setTotalPowerTarget(170, List.of(gpu)));
        assertEquals(170, runner.limit);
        assertEquals(62.5, service.readTemperatureC(gpu));
    }

    @Test
    void pendingRecoveryIsAppliedByNextServiceInstance() {
        NvidiaRunner runner = new NvidiaRunner(Map.of("GPU-one", 250));
        Path settings = temp.resolve("limits.json");
        LocalGpuPowerService first = new LocalGpuPowerService(new ObjectMapper(), settings, runner);
        LocalGpuPowerService.Gpu gpu = first.discover().getFirst();
        assertTrue(first.setTotalPowerTarget(180, List.of(gpu)));
        assertEquals(180, runner.limits.get("GPU-one"));

        LocalGpuPowerService restarted = new LocalGpuPowerService(new ObjectMapper(), settings, runner);
        restarted.discover();

        assertEquals(250, runner.limits.get("GPU-one"));
    }

    @Test
    void linuxAmdSysfsFallbackWorksWithoutRocmInstallation() throws IOException {
        Path drm = temp.resolve("drm");
        Path pci = temp.resolve("devices/0000:03:00.0");
        Path hwmon = pci.resolve("hwmon/hwmon0");
        Files.createDirectories(hwmon);
        Files.writeString(pci.resolve("vendor"), "0x1002\n");
        Files.writeString(hwmon.resolve("power1_cap_min"), "80000000\n");
        Files.writeString(hwmon.resolve("power1_cap_max"), "250000000\n");
        Files.writeString(hwmon.resolve("power1_cap"), "200000000\n");
        Files.writeString(hwmon.resolve("power1_average"), "123500000\n");
        Files.writeString(hwmon.resolve("temp1_input"), "62500\n");
        Files.createDirectories(drm.resolve("card0"));
        Files.createSymbolicLink(drm.resolve("card0/device"), pci);
        LocalGpuPowerService.CommandRunner missingTools = command -> { throw new IOException("missing tool"); };
        LocalGpuPowerService service = new LocalGpuPowerService(new ObjectMapper(), temp.resolve("limits.json"), missingTools, drm);

        LocalGpuPowerService.Gpu gpu = service.discover().getFirst();

        assertEquals("0000:03:00.0", gpu.deviceId());
        assertTrue(gpu.supportsDynamicPowerScaling());
        assertEquals(123.5, gpu.currentWatts());
        assertTrue(service.setTotalPowerTarget(150, List.of(gpu)));
        assertEquals("150000000", Files.readString(hwmon.resolve("power1_cap")));
        assertEquals(62.5, service.readTemperatureC(gpu));
    }

    private LocalGpuPowerService service(LocalGpuPowerService.CommandRunner runner) {
        return new LocalGpuPowerService(new ObjectMapper(), temp.resolve("limits.json"), runner);
    }

    private static final class NvidiaRunner implements LocalGpuPowerService.CommandRunner {
        private final Map<String, Integer> limits = new LinkedHashMap<>();
        private final List<List<String>> commands = new ArrayList<>();
        private boolean denyWrites;
        private String failChangedWriteFor;

        private NvidiaRunner(Map<String, Integer> initial) { limits.putAll(initial); }

        @Override public String run(List<String> command) throws IOException {
            commands.add(List.copyOf(command));
            if (!"nvidia-smi".equals(command.getFirst())) throw new IOException("missing tool");
            if (command.stream().anyMatch(value -> value.startsWith("--query-gpu=index"))) {
                StringBuilder result = new StringBuilder();
                int index = 0;
                for (Map.Entry<String, Integer> entry : limits.entrySet())
                    result.append(index++).append(", ").append(entry.getKey()).append(", Test GPU, 100, 300, ")
                            .append(entry.getValue()).append(", 120\n");
                return result.toString();
            }
            String id = command.get(command.indexOf("-i") + 1);
            if (command.contains("-pl")) {
                int value = Integer.parseInt(command.get(command.indexOf("-pl") + 1));
                if (denyWrites || id.equals(failChangedWriteFor) && value != limits.get(id)) throw new IOException("Insufficient Permissions");
                limits.put(id, value);
                return "Power limit set";
            }
            if (command.stream().anyMatch(value -> value.contains("power.limit"))) return limits.get(id) + "\n";
            if (command.stream().anyMatch(value -> value.contains("temperature.gpu"))) return "60\n";
            throw new IOException("unknown command " + command);
        }
    }

    private static final class AmdRunner implements LocalGpuPowerService.CommandRunner {
        private int limit = 200;

        @Override public String run(List<String> command) throws IOException {
            if (!"amd-smi".equals(command.getFirst())) throw new IOException("missing tool");
            if (command.contains("list")) return "{\"gpu_data\":[{\"gpu\":0,\"bdf\":\"0000:03:00.0\",\"uuid\":\"11111111-2222-3333-4444-555555555555\"}]}";
            if (command.contains("static")) return "{\"gpu_data\":[{\"gpu\":0,\"asic\":{\"market_name\":\"Radeon Test\"},\"limit\":{\"min_power_cap\":\"80 W\",\"max_power_cap\":\"250 W\",\"power_cap\":\"" + limit + " W\"}}]}";
            if (command.contains("metric")) return "{\"gpu_data\":[{\"gpu\":0,\"power\":{\"current_socket_power\":\"123 W\"},\"temperature\":{\"hotspot_temperature\":\"62.5 C\"}}]}";
            if (command.contains("set")) {
                limit = Integer.parseInt(command.get(command.indexOf("--power-cap") + 1));
                return "Power cap set";
            }
            throw new IOException("unknown command " + command);
        }
    }
}
