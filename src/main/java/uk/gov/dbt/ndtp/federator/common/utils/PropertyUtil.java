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

import static uk.gov.dbt.ndtp.federator.FederatorServer.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.common.service.secret.NoopSecretProvider;
import uk.gov.dbt.ndtp.federator.common.service.secret.SecretProvider;
import uk.gov.dbt.ndtp.federator.common.service.secret.VaultAuthConfig;
import uk.gov.dbt.ndtp.federator.common.service.secret.VaultAuthMethod;
import uk.gov.dbt.ndtp.federator.common.service.secret.VaultSecretProvider;

/**
 * Utility class to load properties from a file or resource.
 * <p>
 *   The class is a singleton and should be initialised with the {@link #init(String)} or {@link #init(File)} methods.
 *   The properties can be accessed using the {@link #getPropertyValue(String)} method.
 *   The class will throw a {@link PropertyUtilException} if the properties are not initialised or if a property is missing.
 *   The class will also throw a {@link PropertyUtilException} if the properties are initialised twice.
 *   The class will also override any properties with the same key in the system properties.
 *   The class also provides methods to get properties as int, long, boolean, duration and file.
 *   The class also provides a method to clear the properties for testing purposes.
 * </p>
 */
public class PropertyUtil {

    public static final Logger LOGGER = LoggerFactory.getLogger("PropertyUtil");
    public static final String VAULT_URI = "vault.uri";
    public static final String VAULT_TRUSTSTORE_PATH = "vault.truststore.path";
    public static final String VAULT_TRUSTSTORE_PASSWORD = "vault.truststore.password";
    public static final String ENV_VAULT_TOKEN = "VAULT_TOKEN";
    public static final String ENV_VAULT_KEYSTORE_PASSWORD_PATH = "VAULT_KEYSTORE_PASSWORD_PATH";
    public static final String ENV_VAULT_TRUSTSTORE_PASSWORD_PATH = "VAULT_TRUSTSTORE_PASSWORD_PATH";

    /** Selects the Vault authentication method: {@code token} (default) or {@code approle}. */
    public static final String VAULT_AUTH_METHOD = "vault.auth.method";
    /** AppRole {@code role_id}, used when {@link #VAULT_AUTH_METHOD} is {@code approle}. */
    public static final String VAULT_APPROLE_ROLE_ID = "vault.approle.role-id";
    /** AppRole {@code secret_id}, used when {@link #VAULT_AUTH_METHOD} is {@code approle}. */
    public static final String VAULT_APPROLE_SECRET_ID = "vault.approle.secret-id";
    /**
     * Path to a file containing the AppRole {@code secret_id} (e.g. a mounted Kubernetes
     * Secret). Used in preference to {@link #VAULT_APPROLE_SECRET_ID} if both are set.
     */
    public static final String VAULT_APPROLE_SECRET_ID_PATH = "vault.approle.secret-id-path";
    /** Vault mount path for the AppRole auth method (default {@code approle}). */
    public static final String VAULT_APPROLE_MOUNT_PATH = "vault.approle.mount-path";
    /** Interval, in seconds, between AppRole token renewal attempts (default 300). */
    public static final String VAULT_APPROLE_RENEWAL_INTERVAL_SECONDS = "vault.approle.renewal-interval-seconds";
    /** Environment variable holding the AppRole {@code role_id}, takes precedence if set. */
    public static final String ENV_VAULT_ROLE_ID = "VAULT_ROLE_ID";
    /** Environment variable holding the AppRole {@code secret_id}, takes precedence if set. */
    public static final String ENV_VAULT_SECRET_ID = "VAULT_SECRET_ID";

    static SecretProvider providerOverrideForTest = null;
    static Map<String, String> testMappingsOverride = null;

    /**
     * Caches {@link SecretProvider} instances per distinct Vault configuration so that repeated
     * calls to {@link #createSecretProvider(Properties)} (e.g. once per properties file loaded)
     * do not each open a new Vault connection / AppRole login / renewal thread.
     */
    private static final Map<String, SecretProvider> SECRET_PROVIDER_CACHE = new ConcurrentHashMap<>();

    private static final String CLIENT_P12_PASSWORD = "client.p12Password";
    private static final String CLIENT_TRUSTSTORE_PASSWORD = "client.truststorePassword";
    private static final String SERVER_P12_PASSWORD = "server.p12Password";
    private static final String SERVER_TRUSTSTORE_PASSWORD = "server.truststorePassword";
    private static final String IDP_KEYSTORE_PASSWORD = "idp.keystore.password";
    private static final String IDP_TRUSTSTORE_PASSWORD = "idp.truststore.password";

    private static PropertyUtil instance;
    public final Properties properties;

    private PropertyUtil(InputStream inputStream) {
        properties = new Properties();
        try (inputStream) {
            properties.load(inputStream);
        } catch (Exception t) {
            throw new PropertyUtilException("Error loading properties from inputStream", t);
        }
        overrideSystemProperties(properties);

        Properties commonProperties = new Properties();

        String commonConfig = properties.getProperty(GRPCUtils.COMMON_CONFIG_PROPERTIES);

        if (commonConfig != null && !commonConfig.isBlank()) {
            try {
                commonProperties = getPropertiesFromFileName(commonConfig);
            } catch (Exception e) {
                LOGGER.warn("Skipping common configuration loading in this context: {}", e.getMessage());
            }
        }

        SecretProvider secretProvider = (providerOverrideForTest != null)
                ? providerOverrideForTest
                : createSecretProvider(commonProperties);
        overrideWithSecrets(properties, secretProvider);
    }

    public static SecretProvider createSecretProvider(Properties properties) {

        String vaultUri = properties.getProperty(VAULT_URI);

        if (vaultUri == null || vaultUri.isBlank()) {
            return new NoopSecretProvider();
        }

        VaultAuthMethod authMethod;
        VaultAuthConfig authConfig;
        try {
            authMethod = VaultAuthMethod.fromString(properties.getProperty(VAULT_AUTH_METHOD));
            authConfig = switch (authMethod) {
                case APPROLE -> buildAppRoleAuthConfig(properties);
                case TOKEN -> buildTokenAuthConfig();
            };
        } catch (Exception e) {
            LOGGER.error("Invalid Vault authentication configuration", e);
            return new NoopSecretProvider();
        }

        if (authConfig == null) {
            return new NoopSecretProvider();
        }

        String vaultTruststorePath = properties.getProperty(VAULT_TRUSTSTORE_PATH);
        String vaultTruststorePassword = properties.getProperty(VAULT_TRUSTSTORE_PASSWORD);

        String cacheKey = buildCacheKey(vaultUri, authConfig, vaultTruststorePath);

        return SECRET_PROVIDER_CACHE.computeIfAbsent(cacheKey, key -> {
            try {
                return new VaultSecretProvider(vaultUri, authConfig, vaultTruststorePath, vaultTruststorePassword);
            } catch (Exception e) {
                LOGGER.error("Failed to create Vault secret provider for auth method {}", authMethod, e);
                return new NoopSecretProvider();
            }
        });
    }

    /**
     * Builds a {@link VaultAuthConfig} for {@link VaultAuthMethod#TOKEN}, reading the token from
     * the {@value #ENV_VAULT_TOKEN} environment variable.
     *
     * @return a token-based {@link VaultAuthConfig}, or {@code null} if {@value #ENV_VAULT_TOKEN} is unset
     */
    private static VaultAuthConfig buildTokenAuthConfig() {
        String vaultToken = System.getenv(ENV_VAULT_TOKEN);
        if (vaultToken == null || vaultToken.isBlank()) {
            return null;
        }
        return VaultAuthConfig.forToken(vaultToken);
    }

    /**
     * Builds a {@link VaultAuthConfig} for {@link VaultAuthMethod#APPROLE}. The {@code role_id}
     * and {@code secret_id} are resolved with the following precedence: environment variables
     * ({@value #ENV_VAULT_ROLE_ID} / {@value #ENV_VAULT_SECRET_ID}), then
     * {@value #VAULT_APPROLE_SECRET_ID_PATH} (for the secret_id), then the
     * {@value #VAULT_APPROLE_ROLE_ID} / {@value #VAULT_APPROLE_SECRET_ID} properties.
     *
     * @param properties the loaded application properties
     * @return an AppRole-based {@link VaultAuthConfig}, or {@code null} if not configured
     */
    private static VaultAuthConfig buildAppRoleAuthConfig(Properties properties) {
        String roleId = firstNonBlank(System.getenv(ENV_VAULT_ROLE_ID), properties.getProperty(VAULT_APPROLE_ROLE_ID));

        String secretId = firstNonBlank(
                System.getenv(ENV_VAULT_SECRET_ID),
                readSecretIdFromFile(properties.getProperty(VAULT_APPROLE_SECRET_ID_PATH)),
                properties.getProperty(VAULT_APPROLE_SECRET_ID));

        if (roleId == null || roleId.isBlank() || secretId == null || secretId.isBlank()) {
            LOGGER.warn(
                    "vault.auth.method is 'approle' but '{}'/'{}' (or equivalent env vars/secret file) are not "
                            + "configured; Vault secret provider will be disabled",
                    VAULT_APPROLE_ROLE_ID,
                    VAULT_APPROLE_SECRET_ID);
            return null;
        }

        String mountPath = properties.getProperty(VAULT_APPROLE_MOUNT_PATH, VaultAuthConfig.DEFAULT_APPROLE_MOUNT_PATH);
        long renewalIntervalSeconds = getLongOrDefault(
                properties.getProperty(VAULT_APPROLE_RENEWAL_INTERVAL_SECONDS),
                VaultAuthConfig.DEFAULT_RENEWAL_INTERVAL_SECONDS);

        return VaultAuthConfig.forAppRole(roleId, secretId, mountPath, renewalIntervalSeconds);
    }

    /**
     * Reads and trims the contents of the file at {@code path}, supporting a mounted Kubernetes
     * Secret file for the AppRole {@code secret_id}.
     *
     * @param path the file path, may be {@code null} or blank
     * @return the trimmed file contents, or {@code null} if {@code path} is blank or unreadable
     */
    private static String readSecretIdFromFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Files.readString(new File(path).toPath()).trim();
        } catch (Exception e) {
            LOGGER.warn("Could not read Vault AppRole secret_id from file '{}': {}", path, e.getMessage());
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static long getLongOrDefault(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid numeric value '{}', using default {}", value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Builds a stable cache key for {@link #SECRET_PROVIDER_CACHE} that uniquely identifies a
     * Vault connection configuration, without including the raw token/secret_id value itself.
     */
    private static String buildCacheKey(String vaultUri, VaultAuthConfig authConfig, String truststorePath) {
        String authIdentity = authConfig.isAppRole()
                ? "approle:" + authConfig.getApproleMountPath() + ":" + authConfig.getRoleId()
                : "token:" + Integer.toHexString(authConfig.getToken().hashCode());
        return vaultUri + "|" + authIdentity + "|" + truststorePath;
    }

    /**
     * Clears the cached {@link SecretProvider} instances. For testing purposes only.
     */
    public static void clearSecretProviderCache() {
        SECRET_PROVIDER_CACHE.clear();
    }

    public static boolean initializeProperties() {
        try {
            // Check if already initialized by trying to get instance
            PropertyUtil.getInstance();
            return true;
        } catch (Exception e) {
            LOGGER.debug("Failed to get PropertyUtil instance", e);
            // Not initialized, so initialize now
            final String envProps = System.getenv(ENV_SERVER_PROPS);
            if (envProps != null) {
                final File file = new File(envProps);
                if (file.exists()) {
                    try {
                        PropertyUtil.init(file);
                        return true;
                    } catch (Exception ex) {
                        LOGGER.error("Failed to load properties from: {}", file.getPath(), ex);
                        return false;
                    }
                }
                LOGGER.warn("File specified by {} not found: {}", ENV_SERVER_PROPS, envProps);
            }

            // Try to load from classpath as last resort
            try {
                PropertyUtil.init(SERVER_PROPERTIES);
                return true;
            } catch (Exception exception) {
                LOGGER.error(
                        "Failed to load {} from classpath. " + "Ensure file exists in resources directory or "
                                + "set {} to valid file path",
                        SERVER_PROPERTIES,
                        ENV_SERVER_PROPS,
                        exception);
                return false;
            }
        }
    }

    public static void init(String resourceName) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (null == instance) {
            try (InputStream resourceStream = loader.getResourceAsStream(resourceName)) {
                LOGGER.info("Loading Properties from resource: '{}'", resourceName);
                instance = new PropertyUtil(resourceStream);
            } catch (Exception t) {
                throw new PropertyUtilException("Error loading properties from resource", t);
            }
        } else {
            throw new PropertyUtilException("Cannot initialise Property Util twice");
        }
    }

    public static void init(File file) {
        if (null == instance) {
            try (InputStream inputStream = new FileInputStream(file)) {
                LOGGER.info("Loading Properties from file: '{}'", file.getPath());
                instance = new PropertyUtil(inputStream);
            } catch (Exception t) {
                throw new PropertyUtilException("Error loading properties from file", t);
            }
        } else {
            throw new PropertyUtilException("Cannot initialise Property Util twice");
        }
    }

    public static PropertyUtil getInstance() {
        if (null == instance) {
            throw new PropertyUtilException("Property Util not properly initialised");
        }
        return instance;
    }

    public static String getPropertyValue(String key) {
        return getInstance().getValue(key);
    }

    public static String getPropertyValue(String key, String defaultValue) {
        return getInstance().getValue(key, defaultValue);
    }

    public static int getPropertyIntValue(String key) {
        return Integer.parseInt(getPropertyValue(key));
    }

    public static int getPropertyIntValue(String key, String defaultValue) {
        return Integer.parseInt(getPropertyValue(key, defaultValue));
    }

    public static long getPropertyLongValue(String key) {
        return Long.parseLong(getPropertyValue(key));
    }

    public static long getPropertyLongValue(String key, String defaultValue) {
        return Long.parseLong(getPropertyValue(key, defaultValue));
    }

    public static Duration getPropertyDurationValue(String key) {
        return Duration.parse(getPropertyValue(key));
    }

    public static Duration getPropertyDurationValue(String key, String defaultValue) {
        return Duration.parse(getPropertyValue(key, defaultValue));
    }

    public static boolean getPropertyBooleanValue(String key) {
        return Boolean.parseBoolean(getPropertyValue(key));
    }

    public static boolean getPropertyBooleanValue(String key, String defaultValue) {
        return Boolean.parseBoolean(getPropertyValue(key, defaultValue));
    }

    public static File getPropertyFileValue(String key) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            return new File(loader.getResource(getPropertyValue(key)).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new PropertyUtilException(e);
        }
    }
    /**
     * Absolute destination → used as-is. Relative destination (a bare convention name like
     * jsonschema-org-product.nt) → resolved against the local root so files land in the
     * federator-files folder, not the client's working directory.
     * Root order: client.files.local.dir → client.files.temp.dir → ${java.io.tmpdir}/federator-files.
     */
    public static Path resolveTarget(String destination) {
        Path dest = Path.of(destination);
        if (dest.isAbsolute()) {
            return dest.normalize();
        }
        return localRoot().resolve(dest).toAbsolutePath().normalize();
    }

    private static Path localRoot() {
        String dir = safeProp("client.files.local.dir");
        if (dir.isBlank()) {
            dir = safeProp("client.files.temp.dir");
        }
        if (!dir.isBlank()) {
            return Path.of(dir);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "federator-files");
    }

    /**
     * Loads a properties file specified by the property key. The value of the property can be either an absolute
     * file path or a classpath resource. The method will first attempt to load the properties from the absolute
     * file path, and if that fails, it will attempt to load it from the classpath resource.
     *
     * @param filePathKey the property key that contains the file path or classpath resource
     * @return the loaded properties
     * @throws PropertyUtilException if the properties cannot be loaded from either location
     */
    public static Properties getPropertiesFromFilePath(String filePathKey) {
        String configured = getPropertyValue(filePathKey);
        Properties nestedProperties = new Properties();

        // First try absolute path
        File absoluteFile = new File(configured);
        if (absoluteFile.isFile() && absoluteFile.canRead()) {
            try (FileInputStream fis = new FileInputStream(absoluteFile)) {
                nestedProperties.load(fis);
                return nestedProperties;
            } catch (Exception e) {
                LOGGER.warn(
                        "Failed reading properties from absolute path '{}', attempting classpath resource",
                        absoluteFile.getAbsolutePath(),
                        e);
            }
        } else {
            LOGGER.info("Absolute path '{}' not valid, attempting classpath resource", absoluteFile.getPath());
        }

        // Fallback: try classpath resource
        try {
            File resourceFile = getPropertyFileValue(filePathKey);
            try (FileInputStream fis = new FileInputStream(resourceFile)) {
                nestedProperties.load(fis);
                return nestedProperties;
            }
        } catch (Exception e) {
            throw new PropertyUtilException(
                    "Failed to load properties for key '" + filePathKey + "' from absolute path '"
                            + absoluteFile.getPath() + "' or classpath resource '" + configured + "'",
                    e);
        }
    }

    public static File getPropertyFileNameValue(String fileName) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            return new File(loader.getResource(fileName).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new PropertyUtilException(e);
        }
    }

    /**
     * Loads a properties file specified by the file name. The method will first attempt to load the properties from the absolute
     * file path, and if that fails, it will attempt to load it from the classpath resource.
     *
     * @param fileName the file path or classpath resource
     * @return the loaded properties
     * @throws PropertyUtilException if the properties cannot be loaded from either location
     */
    public static Properties getPropertiesFromFileName(String fileName) {
        Properties nestedProperties = new Properties();

        // First try absolute path
        File absoluteFile = new File(fileName);
        if (absoluteFile.isFile() && absoluteFile.canRead()) {
            try (FileInputStream fis = new FileInputStream(absoluteFile)) {
                nestedProperties.load(fis);
                return nestedProperties;
            } catch (Exception e) {
                LOGGER.warn(
                        "Failed reading properties from absolute path '{}', attempting classpath resource",
                        absoluteFile.getAbsolutePath(),
                        e);
            }
        } else {
            LOGGER.info("Absolute path '{}' not valid, attempting classpath resource", absoluteFile.getPath());
        }

        // Fallback: try classpath resource
        try {
            File resourceFile = getPropertyFileNameValue(fileName);
            try (FileInputStream fis = new FileInputStream(resourceFile)) {
                nestedProperties.load(fis);
                return nestedProperties;
            }
        } catch (Exception e) {
            throw new PropertyUtilException(
                    "Failed to load properties for file '" + fileName + "' from absolute path '"
                            + absoluteFile.getPath() + "' or classpath resource '" + fileName + "'",
                    e);
        }
    }

    public static Properties getByPrefix(String prefix) {
        if (prefix == null) {
            throw new PropertyUtilException("The prefix to search for must not be null");
        }
        Properties props = getInstance().properties;
        Properties found = new Properties();
        props.forEach((key, value) -> {
            if (key instanceof String name && name.startsWith(prefix)) {
                found.put(name, value);
            }
        });
        return found;
    }

    /**
     * For testing purposes
     */
    public static void clear() {
        instance = null;
    }

    public String getValue(String key) {
        String result = properties.getProperty(key);
        if (null == result) {
            throw new PropertyUtilException(String.format("Missing property: '%s'", key));
        }
        return result;
    }

    public String getValue(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public static void overrideSystemProperties(Properties properties) {
        String keyset = properties.keySet().toString();
        LOGGER.info("Properties KeySet from File - [{}]", keyset);
        for (Object key : properties.keySet()) {
            String override = System.getProperty((String) key);
            if (override != null) {
                LOGGER.info("Overriding file property with system property - '{}'", key);
                properties.put(key, override);
            } else {
                LOGGER.trace("Using File Property - '{}'", key);
            }
        }
    }

    public static void overrideWithSecrets(Properties properties, SecretProvider provider) {

        if (!provider.isEnabled()) {
            LOGGER.info("Secret provider not configured or parameter missing, skipping secret override");
            return;
        }

        LOGGER.info("Secret provider enabled, applying secret overrides");

        Map<String, String> mappings =
                (testMappingsOverride != null)
                        ? testMappingsOverride
                        : VaultMappings.getMappings();

        for (Map.Entry<String, String> entry : mappings.entrySet()) {

            String propertyKey = entry.getKey();
            String mapping = entry.getValue();
            if (mapping == null || mapping.isBlank()) {
                continue;  // skip if env not set
            }

            if (!properties.containsKey(propertyKey)) continue;

            String[] parts = mapping.split("#");
            String path = parts[0];
            String key = parts[1];

            try {
                String secret = provider.getSecret(path, key);

                if (secret != null) {
                    properties.put(propertyKey, secret);
                    LOGGER.info("Overrode '{}' from Vault", propertyKey);
                }

            } catch (Exception e) {
                LOGGER.error("Failed to load secret for {}", propertyKey, e);
            }
        }
    }


    private static String safeProp(String key) {
        try {
            String v = PropertyUtil.getPropertyValue(key, "");
            return v == null ? "" : v.trim();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    public class VaultMappings {

        public static Map<String, String> getMappings() {
            return Map.of(
                    CLIENT_P12_PASSWORD, getEnvOrDefault(ENV_VAULT_KEYSTORE_PASSWORD_PATH),
                    CLIENT_TRUSTSTORE_PASSWORD, getEnvOrDefault(ENV_VAULT_TRUSTSTORE_PASSWORD_PATH),
                    SERVER_P12_PASSWORD, getEnvOrDefault(ENV_VAULT_KEYSTORE_PASSWORD_PATH),
                    SERVER_TRUSTSTORE_PASSWORD, getEnvOrDefault(ENV_VAULT_TRUSTSTORE_PASSWORD_PATH),
                    IDP_KEYSTORE_PASSWORD, getEnvOrDefault(ENV_VAULT_KEYSTORE_PASSWORD_PATH),
                    IDP_TRUSTSTORE_PASSWORD, getEnvOrDefault(ENV_VAULT_TRUSTSTORE_PASSWORD_PATH)
            );
        }

        private static String getEnvOrDefault(String key) {
            String value = System.getenv(key);
            return value != null ? value : "";
        }
    }

    public static class PropertyUtilException extends RuntimeException {
        public PropertyUtilException(String message) {
            super(message);
        }

        public PropertyUtilException(Throwable cause) {
            super(cause);
        }

        public PropertyUtilException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
