package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.AgentControlSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWriteAccessFilterTest {
    private final AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
    private final AgentWriteAccessFilter filter = new AgentWriteAccessFilter(controls);

    @Test
    void disabledNodeControlAllowsDiscoveryIdentityButRejectsOtherExternalReadsAndWrites() throws Exception {
        disabled();
        MockHttpServletRequest identity = request("GET", "/api/agent/external/identity");
        MockHttpServletResponse identityResponse = new MockHttpServletResponse();
        MockFilterChain identityChain = new MockFilterChain();
        filter.doFilter(identity, identityResponse, identityChain);
        assertThat(identityResponse.getStatus()).isEqualTo(200);
        assertThat(identityChain.getRequest()).isSameAs(identity);

        for (RequestCase requestCase : List.of(
                new RequestCase("GET", "/api/agent/external/status"),
                new RequestCase("POST", "/api/agent/external/identity"),
                new RequestCase("POST", "/api/agent/external/pause"),
                new RequestCase("POST", "/api/agent/external/resume"),
                new RequestCase("POST", "/api/agent/external/power-target"),
                new RequestCase("POST", "/api/agent/external/proxy"),
                new RequestCase("POST", "/api/agent/external/pearl/configuration"))) {
            MockHttpServletRequest request = request(requestCase.method(), requestCase.path());
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).as(requestCase.path()).isEqualTo(403);
            assertThat(chain.getRequest()).as(requestCase.path()).isNull();
        }
    }

    @Test
    void localNamespaceRequiresSameOriginDashboardForReadsAndWrites() throws Exception {
        disabled();
        for (String method : List.of("GET", "POST")) {
            MockHttpServletRequest rejected = request(method, "/api/agent/local/overview");
            MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
            MockFilterChain rejectedChain = new MockFilterChain();
            filter.doFilter(rejected, rejectedResponse, rejectedChain);
            assertThat(rejectedResponse.getStatus()).isEqualTo(403);
            assertThat(rejectedChain.getRequest()).isNull();

            MockHttpServletRequest allowed = request(method, "/api/agent/local/overview");
            allowed.addHeader(method.equals("GET") ? "Referer" : "Origin",
                    method.equals("GET") ? "http://agent.local:8084/mining.html" : "http://agent.local:8084");
            MockFilterChain allowedChain = new MockFilterChain();
            filter.doFilter(allowed, new MockHttpServletResponse(), allowedChain);
            assertThat(allowedChain.getRequest()).isSameAs(allowed);
        }
    }

    @Test
    void enabledNodeControlAllowsOnlyExternalNamespaceWithoutBrowserHeaders() throws Exception {
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, true, Map.of(), Map.of()));
        MockHttpServletRequest external = request("POST", "/api/agent/external/pause");
        MockFilterChain externalChain = new MockFilterChain();
        filter.doFilter(external, new MockHttpServletResponse(), externalChain);
        assertThat(externalChain.getRequest()).isSameAs(external);

        MockHttpServletRequest local = request("POST", "/api/agent/local/pause");
        MockHttpServletResponse localResponse = new MockHttpServletResponse();
        MockFilterChain localChain = new MockFilterChain();
        filter.doFilter(local, localResponse, localChain);
        assertThat(localResponse.getStatus()).isEqualTo(403);
        assertThat(localChain.getRequest()).isNull();
    }

    @Test
    void legacySharedNamespaceIsNeverAccepted() throws Exception {
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, true, Map.of(), Map.of()));
        MockHttpServletRequest request = request("POST", "/api/agent/pause");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    private void disabled() {
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false, Map.of(), Map.of()));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setScheme("http");
        request.setServerName("agent.local");
        request.setServerPort(8084);
        return request;
    }

    private record RequestCase(String method, String path) { }
}
