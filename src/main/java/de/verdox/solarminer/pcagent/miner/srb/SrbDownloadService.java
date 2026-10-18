package de.verdox.solarminer.pcagent.miner.srb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.miner.engine.MinerBinaryInstaller;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.support.AtomicFileStore;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Installs the official, checksum-verified SRBMiner release when the binary is absent. */
@Service
public class SrbDownloadService extends MinerBinaryInstaller {
    /**
     * 3.7.3 is the first stable release whose PearlHash defaults select the new kernels for
     * NVIDIA SM86, SM89 and SM120 while retaining SRBMiner's standard kernels for the other
     * supported NVIDIA generations. Keep both operating systems on the same reviewed CLI:
     * the former Linux "latest" lookup could silently remove parameters used by the agent.
     */
    public static final String SRBMINER_VERSION = "3.7.3";
    private static final URI RELEASE_API = URI.create(
            "https://api.github.com/repos/doktor83/SRBMiner-Multi/releases/tags/" + SRBMINER_VERSION);
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper mapper;
    private final PearlMinerService miner;

    public SrbDownloadService(ObjectMapper mapper, PearlMinerService miner) {
        this.mapper = mapper;
        this.miner = miner;
    }

    @Override public Path installDirectory() { return miner.executablePath().getParent().toAbsolutePath().normalize(); }

    @Override protected String installerName() { return "SRBMiner-MULTI"; }
    @Override protected String downloadThreadName() { return "srbminer-download"; }
    @Override protected boolean binaryAvailable() { return miner.binaryAvailable(); }

    @Override
    protected String removeInstalled() throws IOException {
        Path executable = miner.executablePath().toAbsolutePath().normalize();
        Path target = executable.getParent();
        Path manifest = target.resolve(".solarminer-srbminer-files.json");
        if (Files.isRegularFile(manifest)) {
            List<String> files = mapper.readValue(manifest.toFile(), new TypeReference<>() { });
            Path realTarget = target.toRealPath();
            for (String relative : files) {
                Path file = target.resolve(relative).normalize();
                if (!file.startsWith(target) || file.equals(miner.configurationPath())) continue;
                Path parent = file.getParent();
                if (Files.exists(parent) && !parent.toRealPath().startsWith(realTarget))
                    throw new IOException("Tracked package path escapes the SRBMiner directory");
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(executable);
        Files.deleteIfExists(PearlMinerService.versionPath(executable));
        Files.deleteIfExists(manifest);
        return "SRBMiner entfernt; die Pearl-Konfiguration bleibt gespeichert.";
    }

    @Override
    protected String removeFailureDetail(IOException failure) {
        return "SRBMiner konnte nicht vollständig entfernt werden: " + failure.getMessage();
    }

    @Override
    protected void install() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64"))
            throw new UnsupportedOperationException("SRBMiner-MULTI supports only x64 on this agent");
        boolean windows = os.contains("win");
        if (!windows && !os.contains("linux"))
            throw new UnsupportedOperationException("No official SRBMiner package for this operating system");

        HttpRequest releaseRequest = HttpRequest.newBuilder(RELEASE_API).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
        HttpResponse<InputStream> releaseResponse = http.send(releaseRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (releaseResponse.statusCode() != 200) {
            try (InputStream ignored = releaseResponse.body()) { }
            throw new IOException("GitHub release API returned HTTP " + releaseResponse.statusCode());
        }
        JsonNode release;
        setProgress(5);
        try (InputStream stream = releaseResponse.body()) {
            byte[] json = stream.readNBytes(1024 * 1024 + 1);
            if (json.length > 1024 * 1024) throw new IOException("Release metadata too large");
            release = mapper.readTree(json);
        }
        if (release.path("prerelease").asBoolean() || release.path("draft").asBoolean())
            throw new IOException("SRBMiner release is not stable");
        String tag = release.path("tag_name").asText();
        if (!tag.equals(SRBMINER_VERSION))
            throw new IOException("Unexpected SRBMiner release tag");
        String name = "SRBMiner-Multi-" + tag.replace('.', '-') + (windows ? "-win64.zip" : "-Linux.tar.gz");
        JsonNode asset = null;
        for (JsonNode candidate : release.path("assets"))
            if (name.equals(candidate.path("name").asText())) { asset = candidate; break; }
        if (asset == null) throw new IOException("No SRBMiner asset for " + name);
        String digest = asset.path("digest").asText();
        long size = asset.path("size").asLong();
        String url = asset.path("browser_download_url").asText();
        if (!digest.matches("sha256:[0-9a-fA-F]{64}") || size < 1 || size > MAX_ARCHIVE_BYTES
                || !url.equals("https://github.com/doktor83/SRBMiner-Multi/releases/download/" + tag + "/" + name))
            throw new IOException("SRBMiner release asset metadata is invalid");

        Path executable = miner.executablePath();
        Path target = executable.getParent();
        Files.createDirectories(target);
        Path archive = Files.createTempFile(target, "srbminer-", windows ? ".zip" : ".tar.gz");
        Path staging = Files.createTempDirectory(target, "srbminer-stage-");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) { }
                throw new IOException("SRBMiner download returned HTTP " + response.statusCode());
            }
            try (InputStream input = response.body()) {
                downloadAndVerify(input, archive, digest.substring(7), size, 79,
                        "SRBMiner archive exceeds expected size", "SRBMiner archive SHA-256 or size mismatch");
            }
            setProgress(85);
            extractArchive(archive, staging, windows, "SRBMiner");
            setProgress(95);
            Path binary;
            try (var files = Files.walk(staging)) {
                binary = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(executable.getFileName().toString()))
                        .findFirst().orElseThrow(() -> new IOException("SRBMiner executable missing from archive"));
            }
            Path packageRoot = binary.getParent();
            List<String> installedFiles = new ArrayList<>();
            try (var files = Files.walk(packageRoot)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path destination = target.resolve(packageRoot.relativize(file)).normalize();
                    if (!destination.startsWith(target)) throw new IOException("Invalid package path");
                    if (destination.equals(executable) || destination.getFileName().toString().equals("solarminer-config.json"))
                        continue;
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    installedFiles.add(target.relativize(destination).toString());
                }
            }
            if (!miner.binaryAvailable())
                installBinary(binary, executable, windows,
                        "Could not make SRBMiner executable", "SRBMiner executable is empty");
            installedFiles.add(target.relativize(executable).toString());
            Path version = PearlMinerService.versionPath(executable);
            if (!AtomicFileStore.writeText(version, SRBMINER_VERSION + System.lineSeparator(), "srbminer-version-"))
                throw new IOException("Could not write the SRBMiner version marker");
            installedFiles.add(target.relativize(version).toString());
            Path manifest = target.resolve(".solarminer-srbminer-files.json");
            if (!AtomicFileStore.writeJson(manifest, installedFiles, mapper, "srbminer-manifest-"))
                throw new IOException("Could not write the SRBMiner file manifest");
            java.util.logging.Logger.getLogger(SrbDownloadService.class.getName())
                    .info("SRBMiner-MULTI " + tag + " installed at " + executable);
        } finally {
            Files.deleteIfExists(archive);
            deleteTree(staging);
        }
    }
}
