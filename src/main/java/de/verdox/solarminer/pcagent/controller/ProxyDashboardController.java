package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.ManagedProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Serves the loopback-only proxy dashboard through the local PC-Agent web UI. */
@RestController
public class ProxyDashboardController {
    private static final String PREFIX = "/proxy-dashboard";
    // The proxy runs as its own process and serves its dashboard from its own web root.
    private static final Map<String, UpstreamPath> ALLOWED_PATHS = Map.of(
            "/", new UpstreamPath("/", MediaType.TEXT_HTML),
            "/styles.css", new UpstreamPath("/styles.css", MediaType.valueOf("text/css")),
            "/app.js", new UpstreamPath("/app.js", MediaType.valueOf("application/javascript")),
            "/api/dashboard", new UpstreamPath("/api/dashboard", MediaType.APPLICATION_JSON),
            "/api/dashboard/console", new UpstreamPath("/api/dashboard/console", MediaType.APPLICATION_JSON));
    private final ManagedProxyService managedProxy;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public ProxyDashboardController(ManagedProxyService managedProxy) { this.managedProxy = managedProxy; }

    @GetMapping({"/proxy-dashboard", "/proxy-dashboard/", "/proxy-dashboard/**"})
    public ResponseEntity<byte[]> dashboard(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length() + PREFIX.length());
        if (requestPath.isEmpty()) return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(PREFIX + "/")).build();
        if (!managedProxy.running()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN).body("The local Stratum proxy is not running.".getBytes());

        String path = requestPath;
        UpstreamPath route = ALLOWED_PATHS.get(path);
        if (route == null) return ResponseEntity.notFound().build();
        String query = safeQuery(request.getQueryString());
        if (query == null) return ResponseEntity.badRequest().build();

        try {
            URI target = URI.create("http://127.0.0.1:8090" + route.path() + (query.isEmpty() ? "" : "?" + query));
            HttpRequest upstream = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<byte[]> response = http.send(upstream, HttpResponse.BodyHandlers.ofByteArray());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(route.contentType());
            headers.setCacheControl("no-store");
            return ResponseEntity.status(response.statusCode()).headers(headers).body(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /** Only pass the dashboard's three display filters; this proxy cannot tunnel arbitrary URLs or APIs. */
    private static String safeQuery(String query) {
        if (query == null || query.isBlank()) return "";
        for (String parameter : query.split("&")) {
            if (!parameter.matches("(?:coin|level|limit)=[A-Za-z0-9_-]{1,32}")) return null;
        }
        return query;
    }

    private record UpstreamPath(String path, MediaType contentType) { }
}
