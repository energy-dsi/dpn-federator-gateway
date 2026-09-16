// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.common.utils.ObjectMapperUtil;

/**
 * OpenID Connect Authorization Code flow for browser access to the Job Runner UI, run entirely
 * by {@link JobRunnerAuthGateway} - no external login-proxy required.
 * <p>
 * An unauthenticated browser request is redirected to Keycloak's authorization endpoint, with the
 * originally requested path and a CSRF state token stashed in a short-lived cookie. Keycloak's
 * redirect back to {@link JobRunnerAuthProperties#getOidcRedirectPath()} is exchanged for an
 * access token, which is then stored in an HttpOnly cookie so subsequent requests carry it
 * automatically. {@link BearerTokenVerifier} validates that token exactly as it would a bearer
 * header - this class only handles obtaining and storing it.
 */
@Slf4j
public final class OidcLoginFlow {

    private static final String STATE_COOKIE_NAME = "jobrunr_oidc_state";
    private static final SecureRandom RANDOM = new SecureRandom();
    // Used only if Keycloak's token response omits refresh_expires_in (non-standard but seen on
    // some setups) - 30 minutes matches this realm's default ssoSessionMaxLifespan.
    private static final long DEFAULT_REFRESH_TOKEN_TTL_SECONDS = 1800L;

    private final JobRunnerAuthProperties config;
    private final Supplier<HttpClient> httpClientSupplier;

    public OidcLoginFlow(JobRunnerAuthProperties config, Supplier<HttpClient> httpClientSupplier) {
        this.config = config;
        this.httpClientSupplier = httpClientSupplier;
    }

    /** Redirects the browser to Keycloak's login page, remembering the originally requested path. */
    void redirectToLogin(HttpExchange exchange) throws IOException {
        String state = randomToken();
        String originalPath = exchange.getRequestURI().toString();
        String redirectUri = config.getOidcPublicBaseUrl() + config.getOidcRedirectPath();

        String authorizeUrl = config.getAuthorizationEndpoint()
                + "?response_type=code"
                + "&client_id=" + encode(config.getOidcClientId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(config.getOidcScope())
                + "&state=" + encode(state);

        String stateCookieValue = state + "|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(originalPath.getBytes(StandardCharsets.UTF_8));

        exchange.getResponseHeaders().add("Set-Cookie", buildCookie(STATE_COOKIE_NAME, stateCookieValue, 300));
        exchange.getResponseHeaders().add("Location", authorizeUrl);
        exchange.sendResponseHeaders(302, -1);
    }

    /** True when the given request path is the configured OIDC callback path. */
    boolean isCallback(HttpExchange exchange) {
        return config.getOidcRedirectPath().equals(exchange.getRequestURI().getPath());
    }

    /** Handles Keycloak's redirect back with ?code=&state=: exchanges the code, then redirects to the original path. */
    void handleCallback(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String code = query.get("code");
        String state = query.get("state");
        String stateCookie = readCookie(exchange, STATE_COOKIE_NAME);

        if (code == null || state == null || stateCookie == null || !stateCookie.startsWith(state + "|")) {
            log.warn("Rejecting OIDC callback: missing or mismatched state");
            sendPlainText(exchange, 400, "Invalid OIDC callback (missing or mismatched state)");
            return;
        }

        String originalPath = decodeOriginalPath(stateCookie);
        Map<String, Object> tokenResponse;
        try {
            tokenResponse = exchangeCodeForToken(code);
            if (tokenResponse.get("access_token") == null) {
                throw new IllegalStateException("Token response did not contain an access_token");
            }
        } catch (Exception e) {
            log.warn("OIDC token exchange failed: {}", e.getMessage());
            sendPlainText(exchange, 502, "Failed to complete Keycloak login");
            return;
        }

        exchange.getResponseHeaders().add("Set-Cookie", buildCookie(STATE_COOKIE_NAME, "", 0));
        setTokenCookies(exchange, tokenResponse);
        exchange.getResponseHeaders().add("Location", originalPath);
        exchange.sendResponseHeaders(302, -1);
    }

    /**
     * Attempts to silently renew the access token using the refresh token cookie (if present),
     * without any browser redirect - avoids the visible Keycloak round-trip that a background
     * {@code fetch()}/{@code EventSource} call from the dashboard's own SPA can't follow anyway
     * (it would land on a cross-origin Keycloak page with no CORS headers, failing as a network
     * error rather than recovering). Sets fresh access/refresh token cookies on {@code exchange}'s
     * response on success, ready to be sent alongside whatever response the caller ultimately
     * produces.
     *
     * @param exchange the in-flight request/response being handled by {@link JobRunnerAuthGateway}
     * @return the new access token on success, or {@code null} if there is no refresh token cookie,
     *     or the refresh itself failed (e.g. the refresh token has also expired or been revoked) -
     *     callers should fall back to {@link #redirectToLogin(HttpExchange)} in that case
     */
    String tryRefresh(HttpExchange exchange) {
        String refreshToken = readCookie(exchange, config.getOidcRefreshCookieName());
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> tokenResponse = refreshAccessToken(refreshToken);
            String accessToken = (String) tokenResponse.get("access_token");
            if (accessToken == null) {
                return null;
            }
            setTokenCookies(exchange, tokenResponse);
            return accessToken;
        } catch (Exception e) {
            log.debug("Silent token refresh failed, falling back to full login: {}", e.getMessage());
            return null;
        }
    }

    /** Sets the access token cookie (always) and refresh token cookie (if Keycloak issued one). */
    private void setTokenCookies(HttpExchange exchange, Map<String, Object> tokenResponse) {
        String accessToken = (String) tokenResponse.get("access_token");
        Object expiresIn = tokenResponse.get("expires_in");
        long expiresInSeconds = expiresIn instanceof Number number ? number.longValue() : 3600L;
        exchange.getResponseHeaders()
                .add("Set-Cookie", buildCookie(config.getOidcCookieName(), accessToken, (int) expiresInSeconds));

        String refreshToken = (String) tokenResponse.get("refresh_token");
        if (refreshToken != null) {
            Object refreshExpiresIn = tokenResponse.get("refresh_expires_in");
            long refreshExpiresInSeconds =
                    refreshExpiresIn instanceof Number number ? number.longValue() : DEFAULT_REFRESH_TOKEN_TTL_SECONDS;
            exchange.getResponseHeaders()
                    .add(
                            "Set-Cookie",
                            buildCookie(
                                    config.getOidcRefreshCookieName(), refreshToken, (int) refreshExpiresInSeconds));
        }
    }

    private Map<String, Object> exchangeCodeForToken(String code) throws IOException, InterruptedException {
        String redirectUri = config.getOidcPublicBaseUrl() + config.getOidcRedirectPath();
        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri)
                + "&client_id=" + encode(config.getOidcClientId())
                + "&client_secret=" + encode(config.getOidcClientSecret());
        return requestToken(form);
    }

    private Map<String, Object> refreshAccessToken(String refreshToken) throws IOException, InterruptedException {
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + encode(refreshToken)
                + "&client_id=" + encode(config.getOidcClientId())
                + "&client_secret=" + encode(config.getOidcClientSecret());
        return requestToken(form);
    }

    private Map<String, Object> requestToken(String form) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getTokenEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = httpClientSupplier.get().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // Keycloak's error body (e.g. {"error":"invalid_grant","error_description":"Code not
            // valid"}) never echoes back the code or client_secret, so it's safe to include here -
            // without it, every failure just says "HTTP 400" with no way to tell an expired/reused
            // code from a client-secret mismatch or a redirect_uri mismatch.
            throw new IllegalStateException(
                    "Token endpoint returned HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        return ObjectMapperUtil.getInstance().readValue(response.body(), new TypeReference<Map<String, Object>>() {});
    }

    private String buildCookie(String name, String value, int maxAgeSeconds) {
        StringBuilder cookie = new StringBuilder()
                .append(name)
                .append('=')
                .append(value)
                .append("; Path=/")
                .append("; HttpOnly")
                .append("; SameSite=Lax")
                .append("; Max-Age=")
                .append(maxAgeSeconds);
        if (config.isOidcCookieSecure()) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }

    static String readCookie(HttpExchange exchange, String name) {
        List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null) {
            return null;
        }
        for (String header : cookieHeaders) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).equals(name)) {
                    return trimmed.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String decodeOriginalPath(String stateCookieValue) {
        int separator = stateCookieValue.indexOf('|');
        if (separator < 0) {
            return "/";
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(stateCookieValue.substring(separator + 1));
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static void sendPlainText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 500 ? value.substring(0, 500) + "...(truncated)" : value;
    }
}
