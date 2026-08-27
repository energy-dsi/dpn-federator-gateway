// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

/**
 * Authenticating reverse proxy placed in front of the JobRunr dashboard (Job Runner UI).
 * <p>
 * Listens on the publicly exposed dashboard port. Every request must carry a Keycloak bearer
 * token (see {@link JobRunnerAuthProperties}), typically attached upstream by an oauth2-proxy
 * instance sitting in front of this service; the gateway verifies the token itself via
 * {@link BearerTokenVerifier} so that access is also enforced against clients that manage to
 * reach this port directly, bypassing any upstream proxy. Requests that pass verification are
 * forwarded to the JobRunr dashboard, which is bound to an internal-only port.
 */
@Slf4j
public class JobRunnerAuthGateway {

    private static final Set<String> HOP_BY_HOP_REQUEST_HEADERS = Set.of(
            "connection", "content-length", "host", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private static final Set<String> HOP_BY_HOP_RESPONSE_HEADERS =
            Set.of("connection", "content-length", "keep-alive", "transfer-encoding");

    private final int publicPort;
    private final JobRunnerAuthProperties config;
    private final BearerTokenVerifier verifier;
    private final URI upstreamBaseUri;

    private HttpServer httpServer;
    private ExecutorService executorService;
    private HttpClient httpClient;

    public JobRunnerAuthGateway(int publicPort, JobRunnerAuthProperties config, BearerTokenVerifier verifier) {
        this.publicPort = publicPort;
        this.config = config;
        this.verifier = verifier;
        this.upstreamBaseUri = URI.create("http://127.0.0.1:" + config.getInternalPort());
    }

    public void start() {
        try {
            executorService = Executors.newCachedThreadPool();
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

            httpServer = HttpServer.create(new InetSocketAddress(publicPort), 0);
            httpServer.setExecutor(executorService);
            httpServer.createContext("/", this::handle);
            httpServer.start();

            log.info(
                    "Job Runner auth gateway listening on port {} (proxying authenticated requests to internal port {})",
                    publicPort,
                    config.getInternalPort());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start Job Runner auth gateway on port " + publicPort, e);
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        log.info("Job Runner auth gateway stopped");
    }

    private static final Set<String> READ_ONLY_HTTP_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private void handle(HttpExchange exchange) {
        try {
            AuthResult authResult = authenticate(exchange);
            if (!authResult.authorized()) {
                log.debug("Rejected Job Runner UI request from {}: {}", exchange.getRemoteAddress(), authResult.reason());
                sendUnauthorized(exchange, authResult.reason());
                return;
            }

            AccessLevel requiredLevel =
                    READ_ONLY_HTTP_METHODS.contains(exchange.getRequestMethod().toUpperCase(Locale.ROOT))
                            ? AccessLevel.READER
                            : AccessLevel.ADMIN;
            if (authResult.accessLevel().ordinal() < requiredLevel.ordinal()) {
                log.debug(
                        "Forbidding Job Runner UI {} {} for subject={} (level={}, required={})",
                        exchange.getRequestMethod(),
                        exchange.getRequestURI(),
                        authResult.subject(),
                        authResult.accessLevel(),
                        requiredLevel);
                sendForbidden(exchange, requiredLevel);
                return;
            }

            proxy(exchange);
        } catch (Exception e) {
            log.error("Job Runner auth gateway failed to handle request", e);
            sendError(exchange, 502, "Bad Gateway");
        } finally {
            exchange.close();
        }
    }

    private AuthResult authenticate(HttpExchange exchange) {
        String headerValue = firstHeader(exchange.getRequestHeaders(), config.getHeaderName());
        String token = extractToken(headerValue);
        return verifier.verify(token);
    }

    private String extractToken(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String scheme = config.getHeaderScheme();
        if (scheme != null && !scheme.isBlank()) {
            String prefix = scheme + " ";
            if (headerValue.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return headerValue.substring(prefix.length()).trim();
            }
            return null;
        }
        return headerValue.trim();
    }

    private void proxy(HttpExchange exchange) throws IOException, InterruptedException {
        URI targetUri = upstreamBaseUri.resolve(exchange.getRequestURI());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(targetUri).timeout(Duration.ofSeconds(30));
        copyRequestHeaders(exchange, requestBuilder);

        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        HttpRequest.BodyPublisher bodyPublisher =
                requestBody.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(requestBody);
        requestBuilder.method(exchange.getRequestMethod(), bodyPublisher);

        HttpResponse<InputStream> upstreamResponse =
                httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

        copyResponseHeaders(exchange, upstreamResponse);
        exchange.sendResponseHeaders(upstreamResponse.statusCode(), 0);
        try (InputStream upstreamBody = upstreamResponse.body();
                OutputStream clientOut = exchange.getResponseBody()) {
            upstreamBody.transferTo(clientOut);
        }
    }

    private void copyRequestHeaders(HttpExchange exchange, HttpRequest.Builder builder) {
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (HOP_BY_HOP_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException e) {
                    log.debug("Skipping restricted header '{}' while proxying to Job Runner dashboard", name);
                }
            }
        });
    }

    private void copyResponseHeaders(HttpExchange exchange, HttpResponse<InputStream> upstreamResponse) {
        Map<String, List<String>> headers = upstreamResponse.headers().map();
        headers.forEach((name, values) -> {
            if (HOP_BY_HOP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                exchange.getResponseHeaders().add(name, value);
            }
        });
    }

    private String firstHeader(com.sun.net.httpserver.Headers headers, String name) {
        List<String> values = headers.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private void sendUnauthorized(HttpExchange exchange, String reason) {
        exchange.getResponseHeaders().add("WWW-Authenticate", config.getHeaderScheme());
        sendError(exchange, 401, "Unauthorized: " + reason);
    }

    private void sendForbidden(HttpExchange exchange, AccessLevel requiredLevel) {
        sendError(exchange, 403, "Forbidden: this action requires " + requiredLevel + " access");
    }

    private void sendError(HttpExchange exchange, int status, String message) {
        try {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException e) {
            log.debug("Failed to send error response: {}", e.getMessage());
        }
    }
}
