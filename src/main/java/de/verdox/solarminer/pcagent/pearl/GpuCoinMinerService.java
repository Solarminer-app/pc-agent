package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import de.verdox.solarminer.pcagent.mining.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.MinerProcessRegistry;
import de.verdox.solarminer.pcagent.mining.MinerStopContext;
import de.verdox.solarminer.pcagent.mining.MinerShareTelemetry;
import de.verdox.solarminer.pcagent.mining.ProxyConfigurationService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Manages RVN/ETC GPU processes through the same SRBMiner installation as Pearl. */
@Service
public class GpuCoinMinerService {
    private static final Duration HASHRATE_GRACE = Duration.ofSeconds(20);
    private final ObjectMapper mapper;
    private final ProxyConfigurationService proxy;
    private final PearlMinerService pearl;
    private final LocalGpuPowerService power;
    private final MinerConsoleService console;
    private final Path configDirectory;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Map<String, Config> configs = new ConcurrentHashMap<>();
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Set<String> manuallyPaused = ConcurrentHashMap.newKeySet();
    private volatile String lastError;

    private static final class Run {
        final String coin;
        final LocalGpuPowerService.Gpu gpu;
        volatile Process process;
        volatile de.verdox.solarminer.pcagent.mining.GpuStratumRelay relay;
        volatile MinerStats.MinerStatus status = MinerStats.MinerStatus.PAUSED;
        volatile boolean healthy;
        volatile double hashrate;
        volatile Long acceptedShares;
        volatile Long rejectedShares;
        volatile MinerShareTelemetry.Counters poolCounters = MinerShareTelemetry.Counters.unavailable();
        volatile String detail = "Noch nicht gestartet";
        volatile String error;
        volatile int apiPort;
        Run(String coin, LocalGpuPowerService.Gpu gpu) { this.coin = coin; this.gpu = gpu; }
        String key() { return coin + ":" + gpu.vendor() + ":" + gpu.index(); }
        String consoleId() { return coin + "-" + gpu.vendor() + "-" + gpu.index(); }
        boolean running() { return process != null && process.isAlive(); }
    }

    public GpuCoinMinerService(ObjectMapper mapper, ProxyConfigurationService proxy, PearlMinerService pearl,
                               LocalGpuPowerService power, MinerConsoleService console,
                               @Value("${solarminer.gpu-coin.config-directory:./solarminer-agent/srbminer}") String directory) {
        this.mapper = mapper; this.proxy = proxy; this.pearl = pearl; this.power = power; this.console = console;
        this.configDirectory = Path.of(directory).toAbsolutePath().normalize();
        for (String coin : List.of("ravencoin", "ethereumclassic", "decred", "quantus")) {
            try {
                Path file = file(coin);
                if (Files.isRegularFile(file)) {
                    Config config = mapper.readValue(file.toFile(), Config.class);
                    validate(coin, config);
                    configs.put(coin, config);
                }
            } catch (Exception e) {
                lastError = coin + ": gespeicherte Konfiguration ungültig: " + e.getMessage();
            }
        }
    }

    public static boolean supported(String coin) { return "ravencoin".equals(coin) || "ethereumclassic".equals(coin) || "decred".equals(coin) || "quantus".equals(coin); }
    public static String algorithm(String coin) {
        return switch (coin) {
            case "ravencoin" -> "kawpow";
            case "ethereumclassic" -> "etchash";
            case "decred" -> "blake3_decred";
            case "quantus" -> "quantus";
            default -> throw new IllegalArgumentException("Unbekannter GPU-Coin");
        };
    }
    private Path file(String coin) { return configDirectory.resolve("solarminer-" + coin + ".json"); }
    public Config configuration(String coin) { return configs.get(coin); }
    public boolean binaryAvailable() { return pearl.binaryAvailable(); }
    public String lastError() { return lastError; }

    public static void validate(String coin, Config config) {
        if (!supported(coin)) throw new IllegalArgumentException("Unbekannter GPU-Coin");
        if (config == null) throw new IllegalArgumentException("Konfiguration fehlt");
        if (config.poolUrl() == null || !config.poolUrl().matches("^stratum\\+(tcp|ssl)://[A-Za-z0-9.-]+:[1-9][0-9]{0,4}$")
                || URI.create(config.poolUrl()).getPort() > 65535)
            throw new IllegalArgumentException("Pool muss stratum+tcp/ssl://host:port sein");
        if (config.proxyUrl() == null || !config.proxyUrl().matches("^stratum\\+tcp://[A-Za-z0-9.-]+:[1-9][0-9]{0,4}$")
                || URI.create(config.proxyUrl()).getPort() > 65535)
            throw new IllegalArgumentException("Proxy muss stratum+tcp://host:port sein");
        boolean validWallet = switch (coin) {
            case "ravencoin" -> validRavencoinAddress(config.wallet());
            case "ethereumclassic" -> config.wallet() != null && config.wallet().matches("^0x[0-9a-fA-F]{40}$");
            case "decred" -> validDecredAddress(config.wallet());
            case "quantus" -> config.wallet() != null && config.wallet().matches("qz[1-9A-HJ-NP-Za-km-z]{38,58}");
            default -> false;
        };
        if (!validWallet)
            throw new IllegalArgumentException("Ungültige " + coin + "-Wallet");
        if (config.worker() == null || !config.worker().matches("^[A-Za-z0-9_-]{1,32}$"))
            throw new IllegalArgumentException("Worker muss 1–32 Zeichen aus Buchstaben, Zahlen, _ oder - enthalten");
        if (config.devices() == null || !config.devices().matches("^((NVIDIA|AMD):[0-9]+)(,((NVIDIA|AMD):[0-9]+))*$"))
            throw new IllegalArgumentException("Mindestens eine GPU im Format NVIDIA:0 oder AMD:0 wählen");
    }

    static boolean validRavencoinAddress(String address) {
        final String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        if (address == null || address.length() < 26 || address.length() > 40) return false;
        BigInteger value = BigInteger.ZERO;
        for (char letter : address.toCharArray()) {
            int digit = alphabet.indexOf(letter);
            if (digit < 0) return false;
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        byte[] bytes = value.toByteArray();
        if (bytes.length > 0 && bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        int zeros = 0;
        while (zeros < address.length() && address.charAt(zeros) == '1') zeros++;
        byte[] decoded = new byte[zeros + bytes.length];
        System.arraycopy(bytes, 0, decoded, zeros, bytes.length);
        if (decoded.length != 25 || (decoded[0] & 0xff) != 60 && (decoded[0] & 0xff) != 122) return false;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] checksum = sha.digest(sha.digest(Arrays.copyOf(decoded, 21)));
            return Arrays.equals(Arrays.copyOfRange(decoded, 21, 25), Arrays.copyOf(checksum, 4));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    static boolean validDecredAddress(String address) {
        final String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        if (address == null || address.length() < 30 || address.length() > 40) return false;
        BigInteger value = BigInteger.ZERO;
        for (char letter : address.toCharArray()) {
            int digit = alphabet.indexOf(letter);
            if (digit < 0) return false;
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        byte[] bytes = value.toByteArray();
        if (bytes.length > 0 && bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        int zeros = 0;
        while (zeros < address.length() && address.charAt(zeros) == '1') zeros++;
        byte[] decoded = new byte[zeros + bytes.length];
        System.arraycopy(bytes, 0, decoded, zeros, bytes.length);
        if (decoded.length != 26 || (decoded[0] & 0xff) != 0x07
                || ((decoded[1] & 0xff) != 0x3f && (decoded[1] & 0xff) != 0x1a)) return false;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] checksum = sha.digest(sha.digest(Arrays.copyOf(decoded, 22)));
            return Arrays.equals(Arrays.copyOfRange(decoded, 22, 26), Arrays.copyOf(checksum, 4));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    public synchronized void configure(String coin, Config config) throws IOException {
        validate(coin, config);
        if (!proxy.matches(config.proxyUrl(), coin)) throw new IllegalArgumentException("SolarMiner-Proxy-Route stimmt nicht");
        if (!stop(coin)) throw new IOException("GPU-Miner konnten nicht angehalten werden");
        Files.createDirectories(configDirectory);
        Path temp = Files.createTempFile(configDirectory, coin + "-", ".json");
        try {
            mapper.writeValue(temp.toFile(), config);
            try { Files.move(temp, file(coin), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file(coin), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
        configs.put(coin, config);
        manuallyPaused.removeIf(key -> key.startsWith(coin + ":"));
        lastError = null;
    }

    public List<LocalGpuPowerService.Gpu> selected(String coin) {
        Config config = configs.get(coin);
        if (config == null) return List.of();
        List<String> devices = List.of(config.devices().split(","));
        return power.discover().stream().filter(gpu -> devices.contains(gpu.vendor() + ":" + gpu.index())).toList();
    }

    public List<LocalGpuPowerService.Gpu> eligible(String coin) {
        return selected(coin).stream().filter(gpu ->
                !manuallyPaused.contains(coin + ":" + gpu.vendor() + ":" + gpu.index())).toList();
    }

    public synchronized boolean start(String coin) {
        if (!supported(coin)) return false;
        manuallyPaused.removeIf(key -> key.startsWith(coin + ":"));
        boolean success = !selected(coin).isEmpty();
        for (LocalGpuPowerService.Gpu gpu : selected(coin)) success = startGpu(coin, gpu.vendor(), gpu.index()) && success;
        if (!success && selected(coin).isEmpty()) lastError = "Keine konfigurierte GPU erkannt";
        return success;
    }

    public synchronized boolean startGpu(String coin, String vendor, int index) {
        if (!supported(coin)) return false;
        LocalGpuPowerService.Gpu gpu = selected(coin).stream()
                .filter(card -> card.vendor().equals(vendor) && card.index() == index).findFirst().orElse(null);
        if (gpu == null) return false;
        String key = coin + ":" + vendor + ":" + index;
        if (manuallyPaused.contains(key)) return false;
        Run run = runs.computeIfAbsent(key, ignored -> new Run(coin, gpu));
        if (run.running()) return true;
        Config config = configs.get(coin);
        if (config == null || !proxy.matches(config.proxyUrl(), coin) || !proxy.miningReady(coin)
                || !proxy.feeReady(coin) || !binaryAvailable())
            return fail(run, "SolarMiner-Proxy, Fee-Ziel oder SRBMiner für " + coin + " nicht bereit");
        if (pearl.runningGpuDeviceIds().contains(gpu.deviceId()) || runningOther(coin, gpu.deviceId()) || hasUnmanagedMiner())
            return fail(run, "SRBMiner läuft bereits für einen anderen Coin oder außerhalb des PC-Agent");
        try {
            int gpuId = pearl.mappedGpuId(gpu);
            int apiPort = 13000 + switch (coin) { case "ravencoin" -> 0; case "ethereumclassic" -> 1000; case "decred" -> 2000; default -> 3000; } + gpuId;
            String suffix = "-" + (vendor.equals("NVIDIA") ? "n" : "a") + index;
            String worker = config.worker().substring(0, Math.min(config.worker().length(), 32 - suffix.length())) + suffix;
            String login = encodedLogin(config, worker);
            if (run.relay != null) run.relay.close();
            var relay = new de.verdox.solarminer.pcagent.mining.GpuStratumRelay(config.proxyUrl(), login, mapper);
            run.relay = relay;
            Config relayed = new Config(config.poolUrl(), relay.localUrl(), config.wallet(), config.worker(), config.devices());
            List<String> command = buildCommand(pearl.executablePath(), coin, relayed, login, apiPort, gpuId);
            Process process = new ProcessBuilder(command).directory(pearl.executablePath().getParent().toFile())
                    .redirectErrorStream(true).start();
            MinerProcessRegistry.registerGpuCoin(process, gpu.deviceId());
            run.process = process; run.apiPort = apiPort; run.status = MinerStats.MinerStatus.MINING;
            run.healthy = false; run.hashrate = 0; run.error = null; run.detail = "Warte auf Pool-Job";
            run.acceptedShares = null; run.rejectedShares = null;
            run.poolCounters = MinerShareTelemetry.Counters.unavailable();
            lastError = null;
            // The aggregate coin console belongs to the whole GPU worker set.
            // Reset it only for the first worker; starting a second GPU must not
            // erase output already emitted by the first one.
            boolean noOtherCoinGpuRunning = runs.values().stream()
                    .noneMatch(candidate -> candidate != run && candidate.coin.equals(coin) && candidate.running());
            if (noOtherCoinGpuRunning) console.started(coin);
            console.started(run.consoleId());
            console.append(coin, "[" + gpu.vendor() + ":" + gpu.index() + "] New miner start");
            Thread.ofVirtual().name(coin + "-output-" + index).start(() -> drain(run, process, config));
            Thread.ofVirtual().name(coin + "-health-" + index).start(() -> monitor(run, process));
            process.onExit().thenAccept(done -> {
                relay.close();
                MinerProcessRegistry.unregisterGpuCoin(done);
                if (run.process == done && run.status == MinerStats.MinerStatus.MINING)
                    fail(run, "SRBMiner beendet (Code " + done.exitValue() + ")");
            });
            return true;
        } catch (IOException e) {
            if (run.relay != null) run.relay.close();
            return fail(run, "SRBMiner-Start fehlgeschlagen: " + e.getMessage());
        }
    }

    public synchronized boolean startForBudget(String coin) {
        boolean success = !eligible(coin).isEmpty();
        for (LocalGpuPowerService.Gpu gpu : eligible(coin))
            success = startGpu(coin, gpu.vendor(), gpu.index()) && success;
        return success;
    }

    private boolean runningOther(String coin, String deviceId) {
        return runs.values().stream().anyMatch(run -> !run.coin.equals(coin) && run.gpu.deviceId().equals(deviceId) && run.running());
    }

    static String encodedLogin(Config config, String worker) {
        String route = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(config.poolUrl().getBytes(StandardCharsets.UTF_8));
        return config.wallet() + ".sm1." + route + "." + worker;
    }

    static List<String> buildCommand(Path executable, String coin, Config config, String login,
                                     int apiPort, int gpuId) {
        URI uri = URI.create(config.proxyUrl());
        List<String> command = new ArrayList<>(List.of(executable.toString(), "--disable-cpu", "--algorithm-gpu", algorithm(coin),
                "--pool", uri.getHost() + ":" + uri.getPort(), "--wallet", login,
                "--tls", "false", "--api-enable", "--api-port", Integer.toString(apiPort),
                "--gpu-id", Integer.toString(gpuId)));
        // Select the proxy's dialect explicitly, including extranonce negotiation.
        if ("ethereumclassic".equals(coin)) command.addAll(List.of("--esm", "2"));
        if ("ravencoin".equals(coin)) command.addAll(List.of("--nicehash", "true"));
        return List.copyOf(command);
    }

    private boolean hasUnmanagedMiner() {
        return pearl.hasExternalMinerProcess();
    }

    private boolean fail(Run run, String error) {
        run.status = MinerStats.MinerStatus.ERROR; run.error = error; run.detail = error;
        run.healthy = false; run.hashrate = 0; lastError = error;
        console.append(run.consoleId(), "[SolarMiner] " + error);
        console.append(run.coin, "[" + run.gpu.vendor() + ":" + run.gpu.index() + "] " + error);
        return false;
    }

    private void drain(Run run, Process process, Config config) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String safe = line.replace(config.wallet(), "[wallet]").replace(config.poolUrl(), "[pool]");
                console.append(run.consoleId(), safe); console.append(run.coin, "[" + run.gpu.index() + "] " + safe);
            }
        } catch (IOException ignored) { }
    }

    private void monitor(Run run, Process process) {
        Instant launched = Instant.now(), lastPoolConnection = launched, lastHashrate = launched;
        Instant lastPositiveHashrate = null;
        boolean hadJob = false, hadHashrate = false;
        while (process.isAlive() && run.process == process) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + run.apiPort + "/"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (run.process != process || !process.isAlive() || run.status != MinerStats.MinerStatus.MINING) return;
                if (response.statusCode() != 200) throw new IOException("Miner-API HTTP " + response.statusCode());
                JsonNode algorithms = mapper.readTree(response.body()).path("algorithms");
                JsonNode info = algorithms.isArray() && !algorithms.isEmpty() ? algorithms.get(0) : mapper.createObjectNode();
                JsonNode pool = info.path("pool");
                double rate = reportedHashrate(info);
                Instant now = Instant.now();
                if (rate > 0) lastPositiveHashrate = now;
                run.hashrate = displayedHashrate(rate, run.hashrate, lastPositiveHashrate, now);
                updateShares(run, pool);
                long age = pool.path("last_job_received").asLong(0);
                boolean poolConnected = response.statusCode() == 200 && pool.path("uptime").asLong(0) > 0;
                boolean recentJob = poolConnected && age > 0 && age < 120;
                // KAWPOW jobs can validly outlive the short job-refresh cadence used
                // by some pools. A worker that is still producing hashes therefore
                // proves that it received a usable job; do not turn an old
                // last_job_received counter into a false pool outage.
                if (poolConnected) lastPoolConnection = Instant.now();
                if (hasUsablePoolJob(poolConnected, recentJob, rate)) hadJob = true;
                run.healthy = poolConnected && rate > 0;
                if (rate > 0) { hadHashrate = true; lastHashrate = now; }
                if (run.healthy) run.detail = "GPU hasht, letzter Pool-Job vor " + age + " s";
                else if (poolConnected) run.detail = "Pool verbunden, warte auf GPU-Hashrate";
                else run.detail = "Warte auf Pool-Job";
            } catch (Exception e) {
                if (run.process != process || !process.isAlive() || run.status != MinerStats.MinerStatus.MINING) return;
                run.hashrate = displayedHashrate(0, run.hashrate, lastPositiveHashrate, Instant.now());
                run.healthy = false; run.detail = "Miner-API nicht erreichbar";
            }
            if (run.status != MinerStats.MinerStatus.MINING) return;
            if ((!hadJob && Duration.between(launched, Instant.now()).toSeconds() >= 90)
                    || (hadJob && Duration.between(lastPoolConnection, Instant.now()).toSeconds() >= 120)
                    || !proxy.miningReady(run.coin) || !proxy.feeReady(run.coin)) {
                fail(run, "Pool-Job oder Fee-Route nicht verfügbar");
                process.destroy(); return;
            }
            if (hadJob && ((!hadHashrate && Duration.between(launched, Instant.now()).toSeconds() >= 180)
                    || (hadHashrate && Duration.between(lastHashrate, Instant.now()).toSeconds() >= 120))) {
                fail(run, "GPU liefert trotz Pool-Jobs keine Hashrate; SRBMiner- und GPU-Treiber prüfen");
                process.destroy(); return;
            }
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    static double reportedHashrate(JsonNode info) {
        JsonNode hashrate = info.path("hashrate");
        for (JsonNode value : List.of(hashrate.path("gpu").path("total"), hashrate.path("1m"), hashrate.path("1min"))) {
            double rate = value.asDouble(0);
            if (Double.isFinite(rate) && rate > 0) return rate;
        }
        return 0;
    }

    static double displayedHashrate(double reported, double previous, Instant lastPositive, Instant now) {
        if (Double.isFinite(reported) && reported > 0) return reported;
        return previous > 0 && lastPositive != null && !now.isAfter(lastPositive.plus(HASHRATE_GRACE)) ? previous : 0;
    }

    static boolean hasUsablePoolJob(boolean poolConnected, boolean recentJob, double hashrate) {
        return poolConnected && (recentJob || hashrate > 0);
    }

    public synchronized boolean stopGpu(String coin, String vendor, int index) {
        Run run = runs.get(coin + ":" + vendor + ":" + index);
        if (run == null) return true;
        run.status = MinerStats.MinerStatus.PAUSED; run.healthy = false; run.hashrate = 0;
        run.acceptedShares = null; run.rejectedShares = null; run.poolCounters = MinerShareTelemetry.Counters.unavailable(); run.detail = "Pausiert";
        Process process = run.process;
        if (run.relay != null) run.relay.close();
        if (process == null || !process.isAlive()) return true;
        console.append(run.consoleId(), "[SolarMiner] Stop: " + MinerStopContext.source());
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return process.waitFor(5, TimeUnit.SECONDS);
            }
            return true;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); return false; }
    }

    public synchronized boolean stop(String coin) {
        boolean success = true;
        for (Run run : new ArrayList<>(runs.values()))
            if (run.coin.equals(coin)) success = stopGpu(coin, run.gpu.vendor(), run.gpu.index()) && success;
        return success;
    }
    public synchronized boolean pause(String coin) {
        for (LocalGpuPowerService.Gpu gpu : selected(coin)) manuallyPaused.add(coin + ":" + gpu.vendor() + ":" + gpu.index());
        return stop(coin);
    }
    public synchronized boolean pauseGpu(String coin, String vendor, int index) {
        manuallyPaused.add(coin + ":" + vendor + ":" + index); return stopGpu(coin, vendor, index);
    }
    public synchronized boolean resumeGpu(String coin, String vendor, int index) {
        manuallyPaused.remove(coin + ":" + vendor + ":" + index); return startGpu(coin, vendor, index);
    }
    public boolean running(String coin) { return runs.values().stream().anyMatch(run -> run.coin.equals(coin) && run.running()); }
    public MinerStats.MinerStatus status(String coin) {
        if (runs.values().stream().anyMatch(run -> run.coin.equals(coin) && run.running()
                && run.status == MinerStats.MinerStatus.MINING)) return MinerStats.MinerStatus.MINING;
        if (runs.values().stream().anyMatch(run -> run.coin.equals(coin) && run.status == MinerStats.MinerStatus.ERROR)) return MinerStats.MinerStatus.ERROR;
        return MinerStats.MinerStatus.PAUSED;
    }
    public List<GpuState> gpuStates(String coin, List<LocalGpuPowerService.Gpu> cards) {
        List<String> selected = configs.containsKey(coin) ? List.of(configs.get(coin).devices().split(",")) : List.of();
        return cards.stream().map(gpu -> {
            String key = coin + ":" + gpu.vendor() + ":" + gpu.index();
            Run run = runs.get(key);
            return new GpuState(gpu.vendor(), gpu.index(), gpu.model(), selected.contains(gpu.vendor() + ":" + gpu.index()),
                    run == null ? MinerStats.MinerStatus.PAUSED : run.status,
                    run != null && run.running(), run != null && run.healthy, manuallyPaused.contains(key),
                    run == null ? "Noch nicht gestartet" : run.detail, run == null ? null : run.error);
        }).toList();
    }
    public List<MinerStats.Worker> workerStats(String coin, List<LocalGpuPowerService.Gpu> cards) {
        Config config = configs.get(coin);
        if (config == null) return List.of();
        List<Pools> pools = List.of(new Pools(config.poolUrl(), config.wallet() + "/" + config.worker(), ""));
        return gpuStates(coin, cards).stream().filter(GpuState::selected).map(state -> {
            LocalGpuPowerService.Gpu gpu = cards.stream().filter(card -> card.vendor().equals(state.vendor()) && card.index() == state.index()).findFirst().orElseThrow();
            Run run = runs.get(coin + ":" + gpu.vendor() + ":" + gpu.index());
            boolean reported = run != null && run.status == MinerStats.MinerStatus.MINING;
            MinerShareTelemetry.Counters counters = run == null ? MinerShareTelemetry.Counters.unavailable() : run.poolCounters;
            return new MinerStats.Worker(state.status(), "SRBMiner " + gpu.model() + " (" + coin + ")", algorithm(coin),
                    reported ? run.hashrate / 1_000_000_000_000.0 : 0, 0.0,
                    power.appliedTarget(gpu), gpu.minWatts(), gpu.maxWatts(), gpu.maxWatts(),
                    gpu.currentWatts() == null ? 0 : Math.round(gpu.currentWatts()), pools, "GPU", gpu.model(), gpu.deviceId(),
                    reported ? run.acceptedShares : null,
                    reported ? run.rejectedShares : null,
                    reported ? new MinerStats.PoolTelemetry(counters.difficulty(), counters.bestShare(), counters.stale(), counters.latencyMs())
                            : MinerStats.PoolTelemetry.unavailable());
        }).toList();
    }
    private static void updateShares(Run run, JsonNode pool) {
        MinerShareTelemetry.Counters counters = MinerShareTelemetry.srbMiner(pool);
        run.acceptedShares = counters.accepted();
        run.rejectedShares = counters.rejected();
        run.poolCounters = counters;
    }
    @PreDestroy public void shutdown() { stop("ravencoin"); stop("ethereumclassic"); stop("decred"); stop("quantus"); }
    public record Config(String poolUrl, String proxyUrl, String wallet, String worker, String devices) { }
    public record GpuState(String vendor, int index, String model, boolean selected, MinerStats.MinerStatus status,
                           boolean running, boolean poolHealthy, boolean manuallyPaused, String connectionDetail, String lastError) { }
}
