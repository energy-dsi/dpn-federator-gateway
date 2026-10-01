// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.common.utils.SSLUtils;

/**
 * Minimal HTTPS reverse proxy that terminates TLS in front of JobRunr's embedded dashboard.
 * <p>
 * JobRunr's dashboard server (built on {@code com.sun.net.httpserver.HttpServer}) has no TLS
 * support in its public configuration API. This listens on a public HTTPS port using a
 * self-signed PKCS12 keystore and forwards every request to the plain-HTTP dashboard running on
 * a separate port, streaming the response body back rather than buffering it so the
 * dashboard's SSE-based live updates keep working.
 * <p>
 * SECURITY: the upstream plain-HTTP port is bound by JobRunr on 0.0.0.0 (its OSS config offers no
 * bind-address option). This proxy's TLS is only meaningful if that plaintext port is unreachable
 * from outside the host - enforce that at the network layer (firewall / do not expose the port /
 * Kubernetes NetworkPolicy). See {@link DefaultJobSchedulerProvider}.
 */
@Slf4j
public final class HttpsDashboardProxy {

    private static final Set<String> HOP_BY_HOP_REQUEST_HEADERS =
            Set.of("connection", "keep-alive", "te", "trailers", "transfer-encoding", "upgrade", "content-length",
                    "host", "expect");
    private static final Set<String> HOP_BY_HOP_RESPONSE_HEADERS =
            Set.of("connection", "keep-alive", "transfer-encoding", "content-length");

    private final HttpsServer server;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String upstreamBaseUrl;

    /**
     * Builds the proxy from a PKCS12/JKS keystore file on disk; delegates to the
     * {@link KeyManager}[] constructor.
     */
    public HttpsDashboardProxy(int httpsPort, int upstreamPort, String p12FilePath, String p12Password) {
        this(httpsPort, upstreamPort, SSLUtils.createKeyManagerFromP12(p12FilePath, p12Password));
    }

    /**
     * Builds the proxy from pre-constructed {@link KeyManager}s, allowing the dashboard's TLS
     * identity to come from any source rather than being tied to a specific keystore file.
     */
    public HttpsDashboardProxy(int httpsPort, int upstreamPort, KeyManager[] keyManagers) {
        this.upstreamBaseUrl = "http://localhost:" + upstreamPort;
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, null, null);

            server = HttpsServer.create(new InetSocketAddress(httpsPort), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    params.setSSLParameters(getSSLContext().getDefaultSSLParameters());
                }
            });
            server.createContext("/", new ProxyHandler());
            // A null (default) executor handles every request sequentially on a single thread -
            // one slow or long-lived request (e.g. the dashboard's SSE live-update stream) would
            // then block every other request. Virtual threads give each request its own thread
            // cheaply, matching the virtual-thread executor already used elsewhere for JobRunr
            // (see DefaultJobSchedulerProvider/VirtualThreadJobRunrExecutor).
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise HTTPS dashboard proxy on port " + httpsPort, e);
        }
    }

    public void start() {
        server.start();
        log.info("HTTPS dashboard proxy listening, forwarding to {}", upstreamBaseUrl);
    }

    public void stop() {
        server.stop(0);
    }

    private final class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                URI upstreamUri = URI.create(upstreamBaseUrl + exchange.getRequestURI());
                HttpRequest.Builder builder = HttpRequest.newBuilder(upstreamUri);
                // JobRunr's SSE endpoints stream events for as long as the browser tab stays open -
                // potentially many minutes with no data at all while idle - so the normal 30s
                // request timeout (which covers the whole exchange, not just connecting) would
                // forcibly cut every SSE connection at exactly 30s regardless of network health,
                // tearing down the response being streamed back to the browser too ("Broken pipe").
                // Every other request still gets the 30s safety net.
                if (!exchange.getRequestURI().getPath().startsWith("/sse/")) {
                    builder.timeout(Duration.ofSeconds(30));
                }

                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (!HOP_BY_HOP_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                        for (String value : values) {
                            try {
                                builder.header(name, value);
                            } catch (IllegalArgumentException ignored) {
                                // JDK HttpClient forbids setting some restricted headers directly; skip those.
                            }
                        }
                    }
                });

                String method = exchange.getRequestMethod();
                boolean hasBody = !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method));
                builder.method(
                        method,
                        hasBody
                                ? BodyPublishers.ofInputStream(exchange::getRequestBody)
                                : BodyPublishers.noBody());

                HttpResponse<InputStream> response = client.send(builder.build(), BodyHandlers.ofInputStream());

                response.headers().map().forEach((name, values) -> {
                    if (!HOP_BY_HOP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                        exchange.getResponseHeaders().put(name, values);
                    }
                });

                int status = response.statusCode();
                // 204 (No Content) and 304 (Not Modified) forbid a response body, as does any
                // response to a HEAD request. For those, -1 sends headers only; passing 0 would
                // select chunked encoding and emit an (illegal) body, which the JDK server rejects.
                // JobRunr's dashboard returns 204 on job/recurring-job deletes.
                boolean noBody = status == 204 || status == 304 || "HEAD".equalsIgnoreCase(method);
                // 0 = chunked transfer encoding; response length is unknown up front and this also
                // lets SSE responses stream through as they're written rather than being buffered.
                exchange.sendResponseHeaders(status, noBody ? -1 : 0);
                try (InputStream in = response.body()) {
                    if (!noBody) {
                        try (OutputStream out = exchange.getResponseBody()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                                // Flush each read so tiny SSE events reach the browser immediately
                                // instead of sitting in the chunked-output buffer until it fills.
                                out.flush();
                            }
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Dashboard proxy request to {} failed: {}", exchange.getRequestURI(), e.toString());
                try {
                    exchange.sendResponseHeaders(502, -1);
                } catch (IOException ignored) {
                    // Best effort - connection may already be broken.
                }
            } finally {
                exchange.close();
            }
        }
    }
}
