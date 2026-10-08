package de.verdox.solarminer.pcagent.lowlevel.sensor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Downloads the signed-official upstream release archive after validating GitHub's asset SHA-256. */
@Service
public class WindowsLhmBootstrapService {
    private static final Logger LOGGER = Logger.getLogger(WindowsLhmBootstrapService.class.getName());
    private static final URI RELEASE_API = URI.create("https://api.github.com/repos/LibreHardwareMonitor/LibreHardwareMonitor/releases/latest");
    private static final long MAX_ARCHIVE_BYTES = 160L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 450L * 1024 * 1024;

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Path directory;
    private final boolean enabled;
    private final AtomicBoolean starting = new AtomicBoolean();
    private volatile String authorizationHeader;
    private volatile URI dataUri = URI.create("http://127.0.0.1:8085/data.json");
    private volatile String status = "not-started";
    private volatile String detail = "";
    private volatile long lastHealthCheckNanos;

    public WindowsLhmBootstrapService(ObjectMapper mapper,
            @Value("${solarminer.agent.telemetry.windows-lhm.directory:${user.home}/.solarminer/telemetry/librehardwaremonitor}") String directory,
            @Value("${solarminer.agent.telemetry.windows-lhm.enabled:${solarminer.agent.telemetry.windows-lhm.autostart:true}}") boolean enabled) {
        this.mapper = mapper;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.enabled = enabled;
    }

    public boolean retryStart() {
        return beginStart(false);
    }

    public boolean restartWithElevation() {
        return beginStart(true);
    }

    private boolean beginStart(boolean restart) {
        if (!isWindows() || !enabled || !starting.compareAndSet(false, true)) return false;
        status = "starting";
        detail = restart
                ? "LibreHardwareMonitor wird nach Windows-UAC-Freigabe neu gestartet."
                : "LibreHardwareMonitor wird mit Windows-UAC-Rechten gestartet.";
        Thread thread = new Thread(() -> ensureStarted(restart), "solarminer-lhm-bootstrap");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public String status() {
        if ("available".equals(status)
                && System.nanoTime() - lastHealthCheckNanos > java.util.concurrent.TimeUnit.SECONDS.toNanos(2)) {
            lastHealthCheckNanos = System.nanoTime();
            if (!probeJsonApi()) {
                status = "stopped";
                detail = "LibreHardwareMonitor is no longer responding. Restart it to continue.";
            }
        }
        return status;
    }
    public boolean readyForAgent() { return !isWindows() || "available".equals(status()); }
    public String detail() {
        return detail;
    }
    public URI dataUri() { return dataUri; }
    public String authorizationHeader() { return authorizationHeader; }

    private void ensureStarted(boolean restart) {
        try {
            if (!restart && probeJsonApi()) {
                status = "available";
                lastHealthCheckNanos = System.nanoTime();
                detail = "LibreHardwareMonitor JSON API is responding on 127.0.0.1:8085.";
                return;
            }
            ProcessHandle managedProcess = findManagedProcess();
            if (!restart && (managedProcess != null || isAlreadyRunning())) {
                status = "api-disabled";
                detail = "LibreHardwareMonitor is already running without a reachable local JSON API. Close it and restart the PC-Agent.";
                return;
            }
            Files.createDirectories(directory);
            Path executable = directory.resolve("LibreHardwareMonitor.exe");
            if (!Files.isRegularFile(executable)) installLatest(executable);
            configureServer(executable.resolveSibling("LibreHardwareMonitor.config"));
            startElevated(executable, restart);
            status = "starting";
            detail = "LibreHardwareMonitor was launched after Windows elevation approval.";
            for (int attempt = 0; attempt < 60; attempt++) {
                if (probeJsonApi()) {
                    status = "available";
                    lastHealthCheckNanos = System.nanoTime();
                    detail = "LibreHardwareMonitor JSON API is responding on loopback.";
                    return;
                }
                Thread.sleep(1000);
            }
            status = "api-unavailable";
            detail = "LibreHardwareMonitor was launched, but its local JSON API did not become reachable. Check its window and the Windows elevation prompt.";
        } catch (Exception e) {
            status = "failed";
            detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOGGER.log(Level.WARNING, "Could not bootstrap LibreHardwareMonitor", e);
        } finally {
            starting.set(false);
        }
    }

    private boolean probeJsonApi() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(dataUri).timeout(Duration.ofSeconds(2)).GET();
            if (authorizationHeader != null) builder.header("Authorization", authorizationHeader);
            HttpRequest request = builder.build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().length() < 8_000_000
                    && mapper.readTree(response.body()).has("Children");
        } catch (Exception ignored) { return false; }
    }

    private void configureServer(Path configFile) throws Exception {
        String bindIp = "127.0.0.1";
        authorizationHeader = null;
        dataUri = URI.create("http://" + bindIp + ":8085/data.json");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document;
        if (Files.isRegularFile(configFile)) {
            document = factory.newDocumentBuilder().parse(configFile.toFile());
        } else {
            document = factory.newDocumentBuilder().newDocument();
            document.appendChild(document.createElement("configuration"));
        }
        Element root = document.getDocumentElement();
        NodeList sections = root.getElementsByTagName("appSettings");
        Element settings;
        if (sections.getLength() > 0) settings = (Element) sections.item(0);
        else {
            settings = document.createElement("appSettings");
            root.appendChild(settings);
        }
        setSetting(document, settings, "runWebServerMenuItem", "true");
        setSetting(document, settings, "listenerIp", bindIp);
        setSetting(document, settings, "listenerPort", "8085");
        setSetting(document, settings, "authenticationEnabled", "false");
        setSetting(document, settings, "minTrayMenuItem", "true");
        setSetting(document, settings, "startMinMenuItem", "true");
        // Storage/SMART probing is not needed for mining telemetry. In particular, it
        // can cause Windows storage-management helpers to be invoked on some hosts.
        // Disable it at the source instead of merely hiding its returned sensor nodes.
        setSetting(document, settings, "/storage/enabled", "false");

        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(document), new StreamResult(configFile.toFile()));
    }

    private static void setSetting(Document document, Element settings, String key, String value) {
        NodeList entries = settings.getElementsByTagName("add");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            if (key.equals(entry.getAttribute("key"))) {
                entry.setAttribute("value", value);
                return;
            }
        }
        Element entry = document.createElement("add");
        entry.setAttribute("key", key);
        entry.setAttribute("value", value);
        settings.appendChild(entry);
    }

    private static void startElevated(Path executable, boolean restart) throws IOException, InterruptedException {
        String path = executable.toAbsolutePath().normalize().toString().replace("'", "''");
        String workingDirectory = executable.getParent().toAbsolutePath().normalize().toString().replace("'", "''");
        String stopExisting = restart
                ? "$target = '" + path + "'; Get-CimInstance Win32_Process -Filter \"Name='LibreHardwareMonitor.exe'\" "
                    + "| Where-Object { $_.ExecutablePath -ieq $target } "
                    + "| ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; "
                    + "Start-Sleep -Seconds 1; "
                : "";
        String command = stopExisting + "Start-Process -FilePath '" + path + "' -WorkingDirectory '"
                + workingDirectory + "' -Verb RunAs -ErrorAction Stop";
        Process launcher = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-Command", command).redirectErrorStream(true).start();
        if (!launcher.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
            launcher.destroyForcibly();
            throw new IOException("Waiting for Windows elevation approval timed out");
        }
        String output;
        try (InputStream stream = launcher.getInputStream()) {
            output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        }
        if (launcher.exitValue() != 0) {
            throw new IOException(output.isBlank()
                    ? "Windows elevation was declined or LibreHardwareMonitor could not be launched"
                    : "Windows elevation failed: " + output);
        }
    }

    private boolean isAlreadyRunning() {
        return ProcessHandle.allProcesses().anyMatch(handle -> handle.info().command()
                .map(command -> {
                    try { return Path.of(command).getFileName().toString().equalsIgnoreCase("LibreHardwareMonitor.exe"); }
                    catch (Exception ignored) { return false; }
                }).orElse(false));
    }

    private ProcessHandle findManagedProcess() {
        return ProcessHandle.allProcesses().filter(handle -> handle.info().command().map(command -> {
            try {
                Path executable = Path.of(command).toAbsolutePath().normalize();
                return executable.startsWith(directory) && executable.getFileName().toString().equalsIgnoreCase("LibreHardwareMonitor.exe");
            } catch (Exception ignored) { return false; }
        }).orElse(false)).findFirst().orElse(null);
    }

    private void installLatest(Path executable) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(RELEASE_API).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "SolarMiner-PC-Agent").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        JsonNode release;
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("LibreHardwareMonitor release API returned HTTP " + response.statusCode());
            byte[] bytes = body.readNBytes(1_000_001);
            if (bytes.length > 1_000_000) throw new IOException("LibreHardwareMonitor release metadata too large");
            release = mapper.readTree(bytes);
        }
        if (release.path("draft").asBoolean() || release.path("prerelease").asBoolean())
            throw new IOException("Latest LibreHardwareMonitor release is not stable");
        String tag = release.path("tag_name").asText();
        if (!tag.matches("v?\\d+\\.\\d+\\.\\d+")) throw new IOException("Unexpected LibreHardwareMonitor release tag");
        JsonNode selected = null;
        for (JsonNode asset : release.path("assets")) {
            String name = asset.path("name").asText();
            if (name.matches("(?i)LibreHardwareMonitor.*\\.zip")) { selected = asset; break; }
        }
        if (selected == null) throw new IOException("No LibreHardwareMonitor Windows ZIP asset in the latest release");
        String name = selected.path("name").asText();
        String url = selected.path("browser_download_url").asText();
        String digest = selected.path("digest").asText();
        long size = selected.path("size").asLong();
        String expectedPrefix = "https://github.com/LibreHardwareMonitor/LibreHardwareMonitor/releases/download/" + tag + "/";
        if (!url.startsWith(expectedPrefix) || !url.endsWith("/" + name)
                || !digest.matches("sha256:[0-9a-fA-F]{64}") || size < 1 || size > MAX_ARCHIVE_BYTES)
            throw new IOException("LibreHardwareMonitor release asset metadata has no valid trusted SHA-256");

        Path archive = Files.createTempFile(directory, "lhm-", ".zip");
        Path staging = Files.createTempDirectory(directory, "lhm-stage-");
        try {
            download(url, archive, digest.substring(7), size);
            extract(archive, staging);
            Path source;
            try (var paths = Files.walk(staging)) {
                source = paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equalsIgnoreCase("LibreHardwareMonitor.exe"))
                        .findFirst().orElseThrow(() -> new IOException("LibreHardwareMonitor executable is missing from its release ZIP"));
            }
            Path packageRoot = source.getParent();
            try (var paths = Files.walk(packageRoot)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    Path target = directory.resolve(packageRoot.relativize(file)).normalize();
                    if (!target.startsWith(directory)) throw new IOException("Unsafe LibreHardwareMonitor package path");
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!Files.isRegularFile(executable)) throw new IOException("LibreHardwareMonitor installation did not produce its executable");
        } finally {
            Files.deleteIfExists(archive);
            try (var paths = Files.walk(staging)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
        LOGGER.info("Installed verified LibreHardwareMonitor release " + tag + " at " + executable);
    }

    private void download(String url, Path archive, String expectedHash, long expectedSize) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) { }
            throw new IOException("LibreHardwareMonitor download returned HTTP " + response.statusCode());
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (InputStream input = new DigestInputStream(response.body(), sha); var output = Files.newOutputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > MAX_ARCHIVE_BYTES || count > expectedSize) throw new IOException("LibreHardwareMonitor ZIP exceeds its declared size");
                output.write(buffer, 0, read);
            }
        }
        if (count != expectedSize || !HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(expectedHash))
            throw new IOException("LibreHardwareMonitor ZIP SHA-256 or size mismatch");
    }

    private void extract(Path archive, Path staging) throws IOException {
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path relative = Path.of(entry.getName().replace('\\', '/')).normalize();
                if (relative.isAbsolute() || relative.startsWith("..") || entry.getName().contains(":"))
                    throw new IOException("Unsafe LibreHardwareMonitor ZIP entry");
                Path output = staging.resolve(relative).normalize();
                if (!output.startsWith(staging)) throw new IOException("Unsafe LibreHardwareMonitor ZIP entry");
                if (entry.isDirectory()) Files.createDirectories(output);
                else {
                    Files.createDirectories(output.getParent());
                    try (var target = Files.newOutputStream(output)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            expanded += read;
                            if (expanded > MAX_EXPANDED_BYTES) throw new IOException("LibreHardwareMonitor ZIP expands beyond the safety limit");
                            target.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
}
