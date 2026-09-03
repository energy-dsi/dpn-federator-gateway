// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs.auth;

import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.exceptions.ConfigurationException;

/**
 * Configuration for Keycloak-based authentication in front of the JobRunr dashboard
 * (the Job Runner user interface exposed by the federator client).
 * <p>
 * When {@code jobs.dashboard.auth.enabled=true}, a {@link JobRunnerAuthGateway} is placed in
 * front of the JobRunr dashboard, listening on its own {@code jobs.dashboard.auth.port} and
 * forwarding authenticated requests to the dashboard on {@code jobs.dashboard.port} (which never
 * moves). The gateway expects the request to already carry a Keycloak-issued bearer token
 * (typically attached by an upstream oauth2-proxy instance sitting in front of this service) and
 * verifies it against the Keycloak realm's JWKS before proxying the request through.
 * <p>
 * Two independent roles gate two independent {@link AccessLevel}s: {@code jobs.dashboard.auth.admin.role}
 * grants full access (view plus trigger/requeue/delete), {@code jobs.dashboard.auth.reader.role}
 * grants view-only access (HTTP GET/HEAD/OPTIONS - blocked from any job-mutating action). A caller
 * with neither role is rejected entirely. If both are left unset, any successfully authenticated
 * token is granted full ({@link AccessLevel#ADMIN}) access - no role gating.
 * <p>
 * This composes with {@code jobs.dashboard.https.enabled} (see {@code HttpsDashboardProxy}): when
 * both are enabled, the chain is HTTPS proxy (public) -&gt; auth gateway -&gt; dashboard
 * (innermost). When only auth is enabled, the gateway's own port becomes the public entry point.
 * <p>
 * When disabled (the default), the dashboard behaves exactly as before: nothing sits between
 * callers and {@code jobs.dashboard.port} (or the HTTPS proxy, if that alone is enabled).
 */
public final class JobRunnerAuthProperties {

    public static final String PROP_AUTH_ENABLED = "jobs.dashboard.auth.enabled";
    public static final String PROP_ISSUER_URL = "jobs.dashboard.auth.issuer.url";
    public static final String PROP_JWKS_URL = "jobs.dashboard.auth.jwks.url";
    public static final String PROP_AUDIENCE = "jobs.dashboard.auth.audience";
    public static final String PROP_ADMIN_ROLE = "jobs.dashboard.auth.admin.role";
    public static final String PROP_READER_ROLE = "jobs.dashboard.auth.reader.role";
    public static final String PROP_HEADER_NAME = "jobs.dashboard.auth.header.name";
    public static final String PROP_HEADER_SCHEME = "jobs.dashboard.auth.header.scheme";
    public static final String PROP_JWKS_CACHE_TTL_SECONDS = "jobs.dashboard.auth.jwks.cache.ttl.seconds";
    // The gateway's own bind port; jobs.dashboard.port always remains the dashboard's own port.
    public static final String PROP_GATEWAY_PORT = "jobs.dashboard.auth.port";
    // Browser login (OIDC authorization code flow), handled entirely by JobRunnerAuthGateway/
    // OidcLoginFlow - no external login-proxy required. Optional: only active when both client
    // id and secret are set (see isBrowserLoginEnabled()).
    public static final String PROP_OIDC_CLIENT_ID = "jobs.dashboard.auth.oidc.client.id";
    public static final String PROP_OIDC_CLIENT_SECRET = "jobs.dashboard.auth.oidc.client.secret";
    public static final String PROP_OIDC_REDIRECT_PATH = "jobs.dashboard.auth.oidc.redirect.path";
    public static final String PROP_OIDC_SCOPE = "jobs.dashboard.auth.oidc.scope";
    public static final String PROP_OIDC_COOKIE_NAME = "jobs.dashboard.auth.oidc.cookie.name";
    public static final String PROP_OIDC_COOKIE_SECURE = "jobs.dashboard.auth.oidc.cookie.secure";
    // Public URL the browser actually uses to reach the dashboard (e.g. through
    // HttpsDashboardProxy) - the gateway itself only ever sees the internal loopback
    // request, so the redirect_uri sent to Keycloak can't be derived from the incoming
    // request's Host header. Required when browser login is enabled.
    public static final String PROP_OIDC_PUBLIC_BASE_URL = "jobs.dashboard.auth.oidc.public.base.url";

    private static final String DEFAULT_HEADER_NAME = "Authorization";
    private static final String DEFAULT_HEADER_SCHEME = "Bearer";
    private static final String DEFAULT_JWKS_CACHE_TTL_SECONDS = "300";
    private static final String DEFAULT_OIDC_REDIRECT_PATH = "/oauth2/callback";
    private static final String DEFAULT_OIDC_SCOPE = "openid";
    private static final String DEFAULT_OIDC_COOKIE_NAME = "jobrunr_at";
    private static final String DEFAULT_OIDC_COOKIE_SECURE = "true";
    private static final String OPENID_CERTS_PATH = "/protocol/openid-connect/certs";
    private static final String OPENID_AUTH_PATH = "/protocol/openid-connect/auth";
    private static final String OPENID_TOKEN_PATH = "/protocol/openid-connect/token";
    private static final int DEFAULT_GATEWAY_PORT_OFFSET = 10000;

    private final boolean enabled;
    private final String issuerUrl;
    private final String jwksUrl;
    private final String audience;
    private final String adminRole;
    private final String readerRole;
    private final String headerName;
    private final String headerScheme;
    private final long jwksCacheTtlSeconds;
    // The dashboard's own port - the gateway's upstream target. Always equal to jobs.dashboard.port.
    private final int internalPort;
    // The gateway's own bind port - jobs.dashboard.auth.port.
    private final int gatewayPort;
    private final String oidcClientId;
    private final String oidcClientSecret;
    private final String oidcRedirectPath;
    private final String oidcScope;
    private final String oidcCookieName;
    private final boolean oidcCookieSecure;
    private final String oidcPublicBaseUrl;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;

    // Package-private (rather than private) so tests in this package can build instances directly
    // without going through PropertyUtil.
    JobRunnerAuthProperties(
            boolean enabled,
            String issuerUrl,
            String jwksUrl,
            String audience,
            String adminRole,
            String readerRole,
            String headerName,
            String headerScheme,
            long jwksCacheTtlSeconds,
            int internalPort,
            int gatewayPort,
            String oidcClientId,
            String oidcClientSecret,
            String oidcRedirectPath,
            String oidcScope,
            String oidcCookieName,
            boolean oidcCookieSecure,
            String oidcPublicBaseUrl,
            String authorizationEndpoint,
            String tokenEndpoint) {
        this.enabled = enabled;
        this.issuerUrl = issuerUrl;
        this.jwksUrl = jwksUrl;
        this.audience = audience;
        this.adminRole = adminRole;
        this.readerRole = readerRole;
        this.headerName = headerName;
        this.headerScheme = headerScheme;
        this.jwksCacheTtlSeconds = jwksCacheTtlSeconds;
        this.internalPort = internalPort;
        this.gatewayPort = gatewayPort;
        this.oidcClientId = oidcClientId;
        this.oidcClientSecret = oidcClientSecret;
        this.oidcRedirectPath = oidcRedirectPath;
        this.oidcScope = oidcScope;
        this.oidcCookieName = oidcCookieName;
        this.oidcCookieSecure = oidcCookieSecure;
        this.oidcPublicBaseUrl = oidcPublicBaseUrl;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
    }

    /**
     * Loads and validates auth configuration from {@link PropertyUtil}, given the dashboard's own
     * port (which never moves). If auth is disabled, no validation of Keycloak-specific properties
     * is performed.
     *
     * @param dashboardPort the JobRunr dashboard's own port (jobs.dashboard.port); used both as the
     *                      gateway's upstream target and to derive a default gateway port when
     *                      {@code jobs.dashboard.auth.port} is unset
     * @throws ConfigurationException if auth is enabled but required properties are missing/invalid
     */
    public static JobRunnerAuthProperties load(int dashboardPort) {
        boolean enabled = PropertyUtil.getPropertyBooleanValue(PROP_AUTH_ENABLED, "false");

        String issuerUrl = trimToNull(PropertyUtil.getPropertyValue(PROP_ISSUER_URL, ""));
        String jwksUrl = trimToNull(PropertyUtil.getPropertyValue(PROP_JWKS_URL, ""));
        String audience = trimToNull(PropertyUtil.getPropertyValue(PROP_AUDIENCE, ""));
        String adminRole = trimToNull(PropertyUtil.getPropertyValue(PROP_ADMIN_ROLE, ""));
        String readerRole = trimToNull(PropertyUtil.getPropertyValue(PROP_READER_ROLE, ""));
        String headerName = PropertyUtil.getPropertyValue(PROP_HEADER_NAME, DEFAULT_HEADER_NAME);
        String headerScheme = PropertyUtil.getPropertyValue(PROP_HEADER_SCHEME, DEFAULT_HEADER_SCHEME);
        long jwksCacheTtlSeconds =
                PropertyUtil.getPropertyLongValue(PROP_JWKS_CACHE_TTL_SECONDS, DEFAULT_JWKS_CACHE_TTL_SECONDS);
        int gatewayPort = PropertyUtil.getPropertyIntValue(
                PROP_GATEWAY_PORT, String.valueOf(dashboardPort + DEFAULT_GATEWAY_PORT_OFFSET));

        if (jwksUrl == null && issuerUrl != null) {
            jwksUrl = issuerUrl + OPENID_CERTS_PATH;
        }

        String oidcClientId = trimToNull(PropertyUtil.getPropertyValue(PROP_OIDC_CLIENT_ID, ""));
        String oidcClientSecret = trimToNull(PropertyUtil.getPropertyValue(PROP_OIDC_CLIENT_SECRET, ""));
        String oidcRedirectPath = PropertyUtil.getPropertyValue(PROP_OIDC_REDIRECT_PATH, DEFAULT_OIDC_REDIRECT_PATH);
        String oidcScope = PropertyUtil.getPropertyValue(PROP_OIDC_SCOPE, DEFAULT_OIDC_SCOPE);
        String oidcCookieName = PropertyUtil.getPropertyValue(PROP_OIDC_COOKIE_NAME, DEFAULT_OIDC_COOKIE_NAME);
        boolean oidcCookieSecure =
                PropertyUtil.getPropertyBooleanValue(PROP_OIDC_COOKIE_SECURE, DEFAULT_OIDC_COOKIE_SECURE);
        String oidcPublicBaseUrl = trimToNull(PropertyUtil.getPropertyValue(PROP_OIDC_PUBLIC_BASE_URL, ""));
        String authorizationEndpoint = issuerUrl == null ? null : issuerUrl + OPENID_AUTH_PATH;
        String tokenEndpoint = issuerUrl == null ? null : issuerUrl + OPENID_TOKEN_PATH;

        JobRunnerAuthProperties props = new JobRunnerAuthProperties(
                enabled,
                issuerUrl,
                jwksUrl,
                audience,
                adminRole,
                readerRole,
                headerName,
                headerScheme,
                jwksCacheTtlSeconds,
                dashboardPort,
                gatewayPort,
                oidcClientId,
                oidcClientSecret,
                oidcRedirectPath,
                oidcScope,
                oidcCookieName,
                oidcCookieSecure,
                oidcPublicBaseUrl,
                authorizationEndpoint,
                tokenEndpoint);

        if (enabled) {
            props.validate();
        }
        return props;
    }

    private void validate() {
        if (jwksUrl == null) {
            throw new ConfigurationException(
                    "jobs.dashboard.auth.enabled=true requires either '" + PROP_JWKS_URL + "' or '" + PROP_ISSUER_URL
                            + "' to be set");
        }
        if (gatewayPort == internalPort) {
            throw new ConfigurationException(
                    "'" + PROP_GATEWAY_PORT + "' must differ from 'jobs.dashboard.port' (" + internalPort + ")");
        }
        if ((oidcClientId == null) != (oidcClientSecret == null)) {
            throw new ConfigurationException(
                    "'" + PROP_OIDC_CLIENT_ID + "' and '" + PROP_OIDC_CLIENT_SECRET
                            + "' must both be set together to enable browser login, or both left blank");
        }
        if (isBrowserLoginEnabled() && oidcPublicBaseUrl == null) {
            throw new ConfigurationException("'" + PROP_OIDC_PUBLIC_BASE_URL
                    + "' is required when browser login is enabled (oidc client id/secret set)");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getIssuerUrl() {
        return issuerUrl;
    }

    public String getJwksUrl() {
        return jwksUrl;
    }

    public String getAudience() {
        return audience;
    }

    /** Realm/client role granting full ({@link AccessLevel#ADMIN}) access. Optional. */
    public String getAdminRole() {
        return adminRole;
    }

    /** Realm/client role granting view-only ({@link AccessLevel#READER}) access. Optional. */
    public String getReaderRole() {
        return readerRole;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getHeaderScheme() {
        return headerScheme;
    }

    public long getJwksCacheTtlSeconds() {
        return jwksCacheTtlSeconds;
    }

    /** The dashboard's own port (jobs.dashboard.port) - the gateway's upstream target. */
    public int getInternalPort() {
        return internalPort;
    }

    /** The gateway's own bind port (jobs.dashboard.auth.port). */
    public int getGatewayPort() {
        return gatewayPort;
    }

    /** True when both {@code oidc.client.id} and {@code oidc.client.secret} are set. */
    public boolean isBrowserLoginEnabled() {
        return oidcClientId != null && oidcClientSecret != null;
    }

    public String getOidcClientId() {
        return oidcClientId;
    }

    public String getOidcClientSecret() {
        return oidcClientSecret;
    }

    /** Path the gateway treats as the OIDC callback, e.g. {@code /oauth2/callback}. */
    public String getOidcRedirectPath() {
        return oidcRedirectPath;
    }

    public String getOidcScope() {
        return oidcScope;
    }

    /** Name of the cookie the access token is stored in after a successful browser login. */
    public String getOidcCookieName() {
        return oidcCookieName;
    }

    public boolean isOidcCookieSecure() {
        return oidcCookieSecure;
    }

    /** Public URL the browser uses to reach the dashboard - used to build the redirect_uri. */
    public String getOidcPublicBaseUrl() {
        return oidcPublicBaseUrl;
    }

    /** Keycloak's authorization endpoint, derived from issuer.url. */
    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    /** Keycloak's token endpoint, derived from issuer.url. */
    public String getTokenEndpoint() {
        return tokenEndpoint;
    }
}
