package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.AgentControlSettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * Keeps the local dashboard usable while preventing every LAN/API write when the operator has
 * disabled Node control. Node requests have no browser Origin header; dashboard fetch requests
 * must carry an Origin matching the agent that served the page.
 */
@Component
public class AgentWriteAccessFilter extends OncePerRequestFilter {
    private static final String AGENT_API = "/api/agent/";
    private static final String LOCAL_API = AGENT_API + "local";
    private static final String EXTERNAL_API = AGENT_API + "external";

    private final AgentControlSettingsService controls;

    public AgentWriteAccessFilter(AgentControlSettingsService controls) {
        this.controls = controls;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(AGENT_API);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals(EXTERNAL_API) || path.startsWith(EXTERNAL_API + "/")) {
            // Discovery is read-only and must remain available when the operator disables
            // remote control. Keep every other external endpoint behind the global gate.
            if ("GET".equalsIgnoreCase(request.getMethod())
                    && path.equals(EXTERNAL_API + "/identity")) {
                filterChain.doFilter(request, response);
                return;
            }
            if (controls.get().externalControlEnabled()) {
                filterChain.doFilter(request, response);
                return;
            }
            forbidden(response, "Externe Node-Steuerung wurde lokal deaktiviert");
            return;
        }
        if ((path.equals(LOCAL_API) || path.startsWith(LOCAL_API + "/"))
                && isSameOriginDashboardRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        forbidden(response, "Lokale Agent-API ist ausschließlich über das PC-Agent-Dashboard verfügbar");
    }

    static boolean isSameOriginDashboardRequest(HttpServletRequest request) {
        String value = request.getHeader(HttpHeaders.ORIGIN);
        boolean originHeader = value != null;
        if (value == null) value = request.getHeader(HttpHeaders.REFERER);
        if (value == null || value.isBlank() || "null".equals(value)) return false;
        try {
            URI origin = URI.create(value);
            if (origin.getHost() == null || origin.getRawUserInfo() != null
                    || originHeader && origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                return false;
            return origin.getScheme().equalsIgnoreCase(request.getScheme())
                    && origin.getHost().equalsIgnoreCase(request.getServerName())
                    && effectivePort(origin) == request.getServerPort();
        } catch (IllegalArgumentException invalidOrigin) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static void forbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
