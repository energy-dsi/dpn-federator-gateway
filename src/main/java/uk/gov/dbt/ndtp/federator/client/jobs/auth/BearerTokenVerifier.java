// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Verifies Keycloak-issued bearer tokens presented to the {@link JobRunnerAuthGateway}.
 * <p>
 * Signature verification uses the realm's JWKS endpoint, cached with a TTL to avoid a network
 * round-trip on every dashboard request; the cache is bypassed (forced refresh) once when a
 * token references a {@code kid} that is not present in the cached key set, to tolerate
 * Keycloak key rotation without a restart.
 * <p>
 * A token that passes cryptographic verification (signature, expiry, issuer, audience) is always
 * {@link AuthResult#authorized()}; the resolved {@link AccessLevel} (based on
 * {@link JobRunnerAuthProperties#getAdminRole()} / {@link JobRunnerAuthProperties#getReaderRole()})
 * then determines what the caller is allowed to do - {@link AccessLevel#NONE} rejects every
 * request, even read-only ones, at the gateway.
 */
@Slf4j
public class BearerTokenVerifier {

    private final JobRunnerAuthProperties config;
    private final Supplier<HttpClient> httpClientSupplier;

    private volatile JWKSet cachedJwkSet;
    private volatile Instant cacheExpiresAt = Instant.EPOCH;

    public BearerTokenVerifier(JobRunnerAuthProperties config) {
        this(config, () -> HttpClient.newBuilder()
                .sslContext(trustStoreSslContext())
                .build());
    }

    /**
     * Builds an SSLContext from its own dedicated {@code jobs.dashboard.auth.trustStore*} system
     * properties, rather than sharing the JVM-wide {@code javax.net.ssl.trustStore} (or its
     * implicit default {@link SSLContext#getDefault()}). Kafka's own client takes the same
     * approach - it never relies on the JVM-wide trust store either, pointing its
     * {@code ssl.truststore.*} settings straight at its own file - which is why its OAuthBearer
     * login isn't affected by whatever else in this process (gRPC, OpenTelemetry) touches shared
     * JVM TLS state. This mirrors that isolation for the JWKS fetch here.
     */
    private static SSLContext trustStoreSslContext() {
        String trustStorePath = System.getProperty("jobs.dashboard.auth.trustStore");
        try {
            if (trustStorePath == null) {
                return SSLContext.getDefault();
            }
            String trustStoreType = System.getProperty("jobs.dashboard.auth.trustStoreType", KeyStore.getDefaultType());
            String trustStorePassword = System.getProperty("jobs.dashboard.auth.trustStorePassword", "");
            KeyStore trustStore = KeyStore.getInstance(trustStoreType);
            try (InputStream in = Files.newInputStream(Path.of(trustStorePath))) {
                trustStore.load(in, trustStorePassword.toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to build SSLContext from jobs.dashboard.auth.trustStore=" + trustStorePath, e);
        }
    }

    public BearerTokenVerifier(JobRunnerAuthProperties config, Supplier<HttpClient> httpClientSupplier) {
        this.config = config;
        this.httpClientSupplier = httpClientSupplier;
    }

    /**
     * Verifies the given raw JWT: signature (via JWKS), expiry, issuer and audience, then resolves
     * the caller's {@link AccessLevel} from their realm/client roles.
     */
    public AuthResult verify(String token) {
        if (token == null || token.isBlank()) {
            return AuthResult.failure("missing token");
        }
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            String kid = signedJWT.getHeader().getKeyID();

            JWK jwk = selectVerificationKey(kid, false);
            if (jwk == null) {
                // Key not found; could be a rotation - force a refresh and retry once.
                jwk = selectVerificationKey(kid, true);
            }
            if (jwk == null || !(jwk instanceof RSAKey rsaKey)) {
                return AuthResult.failure("no matching JWKS key for kid: " + kid);
            }

            JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
            if (!signedJWT.verify(verifier)) {
                return AuthResult.failure("invalid signature");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            return validateClaims(claims);
        } catch (ParseException e) {
            log.debug("Rejected malformed bearer token: {}", e.getMessage());
            return AuthResult.failure("malformed token");
        } catch (Exception e) {
            log.warn("Bearer token verification failed: {}", e.getMessage());
            return AuthResult.failure("verification error");
        }
    }

    private AuthResult validateClaims(JWTClaimsSet claims) {
        var exp = claims.getExpirationTime();
        if (exp == null || exp.toInstant().isBefore(Instant.now())) {
            return AuthResult.failure("token expired");
        }

        if (config.getIssuerUrl() != null && !config.getIssuerUrl().equals(claims.getIssuer())) {
            return AuthResult.failure("unexpected issuer");
        }

        if (config.getAudience() != null && !audienceMatches(claims, config.getAudience())) {
            return AuthResult.failure("unexpected audience");
        }

        AccessLevel accessLevel = resolveAccessLevel(claims);
        return AuthResult.success(claims.getSubject(), accessLevel);
    }

    private boolean audienceMatches(JWTClaimsSet claims, String expectedAudience) {
        List<String> audiences = claims.getAudience();
        if (audiences != null && audiences.contains(expectedAudience)) {
            return true;
        }
        try {
            String azp = claims.getStringClaim("azp");
            return expectedAudience.equals(azp);
        } catch (ParseException e) {
            return false;
        }
    }

    /**
     * Resolves the caller's {@link AccessLevel} from their realm/client roles: the admin role (if
     * present) wins over the reader role. When neither role is configured, any authenticated
     * caller is granted {@link AccessLevel#ADMIN} - i.e. authentication only, no role gating.
     */
    private AccessLevel resolveAccessLevel(JWTClaimsSet claims) {
        if (config.getAdminRole() == null && config.getReaderRole() == null) {
            return AccessLevel.ADMIN;
        }
        Set<String> roles = extractRoles(claims);
        if (config.getAdminRole() != null && roles.contains(config.getAdminRole())) {
            return AccessLevel.ADMIN;
        }
        if (config.getReaderRole() != null && roles.contains(config.getReaderRole())) {
            return AccessLevel.READER;
        }
        return AccessLevel.NONE;
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRoles(JWTClaimsSet claims) {
        Set<String> roles = new HashSet<>();
        try {
            Map<String, Object> realmAccess = claims.getJSONObjectClaim("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> realmRoles) {
                for (Object role : realmRoles) {
                    roles.add(String.valueOf(role));
                }
            }
        } catch (ParseException e) {
            log.debug("Could not parse realm_access claim: {}", e.getMessage());
        }

        // Some realms map realm roles onto a flat top-level "roles" claim instead of the
        // Keycloak-standard nested realm_access.roles (a custom protocol mapper choice, not part
        // of the OIDC/Keycloak default) - check for that shape too.
        try {
            if (claims.getClaim("roles") instanceof List<?> flatRoles) {
                for (Object role : flatRoles) {
                    roles.add(String.valueOf(role));
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not parse flat roles claim: {}", e.getMessage());
        }

        if (config.getAudience() != null) {
            try {
                Map<String, Object> resourceAccess = claims.getJSONObjectClaim("resource_access");
                if (resourceAccess != null
                        && resourceAccess.get(config.getAudience()) instanceof Map<?, ?> clientAccess
                        && clientAccess.get("roles") instanceof List<?> clientRoles) {
                    for (Object role : clientRoles) {
                        roles.add(String.valueOf(role));
                    }
                }
            } catch (ParseException e) {
                log.debug("Could not parse resource_access claim: {}", e.getMessage());
            }
        }
        return roles;
    }

    private JWK selectVerificationKey(String kid, boolean forceRefresh) {
        JWKSet jwkSet = forceRefresh ? refreshJwks() : jwks();
        JWKSelector selector = new JWKSelector(
                new JWKMatcher.Builder().keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
        var matches = selector.select(jwkSet);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private synchronized JWKSet jwks() {
        if (cachedJwkSet == null || Instant.now().isAfter(cacheExpiresAt)) {
            return refreshJwks();
        }
        return cachedJwkSet;
    }

    private synchronized JWKSet refreshJwks() {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder().uri(URI.create(config.getJwksUrl())).GET().build();
            HttpResponse<String> response =
                    httpClientSupplier.get().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Failed to fetch JWKS: HTTP " + response.statusCode() + " from " + config.getJwksUrl());
            }
            cachedJwkSet = JWKSet.parse(response.body());
            cacheExpiresAt = Instant.now().plusSeconds(config.getJwksCacheTtlSeconds());
            return cachedJwkSet;
        } catch (Exception e) {
            if (cachedJwkSet != null) {
                log.warn("Failed to refresh JWKS, continuing with previously cached keys: {}", e.getMessage());
                return cachedJwkSet;
            }
            throw new IllegalStateException("Unable to fetch JWKS from " + config.getJwksUrl(), e);
        }
    }

    /** Testing helper: forces the next lookup to treat the cache as expired. */
    void invalidateCache() {
        cacheExpiresAt = Instant.EPOCH;
    }

    Duration cacheTtl() {
        return Duration.ofSeconds(config.getJwksCacheTtlSeconds());
    }
}
