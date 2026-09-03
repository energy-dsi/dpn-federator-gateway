// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
 *  Modifications made by the National Digital Twin Programme (NDTP)
 *  © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
 *  and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.common.utils;

import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;

/**
 * Utility class to interact with Redis.
 * <p>
 * This class is a singleton and should be used to interact with Redis.
 * It is used to set and get offsets for a given client and topic.
 * The class is thread-safe.
 * </p>
 * <p>
 * NOTE: Redis-native username/password (ACL) authentication is intentionally NOT used here.
 * Access control is enforced entirely at the application layer, by
 * {@link #getKeycloakGatedInstance()} requiring a valid Keycloak session
 * ({@code KeycloakSessionGuard}) before returning a connection - see
 * {@link #getKeycloakGatedInstance()} for the one exception (IDP token caching) that
 * deliberately bypasses this gate.
 * </p>
 */
public class RedisUtil {

    public static final String REDIS_HOST = "redis.host";
    public static final String REDIS_PORT = "redis.port";
    public static final String REDIS_KEY_PREFIX = "redis.prefix";
    public static final String REDIS_TLS_ENABLED = "redis.tls.enabled";
    public static final String REDIS_TRUSTSTORE_PATH = "redis.truststore.path";
    public static final String REDIS_TRUSTSTORE_PASSWORD = "redis.truststore.password";
    public static final String REDIS_KEYSTORE_PATH = "redis.keystore.path";
    public static final String REDIS_KEYSTORE_PASSWORD = "redis.keystore.password";
    public static final String REDIS_AES_KEY = "redis.aes.key";
    public static final String LOCALHOST = "localhost";
    public static final String DEFAULT_PORT = "6379";

    /**
     * Same key used elsewhere (e.g. {@code IdpTokenServiceMtlsImpl}, {@code GRPCUtils}) to look up
     * the path to the shared common-configuration.properties file from the main properties file.
     * redis.tls.enabled / redis.truststore.path / redis.truststore.password live there since they
     * are identical for both federator-server and federator-client - see common-configuration.properties.
     */
    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";
    public static final String TRUE = "true";
    public static final Logger LOGGER = LoggerFactory.getLogger("RedisUtil");
    private static RedisUtil instance;
    private static String redisAesKeyValue;
    private final JedisPooled jedisPooled;

    /**
     * Constructor with existing Jedis pooled instance.
     *
     * @param jedisPooled Jedis pooled instance.
     */
    RedisUtil(JedisPooled jedisPooled) {
        this.jedisPooled = jedisPooled;
    }

    /**
     * Gets the singleton instance of RedisUtil. This is the UNGATED accessor - it does not
     * require a valid Keycloak session. Used only by {@code IdpTokenServiceMtlsImpl} for its
     * own token cache, to avoid a circular dependency (checking the cache to avoid a Keycloak
     * call would otherwise itself require a Keycloak session). Every other Redis-touching call
     * site in the codebase should use {@link #getKeycloakGatedInstance()} instead.
     *
     * @return the singleton instance of RedisUtil.
     */
    public static synchronized RedisUtil getInstance() {
        if (instance == null) {
            redisAesKeyValue = PropertyUtil.getPropertyValue(REDIS_AES_KEY, "");
            String host = PropertyUtil.getPropertyValue(REDIS_HOST, LOCALHOST);
            LOGGER.info("Using Redis Host - '{}'", host);
            int port = PropertyUtil.getPropertyIntValue(REDIS_PORT, DEFAULT_PORT);
            LOGGER.info("Using Redis on Port - '{}'", port);

            // redis.tls.enabled / redis.truststore.path / redis.truststore.password are identical
            // for federator-server and federator-client, so they live once in
            // common-configuration.properties rather than being duplicated in each role's main
            // properties file.
            Properties commonProperties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);
            boolean isTLSEnabled = Boolean.parseBoolean(commonProperties.getProperty(REDIS_TLS_ENABLED, TRUE));

            // Redis TLS is controlled SOLELY by redis.tls.enabled and is completely independent
            // of keycloak.auth.enabled - Redis TLS is always implemented regardless of the
            // switch's value. Only getKeycloakGatedInstance()'s authentication step (the
            // Keycloak session check) is affected by that switch, not TLS. See
            // KeycloakAuthConfig's Javadoc for the full, current scope of that flag.
            LOGGER.info("Using TLS with Redis - '{}'", isTLSEnabled);
            LOGGER.info("Redis-native username/password authentication is disabled; "
                    + "access control is enforced by the Keycloak gate at the application layer.");

            String truststorePath = isTLSEnabled ? commonProperties.getProperty(REDIS_TRUSTSTORE_PATH, "") : "";
            String truststorePassword =
                    isTLSEnabled ? commonProperties.getProperty(REDIS_TRUSTSTORE_PASSWORD, "") : "";
            // Redis defaults to tls-auth-clients yes (mutual TLS), so the client must present its
            // own certificate, not just trust Redis's - keystorePath blank falls back to
            // trust-only (buildRedisConnection), which only works if Redis's tls-auth-clients is
            // explicitly set to no.
            String keystorePath = isTLSEnabled ? commonProperties.getProperty(REDIS_KEYSTORE_PATH, "") : "";
            String keystorePassword = isTLSEnabled ? commonProperties.getProperty(REDIS_KEYSTORE_PASSWORD, "") : "";

            instance = new RedisUtil(buildRedisConnection(
                    host, port, isTLSEnabled, truststorePath, truststorePassword, keystorePath, keystorePassword));
        }
        return instance;
    }

    /**
     * Like {@link #getInstance()}, but first ensures a valid Keycloak session exists via
     * {@code KeycloakSessionGuard.ensureAuthenticated()}, unless Keycloak authentication is
     * disabled entirely via {@code KeycloakAuthConfig.isEnabled() == false} (local development
     * testing only). Use this for all Redis access EXCEPT the IDP token cache itself (see
     * {@link #getInstance()}).
     *
     * @return the same singleton {@link RedisUtil} instance as {@link #getInstance()}.
     * @throws IllegalStateException if Keycloak authentication is enabled but fails.
     */
    public static RedisUtil getKeycloakGatedInstance() {
        if (!KeycloakAuthConfig.isEnabled()) {
            return getInstance();
        }
        KeycloakSessionGuard.ensureAuthenticated();
        return getInstance();
    }

    /**
     * Builds a JedisPooled connection over TLS, trusting a custom truststore if one is
     * configured. No username/password (ACL) is ever set - see the class Javadoc.
     *
     * @param host              redis host address.
     * @param port              redis port number.
     * @param isTLSEnabled      whether TLS is enabled.
     * @param truststorePath    path to a truststore file (JKS or PKCS12, auto-detected), or blank
     *                          to use the JVM default trust store.
     * @param truststorePassword password for the truststore.
     * @param keystorePath      path to a PKCS12 keystore holding this client's own certificate
     *                          and private key, or blank to skip presenting a client certificate.
     *                          Required when Redis's tls-auth-clients is yes (its default) -
     *                          otherwise Redis rejects the handshake with "certificate required".
     * @param keystorePassword  password for the keystore.
     */
    private static JedisPooled buildRedisConnection(
            String host,
            int port,
            boolean isTLSEnabled,
            String truststorePath,
            String truststorePassword,
            String keystorePath,
            String keystorePassword) {

        DefaultJedisClientConfig.Builder jedisClientConfigBuilder =
                DefaultJedisClientConfig.builder().ssl(isTLSEnabled);

        if (isTLSEnabled && truststorePath != null && !truststorePath.isBlank()) {
            SSLContext sslContext;
            if (keystorePath != null && !keystorePath.isBlank()) {
                LOGGER.info(
                        "Configuring Redis TLS using truststore '{}' and client keystore '{}'",
                        truststorePath,
                        keystorePath);
                sslContext = SSLUtils.createSSLContext(
                        keystorePath, keystorePassword, truststorePath, truststorePassword);
            } else {
                LOGGER.info(
                        "Configuring Redis TLS using truststore '{}' (no client keystore configured - "
                                + "requires Redis's tls-auth-clients to be 'no')",
                        truststorePath);
                sslContext = SSLUtils.createSSLContextWithTrustStore(truststorePath, truststorePassword);
            }
            jedisClientConfigBuilder.sslSocketFactory(sslContext.getSocketFactory());

            // A bare SSLSocketFactory only validates the certificate CHAIN - it does not
            // automatically validate that the certificate's SAN matches the hostname being
            // connected to unless explicitly told to. "HTTPS" is the standard JDK algorithm
            // name for hostname verification generically, not specific to the HTTP protocol.
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
            jedisClientConfigBuilder.sslParameters(sslParameters);
            LOGGER.info("Hostname verification explicitly enabled for Redis TLS connection");
        }

        return new JedisPooled(new HostAndPort(host, port), jedisClientConfigBuilder.build());
    }

    private static String qualifyOffset(String key) {
        key = getPrefixedKey(key);
        return "topic:" + key + ":offset";
    }

    /**
     * Checks if an AES key for encrypting/decrypting values in Redis has been set.
     * @return true if an AES key has been set, otherwise false
     */
    private static boolean redisAesKeyValueIsSet() {
        return redisAesKeyValue != null && !redisAesKeyValue.isBlank();
    }

    private long getOffset(String key) {
        key = qualifyOffset(key);
        LOGGER.debug("Retrieving offset from redis {}", key);
        String offset = getValue(key, String.class, redisAesKeyValueIsSet());
        return (offset != null ? Long.parseLong(offset) : 0L);
    }

    public long getOffset(String clientName, String topic) {
        return getOffset(clientName + "-" + topic);
    }

    private String setOffset(String key, long value) {
        LOGGER.debug("Persisting offset in redis {} = {}", key, value);
        setValue(qualifyOffset(key), value);
        return "OK";
    }

    public String setOffset(String clientName, String topic, long value) {
        return setOffset(clientName + "-" + topic, value);
    }

    /**
     * Stores a value in Redis at the given key without encryption.
     * No TTL.
     *
     * @param key   the Redis key
     * @param value the object to store
     * @param <T>   type of the value
     * @return true if Redis SET returned "OK"
     */
    public <T> boolean setValue(String key, T value) {
        return setValue(key, value, null);
    }

    /**
     * Stores a value in Redis at the given key with a TTL.
     * No encryption.
     *
     * @param key         the Redis key
     * @param value       the object to store
     * @param ttlSeconds  time-to-live in seconds; if null or &le; 0 then no TTL
     * @param <T>         type of the value
     * @return true if Redis SET returned "OK"
     */
    public <T> boolean setValue(String key, T value, Long ttlSeconds) {
        try {
            key = getPrefixedKey(key);
            boolean encrypt = redisAesKeyValueIsSet();
            String json = ObjectMapperUtil.getInstance().writeValueAsString(value);
            String toWrite = encrypt ? AesCryptoUtil.encrypt(json, redisAesKeyValue) : json;

            if (ttlSeconds != null && ttlSeconds > 0) {
                LOGGER.debug("Persisting key in redis {} (encrypted={}, ttlSeconds={})", key, encrypt, ttlSeconds);
                return "OK"
                        .equals(jedisPooled.set(
                                key, toWrite, SetParams.setParams().ex(ttlSeconds)));
            } else {
                LOGGER.debug("Persisting key in redis {} (encrypted={}, no TTL)", key, encrypt);
                return "OK".equals(jedisPooled.set(key, toWrite));
            }
        } catch (Exception e) {
            throw new JedisDataException("Failed to set value in Redis for key " + key, e);
        }
    }

    /**
     * Retrieves a value from Redis at the given key without decryption.
     *
     * @param key  the Redis key
     * @param type the expected class type
     * @param <T>  type of the value
     * @param encrypted  whether the value will have been encrypted
     * @return the deserialised object, or null if the key is not found
     */
    public <T> T getValue(String key, Class<T> type, boolean encrypted) {
        try {
            key = getPrefixedKey(key);
            String stored = jedisPooled.get(key);
            if (stored == null) return null;
            String json =
                    encrypted && redisAesKeyValueIsSet() ? AesCryptoUtil.decrypt(stored, redisAesKeyValue) : stored;
            return ObjectMapperUtil.getInstance().readValue(json, type);
        } catch (Exception e) {
            throw new JedisDataException("Failed to get value from Redis for key " + key, e);
        }
    }

    public static String getPrefixedKey(String key) {
        String prefix;
        try {
            prefix = PropertyUtil.getPropertyValue(REDIS_KEY_PREFIX, "");
        } catch (PropertyUtil.PropertyUtilException e) {
            // PropertyUtil not initialised in some tests/contexts; default to no prefix
            prefix = "";
        }
        if (prefix == null || prefix.isBlank()) {
            return key;
        }
        return prefix + ":" + key;
    }
}
