package de.verdox.solarminer.pcagent.miner;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.miner.engine.AbstractMinerEngine;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MeasurementStartAuthorityTest {
    @TempDir Path directory;
    private static class Engine extends AbstractMinerEngine {
        boolean allowed(String id) { return workerStartAllowed(id); }
        protected MinerConsoleService console() { return null; }
        protected ObjectMapper mapper() { return null; }
        protected java.util.logging.Logger logger() { return null; }
    }

    @Test void measurementStartsPausedDeviceWithoutChangingDurableIntentOrAuthorizingOtherThreads() throws Exception {
        var controls = new AgentControlSettingsService(new ObjectMapper(), directory.resolve("settings.json").toString());
        controls.setWorkersManuallyStopped(java.util.Set.of("a", "b"), true);
        var engine = new Engine();
        engine.setWorkerStartPolicy(controls);
        var permit = controls.measurementStartPermit("a");
        assertFalse(engine.allowed("a"));
        assertThrows(IllegalStateException.class, () -> MinerStartContext.measurement("a", permit, () -> {
            assertTrue(engine.allowed("a"));
            assertFalse(engine.allowed("b"));
            assertTrue(MinerStartContext.mayRetryFailure());
            assertFalse(MinerStartContext.operatorRequested());
            assertTrue(controls.get().manuallyStopped("a"));
            var otherThreadAllowed = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread other = Thread.ofVirtual().start(() -> otherThreadAllowed.set(engine.allowed("a")));
            try { other.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(otherThreadAllowed.get());
            throw new IllegalStateException("test cleanup");
        }));
        assertFalse(engine.allowed("a"));
        assertFalse(MinerStartContext.mayRetryFailure());
        assertFalse(new AgentControlSettingsService(new ObjectMapper(), directory.resolve("settings.json").toString()).mayStart("a"));
    }

    @Test void repeatedStopRevokesExistingPermitEvenWhenPersistenceFails() throws Exception {
        Path parent = directory.resolve("blocked");
        java.nio.file.Files.writeString(parent, "not a directory");
        var controls = new AgentControlSettingsService(new ObjectMapper(), parent.resolve("settings.json").toString());
        controls.setWorkerManuallyStopped("a", true);
        var permit = controls.measurementStartPermit("a");
        var engine = new Engine(); engine.setWorkerStartPolicy(controls);
        MinerStartContext.measurement("a", permit, () -> {
            assertTrue(engine.allowed("a"));
            assertFalse(controls.setWorkerManuallyStopped("b", true));
            assertTrue(engine.allowed("a"));
            assertFalse(controls.setWorkerManuallyStopped("a", true));
            assertFalse(engine.allowed("a"));
            return null;
        });
    }
}
