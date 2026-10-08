package de.verdox.solarminer.pcagent.mining;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystemException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsAntivirusBlockTest {
    @Test
    void identifiesDefenderFileAccessFailureWithoutMisclassifyingOrdinaryIo() {
        IOException blocked = new IOException("extract failed", new FileSystemException(
                "srbminer.zip", null, "Der Vorgang konnte nicht erfolgreich abgeschlossen werden, da die Datei einen Virus oder möglicherweise unerwünschte Software enthält"));
        assertTrue(WindowsAntivirusBlock.causedBy(blocked, true));
        assertFalse(WindowsAntivirusBlock.causedBy(blocked, false));
        assertFalse(WindowsAntivirusBlock.causedBy(
                new FileSystemException("virus.zip", null, "Zugriff verweigert"), true));
        assertFalse(WindowsAntivirusBlock.causedBy(new IOException("virus"), true));
    }
}
