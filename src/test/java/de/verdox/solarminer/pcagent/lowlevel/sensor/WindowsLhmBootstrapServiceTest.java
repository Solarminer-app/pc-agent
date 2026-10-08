package de.verdox.solarminer.pcagent.lowlevel.sensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WindowsLhmBootstrapServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void configuresLibreHardwareMonitorWithoutStorageDiscovery() throws Exception {
        WindowsLhmBootstrapService service = new WindowsLhmBootstrapService(
                new ObjectMapper(), tempDirectory.toString(), true);
        Path config = tempDirectory.resolve("LibreHardwareMonitor.config");

        Method configureServer = WindowsLhmBootstrapService.class
                .getDeclaredMethod("configureServer", Path.class);
        configureServer.setAccessible(true);
        configureServer.invoke(service, config);

        var settings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(config.toFile()).getElementsByTagName("add");
        Element setting = null;
        for (int index = 0; index < settings.getLength(); index++) {
            Element candidate = (Element) settings.item(index);
            if ("/storage/enabled".equals(candidate.getAttribute("key"))) {
                setting = candidate;
                break;
            }
        }
        assertNotNull(setting);
        assertEquals("/storage/enabled", setting.getAttribute("key"));
        assertEquals("false", setting.getAttribute("value"));
    }
}
