package de.verdox.solarminer.pcagent.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

@Service
public class AgentUpdateService {
    private static final String REPOSITORY = "https://api.github.com/repos/Solarminer-app/pc-agent/releases";
    private static final String JAR = "solarminer-pc-agent-standalone.jar";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json;
    private final ApplicationContext context;
    private final Path install = Path.of(System.getProperty("user.dir"));
    private final boolean docker = "docker".equals(System.getenv("SOLARMINER_AGENT_DEPLOYMENT"));
    private final boolean supervised = "1".equals(System.getenv("SOLARMINER_AGENT_UPDATE_SUPERVISED"));
    private final String channel = "beta".equals(System.getenv("SOLARMINER_AGENT_CHANNEL")) ? "beta" : "stable";
    private final String currentTag = System.getenv().getOrDefault("SOLARMINER_AGENT_RELEASE_TAG", "unknown");
    private volatile String availableTag = "";
    private volatile String phase = "idle";
    private volatile int percent = -1;
    private volatile String error = "";
    private volatile boolean busy;

    public AgentUpdateService(ObjectMapper json, ApplicationContext context) {
        this.json = json;
        this.context = context;
        if (Files.exists(install.resolve(".update/last-error"))) {
            phase = "failed";
            error = "update_start_failed";
        }
    }

    public record Status(String deployment, String channel, String currentTag, String availableTag,
                         boolean updateAvailable, boolean automatic, String phase, int percent,
                         String error, String dockerGpu) { }

    public Status status() {
        return new Status(docker ? "docker" : "native", channel, currentTag, availableTag,
                !availableTag.isBlank() && !availableTag.equals(currentTag),
                !docker && supervised, phase, percent, error,
                System.getenv().getOrDefault("SOLARMINER_AGENT_GPU", "none"));
    }

    public synchronized void check() {
        if (busy) return;
        busy = true;
        boolean keepStartError = "update_start_failed".equals(error);
        if (!keepStartError) { phase = "checking"; error = ""; }
        CompletableFuture.runAsync(() -> {
            try {
                JsonNode release = release();
                availableTag = release.path("tag_name").asText("");
                if (!keepStartError) phase = "ready";
            } catch (Exception ex) {
                if (!keepStartError) { error = "update_check_failed"; phase = "failed"; }
            } finally { busy = false; }
        });
    }

    public synchronized boolean start() {
        // AgentWriteAccessFilter requires a same-origin dashboard request, including for LAN clients.
        if (docker || !supervised || busy || availableTag.isBlank() || availableTag.equals(currentTag)) return false;
        busy = true;
        error = "";
        try { Files.deleteIfExists(install.resolve(".update/last-error")); } catch (Exception ignored) { }
        phase = "downloading";
        percent = 0;
        CompletableFuture.runAsync(() -> {
            Path dir = install.resolve(".update");
            Path temporary = dir.resolve("pending.jar.download");
            try {
                JsonNode candidate = release();
                String tag = candidate.path("tag_name").asText();
                if (!tag.equals(availableTag)) throw new IllegalStateException("release changed");
                String hashUrl = asset(candidate, JAR + ".sha256");
                String jarUrl = asset(candidate, JAR);
                String hashFile = http.send(request(hashUrl), HttpResponse.BodyHandlers.ofString()).body().trim();
                if (!hashFile.matches("(?i)^[0-9a-f]{64}\\s+\\*?" + JAR.replace(".", "\\.") + "$"))
                    throw new IllegalStateException("invalid checksum file");
                String expected = hashFile.substring(0, 64).toLowerCase();
                Files.createDirectories(dir);
                HttpResponse<InputStream> response = http.send(request(jarUrl), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) throw new IllegalStateException("download failed");
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                long bytes = 0;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = response.body(); var out = Files.newOutputStream(temporary)) {
                    byte[] chunk = new byte[64 * 1024];
                    for (int count; (count = in.read(chunk)) != -1; ) {
                        bytes += count;
                        if (bytes > 300L * 1024 * 1024) throw new IllegalStateException("download too large");
                        out.write(chunk, 0, count);
                        digest.update(chunk, 0, count);
                        percent = total > 0 ? (int) Math.min(99, bytes * 100 / total) : -1;
                    }
                }
                phase = "verifying";
                if (!HexFormat.of().formatHex(digest.digest()).equals(expected))
                    throw new IllegalStateException("checksum mismatch");
                Files.move(temporary, dir.resolve("pending.jar"), StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(dir.resolve("request.tmp"), tag + "\n" + expected + "\n");
                Files.move(dir.resolve("request.tmp"), dir.resolve("request"), StandardCopyOption.REPLACE_EXISTING);
                phase = "restarting";
                percent = 100;
                Thread.sleep(1500);
                int exit = SpringApplication.exit(context, () -> 42);
                System.exit(exit);
            } catch (Exception ex) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
                error = "update_download_failed";
                phase = "failed";
                busy = false;
            }
        });
        return true;
    }

    private JsonNode release() throws Exception {
        if (channel.equals("stable")) {
            JsonNode stable = json.readTree(http.send(request(REPOSITORY + "/latest"), HttpResponse.BodyHandlers.ofString()).body());
            if (stable.path("draft").asBoolean() || stable.path("prerelease").asBoolean()
                    || !stable.path("tag_name").asText().matches("pc-agent-v[0-9].*"))
                throw new IllegalStateException("invalid stable release");
            return stable;
        }
        JsonNode releases = json.readTree(http.send(request(REPOSITORY + "?per_page=100"), HttpResponse.BodyHandlers.ofString()).body());
        if (!releases.isArray()) throw new IllegalStateException("invalid beta list");
        return selectBetaRelease(releases);
    }

    static JsonNode selectBetaRelease(JsonNode releases) {
        return java.util.stream.StreamSupport.stream(releases.spliterator(), false)
                .filter(r -> !r.path("draft").asBoolean() && r.path("prerelease").asBoolean()
                        && r.path("tag_name").asText().matches("pc-agent-beta-[0-9a-f]{12}"))
                .max(Comparator.comparing(r -> r.path("published_at").asText()))
                .orElseThrow(() -> new IllegalStateException("no beta release"));
    }

    private String asset(JsonNode release, String name) {
        for (JsonNode candidate : release.path("assets")) {
            if (name.equals(candidate.path("name").asText())) {
                String url = candidate.path("browser_download_url").asText();
                if (url.startsWith("https://github.com/Solarminer-app/pc-agent/releases/download/")) return url;
            }
        }
        throw new IllegalStateException("missing release asset");
    }

    private HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "SolarMiner-PC-Agent-Updater").GET().build();
    }
}
