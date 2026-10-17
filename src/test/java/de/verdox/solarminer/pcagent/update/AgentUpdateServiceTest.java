package de.verdox.solarminer.pcagent.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentUpdateServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void betaSelectionIgnoresStableDraftAndOtherPrereleases() throws Exception {
        var releases = json.readTree("""
                [
                  {"tag_name":"pc-agent-v1.2.0","prerelease":false,"published_at":"2026-10-20T12:00:00Z"},
                  {"tag_name":"pc-agent-beta-aaaaaaaaaaaa","prerelease":true,"published_at":"2026-10-18T12:00:00Z"},
                  {"tag_name":"pc-agent-beta-bbbbbbbbbbbb","prerelease":true,"draft":true,"published_at":"2026-10-21T12:00:00Z"},
                  {"tag_name":"other-beta-cccccccccccc","prerelease":true,"published_at":"2026-10-22T12:00:00Z"},
                  {"tag_name":"pc-agent-beta-dddddddddddd","prerelease":true,"published_at":"2026-10-19T12:00:00Z"}
                ]
                """);
        assertEquals("pc-agent-beta-dddddddddddd", AgentUpdateService.selectBetaRelease(releases).path("tag_name").asText());
    }

    @Test
    void betaSelectionFailsClosedWithoutMatchingPrerelease() throws Exception {
        var releases = json.readTree("[{\"tag_name\":\"pc-agent-v1.2.0\",\"prerelease\":false}]");
        assertThrows(IllegalStateException.class, () -> AgentUpdateService.selectBetaRelease(releases));
    }
}
