package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the downloaded Stratum proxy release as a separate JVM next to the agent. The agent never
 * bundles a proxy build, so every start uses the currently published proxy and a broken proxy can
 * not be frozen into an agent release. The child is terminated with the agent, and a proxy left
 * behind by a killed agent is terminated before a new one starts.
 */
@Service
public class ManagedProxyService {
    private static final Logger LOGGER = Logger.getLogger(ManagedProxyService.class.getName());
    private static final long START_PROBE_DELAY_MS = 4_000;
    private static final long RESTART_BACKOFF_MS = 20_000;

    private final ProxyReleaseService releases;
    private final HttpClient probeClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final int apiPort;
    private final int bitcoinPort;
    private final int moneroPort;
    private final int pearlPort;
    private final int ravenPort;
    private final int etcPort;
    private final int decredPort;
    private final int quantusPort;
    private volatile boolean standalone;
    /** Fee-target roll mode handed to the proxy child: "random" (stateless) or "stateful" (urn). */
    private volatile String rollMode = "random";
    private volatile Process process;
    private volatile String status = "external";
    private volatile String detail = "";
    private volatile long lastStartAttempt;
    private volatile boolean gateOpen;
    private volatile boolean deferredExternalMode;

    @Autowired
    public ManagedProxyService(
            ProxyReleaseService releases,
            @Value("${solarminer.agent.proxy.api-port:8090}") int apiPort,
            @Value("${solarminer.agent.proxy.bitcoin-port:3333}") int bitcoinPort,
            @Value("${solarminer.agent.proxy.monero-port:3335}") int moneroPort,
            @Value("${solarminer.agent.proxy.pearl-port:3334}") int pearlPort,
            @Value("${solarminer.agent.proxy.ravencoin-port:3336}") int ravenPort,
            @Value("${solarminer.agent.proxy.ethereumclassic-port:3337}") int etcPort,
            @Value("${solarminer.agent.proxy.decred-port:3338}") int decredPort,
            @Value("${solarminer.agent.proxy.quantus-port:3339}") int quantusPort,
            @Value("${solarminer.agent.fee-roll-mode:random}") String rollMode,
            @Value("${solarminer.agent.standalone:false}") boolean standalone) {
        this.releases = releases;
        this.apiPort = apiPort;
        this.bitcoinPort = bitcoinPort;
        this.moneroPort = moneroPort;
        this.pearlPort = pearlPort;
        this.ravenPort = ravenPort;
        this.etcPort = etcPort;
        this.decredPort = decredPort;
        this.quantusPort = quantusPort;
        this.rollMode = "stateful".equalsIgnoreCase(rollMode) ? "stateful" : "random";
        this.standalone = standalone;
        if (standalone) status = "starting";
        // Covers every JVM exit path, including the tray "Exit" item that calls System.exit.
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopProcess, "stratum-proxy-shutdown"));
    }

    /** Kept for unit-level proxy configuration fixtures: no release lookup, no child process, no boot gate. */
    public ManagedProxyService(boolean standalone, String ignoredProxyJar) {
        this(new ProxyReleaseService(new ObjectMapper(), "Solarminer-app/solarminer-stratum-proxy",
                        Path.of(System.getProperty("java.io.tmpdir"), "solarminer-proxy-fixture").toString()),
                8090, 3333, 3335, 3334, 3336, 3337, 3338, 3339, "random", standalone);
        this.gateOpen = true;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAtBoot() {
        terminateOrphanedProxyProcesses();
        releases.refresh();
    }

    @Scheduled(fixedDelay = 5_000)
    public void keepRunning() { maintain(); }

    private synchronized void maintain() {
        if (gateOpen && !standalone) return;
        Process current = process;
        if (current != null) {
            if (current.isAlive()) {
                if (status.equals("starting")) probeReadiness(current);
                return;
            }
            process = null;
            int exitCode = current.exitValue();
            status = "failed";
            detail = "Der lokale Proxy wurde beendet (Exit-Code " + exitCode + "). Protokoll: " + logFile();
            LOGGER.warning("Stratum proxy process exited with code " + exitCode);
        }
        if (!releases.ready()) {
            if (releases.state().equals("FAILED") && releases.useCachedRelease()) return;
            if (!releases.working()) releases.refresh();
            return;
        }
        startIfNeeded();
    }

    private void startIfNeeded() {
        if (process != null || !releases.ready()) return;
        long now = System.currentTimeMillis();
        if (lastStartAttempt != 0 && now - lastStartAttempt < RESTART_BACKOFF_MS) return;
        lastStartAttempt = now;
        Path release = releases.jar();
        try {
            Process started = new ProcessBuilder(commandFor(release))
                    .directory(release.getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()))
                    .start();
            process = started;
            status = "starting";
            detail = "";
            LOGGER.info("Started Stratum proxy " + releases.version() + " as process " + started.pid());
        } catch (Exception failure) {
            status = "failed";
            detail = "Der lokale Proxy konnte nicht gestartet werden: " + failure.getMessage();
            LOGGER.log(Level.WARNING, detail, failure);
        }
    }

    private void probeReadiness(Process current) {
        if (System.currentTimeMillis() - lastStartAttempt < START_PROBE_DELAY_MS) return;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + "/api/network/ip"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = probeClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().isBlank()) return;
        } catch (Exception unreachable) {
            if (unreachable instanceof InterruptedException) Thread.currentThread().interrupt();
            if (System.currentTimeMillis() - lastStartAttempt > Duration.ofSeconds(90).toMillis()) {
                status = "failed";
                detail = "Der lokale Proxy hat sich nicht gemeldet. Protokoll: " + logFile();
                stopProcess();
            }
            return;
        }
        status = "running";
        detail = "";
        gateOpen = true;
        if (deferredExternalMode) {
            deferredExternalMode = false;
            standalone = false;
            stopProcess();
            status = "external";
        }
    }

    private List<String> commandFor(Path release) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(release.toString());
        command.add("--server.address=127.0.0.1");
        command.add("--server.port=" + apiPort);
        command.add("--proxy.bind-address=127.0.0.1");
        command.add("--proxy.fee.required=true");
        command.add("--proxy.fee.roll-mode=" + rollMode);
        command.add("--proxy.coins.bitcoin.port=" + bitcoinPort);
        command.add("--proxy.coins.monero.port=" + moneroPort);
        command.add("--proxy.coins.pearl.port=" + pearlPort);
        command.add("--proxy.coins.ravencoin.port=" + ravenPort);
        command.add("--proxy.coins.ethereumclassic.port=" + etcPort);
        command.add("--proxy.coins.decred.port=" + decredPort);
        command.add("--proxy.coins.quantus.port=" + quantusPort);
        command.add("--proxy.discovery.enabled=false");
        command.add("--proxy.pearl.enabled=true");
        return command;
    }

    private Path javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.isExecutable(candidate) ? candidate : Path.of(executable);
    }

    private Path logFile() { return releases.releaseDirectory().resolve("proxy.log"); }

    /** A previous agent may have been killed without closing its proxy; that proxy would hold our ports. */
    private void terminateOrphanedProxyProcesses() {
        long self = ProcessHandle.current().pid();
        String marker = releases.releaseDirectory().toString();
        List<ProcessHandle> orphans = ProcessHandle.allProcesses()
                .filter(handle -> handle.pid() != self && handle.isAlive())
                .filter(handle -> handle.info().commandLine()
                        .map(line -> line.contains(marker) && line.contains("-jar") && line.contains(".jar"))
                        .orElse(false))
                .toList();
        if (orphans.isEmpty()) return;
        LOGGER.warning("Terminating " + orphans.size()
                + " Stratum proxy process(es) left over by a previous PC-Agent run.");
        for (ProcessHandle orphan : orphans) {
            orphan.descendants().forEach(ProcessHandle::destroy);
            orphan.destroy();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        for (ProcessHandle orphan : orphans) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try { TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            if (orphan.isAlive()) orphan.destroyForcibly();
        }
    }

    /** Switches the managed proxy at runtime. The caller must pause miners before changing mode. */
    public synchronized boolean setStandalone(boolean enabled) {
        if (!enabled) {
            standalone = false;
            if (!gateOpen) {
                // The boot gate requires a downloaded proxy to have started first, so a saved
                // external mode is applied as soon as that proxy is confirmed running.
                deferredExternalMode = true;
                return true;
            }
            stopProcess();
            status = "external";
            detail = "";
            return true;
        }
        standalone = true;
        deferredExternalMode = false;
        lastStartAttempt = 0;
        status = "starting";
        detail = "";
        startIfNeeded();
        return running();
    }

    /** Re-runs the GitHub lookup and, if a JAR is already available, restarts the proxy immediately. */
    public synchronized boolean retry() {
        if (running()) return true;
        lastStartAttempt = 0;
        if (!releases.ready()) releases.refresh();
        startIfNeeded();
        return true;
    }

    /** Switches the fee-target roll mode of the managed proxy at runtime, restarting the child when needed. */
    public synchronized boolean setRollMode(String mode) {
        String normalized = "stateful".equalsIgnoreCase(mode) ? "stateful" : "random";
        if (normalized.equals(rollMode)) return true;
        rollMode = normalized;
        if (running()) stopProcess();
        lastStartAttempt = 0;
        startIfNeeded();
        return true;
    }

    public String rollMode() { return rollMode; }

    public boolean standalone() { return standalone; }
    public boolean running() { Process current = process; return current != null && current.isAlive(); }
    public String status() { return running() ? "running" : status; }
    public String detail() { return detail; }
    public String version() { return releases.version(); }
    public boolean gateOpen() { return gateOpen; }

    public synchronized ProxyGate gate() {
        Process current = process;
        if (current != null && !current.isAlive()) {
            process = null;
            status = "failed";
            detail = "Der lokale Proxy wurde beendet (Exit-Code " + current.exitValue() + "). Protokoll: " + logFile();
        }
        if (standalone && gateOpen && !running()) gateOpen = false;

        // The dashboard gate is also polled during boot. Let that request kick off a cached
        // release instead of relying exclusively on the scheduled maintenance task.
        if (!gateOpen && releases.state().equals("FAILED") && releases.useCachedRelease()) {
            status = "starting";
            detail = "";
        }
        if (!gateOpen && releases.ready()) startIfNeeded();

        if (running()) {
            return gateOpen
                    ? new ProxyGate(true, "running", 100, releases.version(), "")
                    : new ProxyGate(false, "starting", 100, releases.version(), detail);
        }
        if (!gateOpen) {
            String releaseState = releases.state();
            if (releaseState.equals("CHECKING"))
                return new ProxyGate(false, "checking", 0, releases.version(), detail);
            if (releaseState.equals("DOWNLOADING"))
                return new ProxyGate(false, "downloading", releases.progress(), releases.version(), detail);
            String failure = detail.isBlank() ? releases.detail() : detail;
            if (failure.isBlank()) failure = "Der lokale Proxy-Prozess wurde noch nicht gestartet.";
            return new ProxyGate(false, "failed", 0, releases.version(), failure);
        }
        return new ProxyGate(true, "running", 100, releases.version(), detail);
    }

    public record ProxyGate(boolean ready, String state, int percent, String version, String detail) { }

    @PreDestroy
    public synchronized void stop() {
        standalone = false;
        stopProcess();
    }

    private void stopProcess() {
        Process current = process;
        process = null;
        if (current == null || !current.isAlive()) return;
        current.descendants().forEach(ProcessHandle::destroy);
        current.destroy();
        try {
            if (!current.waitFor(5, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        current.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
}
