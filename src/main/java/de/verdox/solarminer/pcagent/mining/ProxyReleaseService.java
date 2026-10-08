package de.verdox.solarminer.pcagent.mining;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the newest published Stratum proxy release on GitHub and downloads its executable JAR.
 * The PC-Agent never ships a proxy build, so a broken proxy can never be frozen into an agent release.
 */
@Service
public class ProxyReleaseService {
    private static final Logger LOGGER = Logger.getLogger(ProxyReleaseService.class.getName());
    private static final Pattern RELEASE_TAG = Pattern.compile("^v(\\d+\\.\\d+\\.\\d+)$");
    private static final Pattern CACHED_JAR = Pattern.compile("^stratum-proxy-(\\d+\\.\\d+\\.\\d+)\\.jar$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final long MAX_JAR_BYTES = 250L * 1024 * 1024;
    private static final long MAX_CHECKSUM_BYTES = 4096;
    /** Newest releases stay on disk so a failed update can still start the last working proxy. */
    private static final int KEEP_RELEASES = 2;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper mapper;
    private final String repository;
    private final Path releaseDirectory;
    private final AtomicBoolean working = new AtomicBoolean();
    private volatile String state = "CHECKING";
    private volatile String detail = "";
    private volatile int progress;
    private volatile String version = "";
    private volatile Path jar;

    public ProxyReleaseService(ObjectMapper mapper,
                               @Value("${solarminer.agent.proxy.repository:Solarminer-app/solarminer-stratum-proxy}") String repository,
                               @Value("${solarminer.agent.proxy.release-dir:./solarminer-agent/proxy-releases}") String releaseDir) {
        this.mapper = mapper;
        this.repository = repository;
        this.releaseDirectory = Path.of(releaseDir).toAbsolutePath().normalize();
        if (repository == null || !repository.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"))
            throw new IllegalStateException("solarminer.agent.proxy.repository must be owner/name");
    }

    public String state() { return state; }
    public String detail() { return detail; }
    public int progress() { return progress; }
    public String version() { return version; }
    public Path jar() { return jar; }
    public Path releaseDirectory() { return releaseDirectory; }
    public String repository() { return repository; }
    public boolean ready() { Path current = jar; return current != null && Files.isRegularFile(current); }
    public boolean working() { return working.get(); }

    /** Starts the GitHub lookup in the background. Returns false when a lookup is already running. */
    public boolean refresh() {
        if (working.compareAndSet(false, true)) {
            state = "CHECKING";
            detail = "";
            progress = 0;
            Thread.ofVirtual().name("stratum-proxy-release-check").start(() -> {
                try {
                    installLatest();
                    state = "READY";
                    progress = 100;
                    detail = "";
                } catch (Exception failure) {
                    state = "FAILED";
                    detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                    LOGGER.log(Level.WARNING, "Stratum proxy release update failed", failure);
                } finally {
                    working.set(false);
                }
            });
            return true;
        }
        return false;
    }

    /**
     * Falls back to the newest JAR already on disk. Used when GitHub is unreachable so the operator
     * keeps a working proxy instead of a permanently blocked dashboard.
     */
    public synchronized boolean useCachedRelease() {
        if (ready()) return true;
        Path cached = newestCachedRelease();
        if (cached == null) return false;
        adopt(cached);
        state = "READY";
        progress = 100;
        detail = "GitHub ist nicht erreichbar - der zuletzt gespeicherte Proxy wird verwendet.";
        return true;
    }

    private void installLatest() throws Exception {
        JsonNode release = latestRelease();
        String tag = release.path("tag_name").asText();
        Matcher tagMatch = RELEASE_TAG.matcher(tag);
        if (!tagMatch.matches()) throw new IOException("Unexpected Stratum proxy release tag: " + tag);
        String releaseVersion = tagMatch.group(1);
        String jarName = "stratum-proxy-" + releaseVersion + ".jar";
        String checksumName = jarName + ".sha256";

        String jarUrl = null;
        String checksumUrl = null;
        for (JsonNode asset : release.path("assets")) {
            String name = asset.path("name").asText();
            String url = asset.path("browser_download_url").asText();
            if (jarName.equals(name)) jarUrl = url;
            else if (checksumName.equals(name)) checksumUrl = url;
        }
        String expectedJarUrl = "https://github.com/" + repository + "/releases/download/" + tag + "/" + jarName;
        String expectedChecksumUrl = expectedJarUrl + ".sha256";
        if (jarUrl == null) throw new IOException("Release " + tag + " has no " + jarName + " asset.");
        if (checksumUrl == null) throw new IOException("Release " + tag + " has no " + checksumName + " asset.");
        if (!expectedJarUrl.equals(jarUrl) || !expectedChecksumUrl.equals(checksumUrl))
            throw new IOException("Stratum proxy release asset URLs are unexpected");

        Files.createDirectories(releaseDirectory);
        Path target = releaseDirectory.resolve(jarName);
        String expectedDigest = readExpectedDigest(URI.create(checksumUrl));
        if (Files.isRegularFile(target) && expectedDigest.equalsIgnoreCase(sha256(target))) {
            adopt(target);
            pruneOlderReleases(releaseVersion);
            return;
        }

        state = "DOWNLOADING";
        progress = 0;
        Path staging = Files.createTempFile(releaseDirectory, "stratum-proxy-", ".part");
        try {
            download(URI.create(jarUrl), staging);
            if (!expectedDigest.equalsIgnoreCase(sha256(staging)))
                throw new IOException("Stratum proxy JAR does not match its published SHA-256");
            try {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException slowMove) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
        adopt(target);
        pruneOlderReleases(releaseVersion);
        LOGGER.info("Stratum proxy " + releaseVersion + " downloaded to " + target);
    }

    private JsonNode latestRelease() throws Exception {
        URI api = URI.create("https://api.github.com/repos/" + repository + "/releases/latest");
        HttpRequest request = HttpRequest.newBuilder(api).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) { }
            throw new IOException("GitHub release API returned HTTP " + response.statusCode());
        }
        JsonNode release;
        try (InputStream stream = response.body()) {
            byte[] json = stream.readNBytes(1024 * 1024 + 1);
            if (json.length > 1024 * 1024) throw new IOException("Stratum proxy release metadata too large");
            release = mapper.readTree(json);
        }
        if (release.path("draft").asBoolean() || release.path("prerelease").asBoolean())
            throw new IOException("Newest Stratum proxy release is not a stable release");
        return release;
    }

    private String readExpectedDigest(URI checksumUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(checksumUrl).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) { }
            throw new IOException("Stratum proxy checksum asset returned HTTP " + response.statusCode());
        }
        String body;
        try (InputStream stream = response.body()) {
            byte[] raw = stream.readNBytes((int) MAX_CHECKSUM_BYTES + 1);
            if (raw.length > MAX_CHECKSUM_BYTES) throw new IOException("Stratum proxy checksum asset too large");
            body = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        }
        String digest = body.strip().split("\\s+", 2)[0];
        if (!SHA256.matcher(digest).matches()) throw new IOException("Stratum proxy checksum asset is malformed");
        return digest.toLowerCase(java.util.Locale.ROOT);
    }

    private void download(URI uri, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) { }
            throw new IOException("Stratum proxy download returned HTTP " + response.statusCode());
        }
        long declaredSize = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (InputStream input = new DigestInputStream(response.body(), sha);
             var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > MAX_JAR_BYTES || (declaredSize > 0 && count > declaredSize))
                    throw new IOException("Stratum proxy download exceeds its published size");
                output.write(buffer, 0, read);
                progress = declaredSize > 0 ? (int) Math.min(99, count * 99 / declaredSize) : 0;
            }
        }
        if (count == 0) throw new IOException("Stratum proxy download was empty");
        if (declaredSize > 0 && count != declaredSize) throw new IOException("Stratum proxy download was truncated");
    }

    private Path newestCachedRelease() {
        if (!Files.isDirectory(releaseDirectory)) return null;
        try (var files = Files.list(releaseDirectory)) {
            List<Path> candidates = new ArrayList<>();
            for (Path file : files.toList()) {
                Matcher matcher = CACHED_JAR.matcher(file.getFileName().toString());
                if (matcher.matches() && Files.isRegularFile(file)) candidates.add(file);
            }
            return candidates.stream().max(Comparator.comparing(ProxyReleaseService::versionOf)).orElse(null);
        } catch (IOException failure) {
            LOGGER.log(Level.WARNING, "Could not list cached Stratum proxy releases", failure);
            return null;
        }
    }

    private void pruneOlderReleases(String keepVersion) {
        if (!Files.isDirectory(releaseDirectory)) return;
        try (var files = Files.list(releaseDirectory)) {
            List<Path> jars = new ArrayList<>();
            for (Path file : files.toList()) {
                Matcher matcher = CACHED_JAR.matcher(file.getFileName().toString());
                if (matcher.matches() && !matcher.group(1).equals(keepVersion)) jars.add(file);
            }
            jars.sort(Comparator.comparing(ProxyReleaseService::versionOf).reversed());
            for (int index = KEEP_RELEASES - 1; index < jars.size(); index++) {
                Path stale = jars.get(index);
                Files.deleteIfExists(stale);
                Files.deleteIfExists(releaseDirectory.resolve(stale.getFileName() + ".sha256"));
            }
        } catch (IOException failure) {
            LOGGER.log(Level.WARNING, "Could not prune old Stratum proxy releases", failure);
        }
    }

    private synchronized void adopt(Path release) {
        jar = release;
        Matcher matcher = CACHED_JAR.matcher(release.getFileName().toString());
        version = matcher.matches() ? matcher.group(1) : "";
    }

    private static String versionOf(Path file) {
        Matcher matcher = CACHED_JAR.matcher(file.getFileName().toString());
        return matcher.matches() ? matcher.group(1) : "0.0.0";
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), sha)) {
            byte[] buffer = new byte[64 * 1024];
            while (input.read(buffer) != -1) { }
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}
