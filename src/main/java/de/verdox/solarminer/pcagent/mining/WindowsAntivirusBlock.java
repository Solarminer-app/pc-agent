package de.verdox.solarminer.pcagent.mining;

import java.nio.file.FileSystemException;
import java.util.Locale;

/** Recognizes Windows' localized antivirus/PUA file access failure without hiding other I/O errors. */
public final class WindowsAntivirusBlock {
    private WindowsAntivirusBlock() { }

    public static boolean causedBy(Throwable failure) {
        return causedBy(failure, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    static boolean causedBy(Throwable failure, boolean windows) {
        if (!windows) return false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof FileSystemException)) continue;
            String message = ((FileSystemException) cause).getReason();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("virus") || lower.contains("potentially unwanted")
                    || lower.contains("unerwünscht") || lower.contains("unerw├╝nscht")
                    || lower.contains("0x800700e1")) return true;
        }
        return false;
    }

    public static final String DETAIL = "Windows-Sicherheit hat eine Miner-Datei blockiert. "
            + "Öffne Windows-Sicherheit > Viren- & Bedrohungsschutz > Schutzverlauf und prüfe den Fund. "
            + "Gib nur eine verifizierte Datei frei und starte danach den Download erneut.";
}
