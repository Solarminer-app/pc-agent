package de.verdox.solarminer.pcagent.miner.engine;

import de.verdox.solarminer.pcagent.coin.DownloadState;
import de.verdox.solarminer.pcagent.miner.engine.WindowsAntivirusBlock;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Shared installation lifecycle for downloadable miner binaries (refactoring Phase 2).
 * Both former installers ({@code XmrDownloadService}, {@code SrbDownloadService}) carried
 * the same state block, retry loop, size-capped SHA-256 download, archive extraction and
 * atomic binary placement; this base keeps that mechanic once.
 *
 * <p>Contract notes: {@link #state()}, {@link #status()}, {@link #detail()} and
 * {@link #progress()} feed the REST wire fields {@code downloadStatus},
 * {@code downloadDetail} and {@code downloadProgress}; {@link DownloadState#wireName()}
 * values must never change. The extraction wall is SRBMiner's stricter one (path
 * traversal, drive letters, symlinks and an extracted-bytes cap), so both miners share
 * the safer implementation.</p>
 *
 * <p>Coin/release specifics stay in the adapters via the hooks {@link #binaryAvailable()},
 * {@link #install()} (release query + install layout), {@link #removeInstalled()},
 * {@link #installerName()} and {@link #downloadThreadName()}.</p>
 */
public abstract class MinerBinaryInstaller {
    /** Hard cap for a downloaded release archive (both installers used 300 MiB). */
    protected static final long MAX_ARCHIVE_BYTES = 300L * 1024 * 1024;
    /** Hard cap for the fully expanded archive (SRBMiner's stricter 1 GiB limit). */
    protected static final long MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024;

    private final Logger logger = Logger.getLogger(getClass().getName());
    private final AtomicBoolean downloading = new AtomicBoolean();
    private volatile DownloadState status = DownloadState.PENDING;
    private volatile String detail = "";
    private volatile int progress;

    /** Typed lifecycle state; {@link #status()} carries the same value as the REST wire name. */
    public DownloadState state() { return status; }
    /** REST wire field {@code downloadStatus} — the {@link DownloadState} wire name. */
    public String status() { return status.wireName(); }
    /** REST wire field {@code downloadDetail} — English source text resolved by the UI catalog. */
    public String detail() { return detail; }
    /** REST wire field {@code downloadProgress} — 0..100. */
    public int progress() { return progress; }

    public abstract Path installDirectory();

    /** True when a usable binary is already installed; {@link #retry()} then short-circuits. */
    protected abstract boolean binaryAvailable();

    /** Release query + install layout; the per-miner heart of the installation. */
    protected abstract void install() throws Exception;

    /** Deletes the installed binary (and its tracked files); returns the success detail text. */
    protected abstract String removeInstalled() throws IOException;

    /** Detail text shown when {@link #removeInstalled()} fails. */
    protected abstract String removeFailureDetail(IOException failure);

    /** Human-readable installer name used in log lines (e.g. "XMRig", "SRBMiner-MULTI"). */
    protected abstract String installerName();

    /** Virtual-thread name for the background install (e.g. "xmrig-download"). */
    protected abstract String downloadThreadName();

    protected final void setProgress(int value) { progress = value; }

    /** Starts the installation unless one is already running; the UI's download trigger. */
    public final boolean retry() {
        if (binaryAvailable()) {
            status = DownloadState.READY;
            detail = "";
            progress = 100;
            return true;
        }
        if (!downloading.compareAndSet(false, true)) return false;
        status = DownloadState.DOWNLOADING;
        detail = "";
        progress = 0;
        Thread.ofVirtual().name(downloadThreadName()).start(() -> {
            try {
                install();
                status = DownloadState.READY;
                detail = "";
                progress = 100;
            } catch (Exception e) {
                status = WindowsAntivirusBlock.causedBy(e) ? DownloadState.BLOCKED_BY_ANTIVIRUS
                        : e instanceof UnsupportedOperationException ? DownloadState.UNSUPPORTED : DownloadState.FAILED;
                detail = status == DownloadState.BLOCKED_BY_ANTIVIRUS ? WindowsAntivirusBlock.DETAIL
                        : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                logger.log(Level.WARNING, installerName() + " installation failed", e);
            } finally {
                downloading.set(false);
            }
        });
        return true;
    }

    /** Removes the installed binary; both callers check {@link #state()} before calling. */
    public final boolean remove() {
        if (!downloading.compareAndSet(false, true)) return false;
        try {
            detail = removeInstalled();
            status = DownloadState.PENDING;
            progress = 0;
            return true;
        } catch (IOException e) {
            status = DownloadState.FAILED;
            detail = removeFailureDetail(e);
            return false;
        } finally {
            downloading.set(false);
        }
    }

    /**
     * Streams {@code source} into {@code archive} while hashing it, aborts on any size
     * overrun, maps the byte count onto the progress bar (5% .. {@code progressCeiling})
     * and finally verifies the exact size and the SHA-256 digest.
     */
    protected final void downloadAndVerify(InputStream source, Path archive, String expectedDigestHex,
                                           long expectedSize, int progressCeiling,
                                           String overSizeMessage, String mismatchMessage) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (InputStream input = new DigestInputStream(source, sha);
             var output = Files.newOutputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > MAX_ARCHIVE_BYTES || count > expectedSize) throw new IOException(overSizeMessage);
                output.write(buffer, 0, read);
                progress = 5 + (int) Math.min(progressCeiling, count * progressCeiling / expectedSize);
            }
        }
        if (count != expectedSize || !HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(expectedDigestHex))
            throw new IOException(mismatchMessage);
    }

    /**
     * Extracts a {@code .zip} (Windows) or {@code .tar.gz} archive into {@code staging}
     * behind SRBMiner's safety wall: no absolute or {@code ..} paths, no drive letters,
     * no links of any kind, and a hard cap on the expanded size.
     */
    protected final void extractArchive(Path archive, Path staging, boolean windows, String label) throws IOException {
        long total = 0;
        if (windows) {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) total = copyEntry(zip, staging, entry.getName(), total, label);
                    zip.closeEntry();
                }
            }
        } else {
            try (TarArchiveInputStream tar = new TarArchiveInputStream(
                    new GZIPInputStream(Files.newInputStream(archive)))) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if (entry.isFile()) total = copyEntry(tar, staging, entry.getName(), total, label);
                    else if (entry.isSymbolicLink() || entry.isLink())
                        throw new IOException(label + " archive contains a link");
                }
            }
        }
    }

    private long copyEntry(InputStream input, Path staging, String name, long total, String label) throws IOException {
        Path relative = Path.of(name.replace('\\', '/')).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || name.contains(":"))
            throw new IOException("Unsafe " + label + " archive entry: " + name);
        Path output = staging.resolve(relative).normalize();
        if (!output.startsWith(staging)) throw new IOException("Unsafe " + label + " archive entry");
        Files.createDirectories(output.getParent());
        try (var target = Files.newOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_EXTRACTED_BYTES) throw new IOException(label + " archive expands beyond limit");
                target.write(buffer, 0, read);
            }
        }
        return total;
    }

    /**
     * Copies {@code source} to a sibling temp file, makes it executable on non-Windows
     * systems and moves it atomically (plain replace move as fallback) onto {@code target}.
     */
    protected final void installBinary(Path source, Path target, boolean windows,
                                       String cannotMakeExecutableMessage, String emptyBinaryMessage) throws IOException {
        Files.createDirectories(target.getParent());
        Path pending = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".part");
        try {
            if (Files.size(source) == 0) throw new IOException(emptyBinaryMessage);
            Files.copy(source, pending, StandardCopyOption.REPLACE_EXISTING);
            if (!windows && !pending.toFile().setExecutable(true, false))
                throw new IOException(cannotMakeExecutableMessage);
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    /** Removes a staging directory tree (used by both install layouts in their finally blocks). */
    protected static void deleteTree(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
