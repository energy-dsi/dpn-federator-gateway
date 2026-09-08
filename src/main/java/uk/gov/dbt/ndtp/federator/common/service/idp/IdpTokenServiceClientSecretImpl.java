// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced,
// and maintained by the National Digital Twin Programme.
package uk.gov.dbt.ndtp.federator.common.service.idp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorTokenException;

/**
 * Implementation of IdpTokenService that fetches tokens from an Identity Provider (IDP)
 * using client secret authentication, and caches them in Redis.
 */
@Slf4j
public class IdpTokenServiceClientSecretImpl extends AbstractIdpTokenService {

    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";
    private static final String MANAGEMENT_NODE_DEFAULT_ID = "default";
    private final String idpTokenUrl;
    private final String idpClientId;
    private final String idpClientSecret;

    /**
     * Original constructor - unchanged behaviour, always reads the "idp." prefixed properties
     * (the existing management-node Keycloak client) from common-configuration.properties.
     * Delegates to the new overload below so existing callers and tests are unaffected.
     */
    public IdpTokenServiceClientSecretImpl(
            Supplier<java.net.http.HttpClient> httpClientSupplier, ObjectMapper objectMapper) {
        this(httpClientSupplier, objectMapper, "idp.");
    }

    /**
     * Allows a second, independent instance of this service to be built against a different
     * Keycloak client, by reading a different property-name PREFIX from the SAME
     * common-configuration.properties file (e.g. "redis.idp." instead of the default "idp.").
     * No second file is used - both Keycloak clients' settings live in one file, distinguished
     * only by their property name prefix. Mirrors {@code IdpTokenServiceMtlsImpl}'s prefix
     * support and Redis caching, so callers can switch between the two implementations without
     * losing the token cache behaviour.
     *
     * @param propertyPrefix the property name prefix to read this client's token URL, JWKS URL,
     *                       client ID and client secret from (e.g. "idp." or "redis.idp.").
     */
    public IdpTokenServiceClientSecretImpl(
            Supplier<java.net.http.HttpClient> httpClientSupplier,
            ObjectMapper objectMapper,
            String propertyPrefix) {
        super(
                PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES).getProperty(propertyPrefix + "jwks.url"),
                httpClientSupplier,
                objectMapper);
        Properties properties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);
        this.idpTokenUrl = properties.getProperty(propertyPrefix + "token.url");
        this.idpClientId = properties.getProperty(propertyPrefix + "client.id");
        this.idpClientSecret = properties.getProperty(propertyPrefix + "client.secret");

        if (idpTokenUrl == null || idpTokenUrl.isBlank()) {
            log.error("IDP token URL is missing (property '{}token.url').", propertyPrefix);
        }
        if (idpClientId == null || idpClientId.isBlank()) {
            log.error("IDP client ID is missing (property '{}client.id').", propertyPrefix);
        }
        if (idpClientSecret == null || idpClientSecret.isBlank()) {
            log.warn("IDP client secret is missing (property '{}client.secret').", propertyPrefix);
        }

        log.info("IDP token service (client-secret) initialised [prefix='{}']. tokenUrl='{}', clientId='{}', secretPresent={}",
                propertyPrefix, idpTokenUrl, idpClientId, idpClientSecret != null && !idpClientSecret.isBlank());
    }

    /**
     * Fetches an access token using client credentials.
     *
     * @return The access token as a String
     * @throws FederatorTokenException if there is an error fetching the token
     */
    @Override
    public String fetchToken() {
        return fetchTokenWithResilience();
    }

    /**
     * Fetches an access token for the specified management node ID using client credentials.
     * If a valid token is cached in Redis, it is returned. Otherwise, a new token is fetched
     * from the IDP and cached.
     *
     * @param managementNodeId The management node identifier (can be null for default)
     * @return The access token as a String
     * @throws FederatorTokenException if there is an error fetching the token
     */
    @Override
    public String fetchToken(String managementNodeId) {
        log.trace("Fetching token for management node {}", managementNodeId);
        return fetchTokenInternal(managementNodeId);
    }

    private String fetchTokenInternal(String managementNodeId) {
        try {
            String cachedToken = getTokenFromCacheOrNull(managementNodeId);
            if (cachedToken != null) {
                return cachedToken;
            }

            String body = GRANT_TYPE
                    + EQUALS_SIGN
                    + CLIENT_CREDENTIALS
                    + AMPERSAND
                    + CLIENT_ID
                    + EQUALS_SIGN
                    + idpClientId
                    + AMPERSAND
                    + CLIENT_SECRET
                    + EQUALS_SIGN
                    + idpClientSecret;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(idpTokenUrl))
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_FORM_URLENCODED)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response =
                    httpClientSupplier.get().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new FederatorTokenException(
                        String.format("Failed to fetch token: HTTP %d - %s", response.statusCode(), response.body()));
            }
            Map<String, Object> json =
                    objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            String accessToken = (String) json.get(ACCESS_TOKEN);
            long expiresIn = ((Number) json.get("expires_in")).longValue(); // seconds

            log.info("Access token fetched successfully");
            persistTokenInCache(managementNodeId, accessToken, expiresIn);

            return accessToken;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FederatorTokenException("Thread interrupted while fetching token from IDP", e);
        } catch (Exception e) {
            throw new FederatorTokenException("Error fetching token from IDP", e);
        }
    }

    /**
     * Returns the cached token if Redis has one, or {@code null} if there's no cached token OR
     * Redis itself is unreachable. This caching is only a performance optimisation for the
     * always-on gRPC token flow (see GRPCUtils/GRPCClient) - it must never be the reason gRPC
     * authentication itself fails. In particular, when keycloak.auth.enabled=false forces Redis
     * TLS off (see RedisUtil), Redis becomes unreachable for THIS client even though gRPC auth
     * is deliberately unaffected by that switch - falling back to "fetch fresh every time"
     * here keeps that guarantee true in practice, not just on paper.
     */
    private String getTokenFromCacheOrNull(String managementNodeId) {
        String redisKey = getRedisKey(managementNodeId);
        try {
            String cachedToken = RedisUtil.getInstance().getValue(redisKey, String.class, true);
            if (cachedToken != null) {
                if (StringUtils.isBlank(managementNodeId)) {
                    log.debug("Using cached access token from Redis for default management node");
                } else {
                    log.debug("Using cached access token from Redis for management node {}", managementNodeId);
                }
            }
            return cachedToken;
        } catch (Exception e) {
            log.warn(
                    "Redis token cache unavailable (falling back to fetching a fresh token every time): {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Best-effort cache write. If Redis is unreachable, the token we already successfully
     * fetched from Keycloak is still returned to the caller and used - only the caching
     * optimisation is lost, not the authentication itself. See {@link #getTokenFromCacheOrNull}
     * for why this matters when keycloak.auth.enabled=false.
     */
    private void persistTokenInCache(String managementNodeId, String accessToken, long expiresIn) {
        String redisKey = getRedisKey(managementNodeId);
        try {
            RedisUtil.getInstance().setValue(redisKey, accessToken, expiresIn);
        } catch (Exception e) {
            log.warn("Unable to cache access token in Redis (token will be re-fetched next time): {}", e.getMessage());
        }
    }

    private String getRedisKey(String managementNodeId) {
        if (managementNodeId == null || managementNodeId.isBlank()) {
            managementNodeId = MANAGEMENT_NODE_DEFAULT_ID;
        }
        return "management_node_" + managementNodeId + "_" + idpClientId + "_access_token";
    }
}
