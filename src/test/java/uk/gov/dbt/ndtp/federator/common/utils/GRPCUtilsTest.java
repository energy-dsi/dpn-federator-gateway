/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.grpc.ChannelCredentials;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import uk.gov.dbt.ndtp.federator.common.service.idp.*;

class GRPCUtilsTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PropertyUtil.clear();
    }

    @AfterEach
    void tearDown() {
        PropertyUtil.clear();
    }

    @Test
    void testCalculateSha256Checksum_ByteArray() throws Exception {
        byte[] data = "test data".getBytes();
        String checksum = GRPCUtils.calculateSha256Checksum(data);
        assertNotNull(checksum);
        assertEquals(64, checksum.length());

        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        String expected = GRPCUtils.bytesToHex(md.digest(data));
        assertEquals(expected, checksum);
    }

    @Test
    void testCalculateSha256Checksum_NullByteArray() {
        assertNull(GRPCUtils.calculateSha256Checksum((byte[]) null));
    }

    @Test
    void testCalculateSha256Checksum_Path() throws Exception {
        Path file = tempDir.resolve("test.txt");
        byte[] data = "test data".getBytes();
        Files.write(file, data);
        String checksum = GRPCUtils.calculateSha256Checksum(file);

        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        String expected = GRPCUtils.bytesToHex(md.digest(data));
        assertEquals(expected, checksum);
    }

    @Test
    void testCalculateSha256Checksum_NullPath() {
        assertNull(GRPCUtils.calculateSha256Checksum((Path) null));
    }

    @Test
    void testBytesToHex() {
        byte[] bytes = new byte[] {0x00, 0x01, 0x0f, (byte) 0xff};
        assertEquals("00010fff", GRPCUtils.bytesToHex(bytes));
    }

    /**
     * Writes a common.configuration properties file in tempDir, points
     * PropertyUtil.COMMON_CONFIG_PROPERTIES at it, and returns the file.
     */
    private File writeCommonConfig(String fileName, Properties props) throws Exception {
        File commonConfig = tempDir.resolve(fileName).toFile();
        try (FileOutputStream out = new FileOutputStream(commonConfig)) {
            props.store(out, null);
        }
        PropertyUtil.init("test.properties"); // Just to have it initialized
        PropertyUtil.getInstance()
                .properties
                .setProperty(GRPCUtils.COMMON_CONFIG_PROPERTIES, commonConfig.getAbsolutePath());
        return commonConfig;
    }

    @Test
    void testCreateIdpTokenService_ClientSecret() throws Exception {
        Properties props = new Properties();
        props.setProperty("idp.mtls.enabled", "false");
        writeCommonConfig("common.properties", props);

        try (MockedStatic<HttpClientFactoryUtils> factoryMock = mockStatic(HttpClientFactoryUtils.class)) {
            factoryMock
                    .when(() -> HttpClientFactoryUtils.createHttpClient(any()))
                    .thenReturn(mock(java.net.http.HttpClient.class));

            IdpTokenService service = GRPCUtils.createIdpTokenService();
            assertTrue(service instanceof IdpTokenServiceClientSecretImpl);
        }
    }

    @Test
    void testCreateIdpTokenService_Mtls() throws Exception {
        Properties props = new Properties();
        props.setProperty("idp.mtls.enabled", "true");
        writeCommonConfig("common_mtls.properties", props);

        // Mocking HttpClientFactoryUtils because it tries to create a real client with MTLS
        try (MockedStatic<HttpClientFactoryUtils> factoryMock = mockStatic(HttpClientFactoryUtils.class)) {
            factoryMock
                    .when(() -> HttpClientFactoryUtils.createHttpClientWithMtls(any()))
                    .thenReturn(mock(java.net.http.HttpClient.class));

            IdpTokenService service = GRPCUtils.createIdpTokenService();
            assertTrue(service instanceof IdpTokenServiceMtlsImpl);
        }
    }

    @Test
    void testCreateIdpTokenService_PrivateKeyJwt_ExplicitMode() throws Exception {
        Properties props = new Properties();
        props.setProperty("idp.auth.mode", "private_key_jwt");
        props.setProperty("idp.token.url", "https://keycloak.example.com/realms/test/protocol/openid-connect/token");
        props.setProperty("idp.jwks.url", "https://keycloak.example.com/realms/test/protocol/openid-connect/certs");
        props.setProperty("idp.client.id", "test-client");
        props.setProperty("idp.jwt.algorithm", "RS256");

        // Build a real PKCS12 keystore fixture so the service constructor
        // (which opens and parses the keystore) succeeds.
        KeystoreTestFixture fixture = KeystoreTestFixture.create(tempDir, "federator");
        props.setProperty("idp.keystore.path", fixture.keystorePath().toString());
        props.setProperty("idp.keystore.password", fixture.password());
        props.setProperty("idp.jwt.key.alias", fixture.alias());

        writeCommonConfig("common_privatejwt.properties", props);

        // NOTE: GRPCUtils.createIdpTokenService()'s private_key_jwt branch intentionally calls
        // createHttpClientWithMtls(...) (not the plain createHttpClient(...)) - see the comment
        // in that method: "private key JWT also require mTLS to be performed at edge layer".
        // This test mocks/asserts that actual call rather than the originally-intended
        // mTLS-free one, to match the current, deliberate production behaviour.
        try (MockedStatic<HttpClientFactoryUtils> factoryMock = mockStatic(HttpClientFactoryUtils.class)) {
            factoryMock
                    .when(() -> HttpClientFactoryUtils.createHttpClientWithMtls(any()))
                    .thenReturn(mock(java.net.http.HttpClient.class));

            IdpTokenService service = GRPCUtils.createIdpTokenService();
            assertTrue(service instanceof IdpTokenServicePrivateJwtImpl);

            // private_key_jwt mode performs mTLS at the edge layer; createHttpClientWithMtls IS invoked
            factoryMock.verify(() -> HttpClientFactoryUtils.createHttpClientWithMtls(any()));
        }
    }

    @Test
    void testCreateIdpTokenService_PrivateKeyJwt_CaseInsensitive() throws Exception {
        Properties props = new Properties();
        props.setProperty("idp.auth.mode", "Private_Key_JWT");
        props.setProperty("idp.token.url", "https://keycloak.example.com/realms/test/protocol/openid-connect/token");
        props.setProperty("idp.jwks.url", "https://keycloak.example.com/realms/test/protocol/openid-connect/certs");
        props.setProperty("idp.client.id", "test-client");

        KeystoreTestFixture fixture = KeystoreTestFixture.create(tempDir, "federator");
        props.setProperty("idp.keystore.path", fixture.keystorePath().toString());
        props.setProperty("idp.keystore.password", fixture.password());
        props.setProperty("idp.jwt.key.alias", fixture.alias());

        writeCommonConfig("common_privatejwt_case.properties", props);

        try (MockedStatic<HttpClientFactoryUtils> factoryMock = mockStatic(HttpClientFactoryUtils.class)) {
            factoryMock
                    .when(() -> HttpClientFactoryUtils.createHttpClient(any()))
                    .thenReturn(mock(java.net.http.HttpClient.class));

            IdpTokenService service = GRPCUtils.createIdpTokenService();
            assertTrue(service instanceof IdpTokenServicePrivateJwtImpl);
        }
    }

    @Test
    void testCreateIdpTokenService_UnknownAuthMode_DefaultsToClientSecret() throws Exception {
        Properties props = new Properties();
        props.setProperty("idp.auth.mode", "totally_unknown_mode");
        writeCommonConfig("common_unknown.properties", props);

        try (MockedStatic<HttpClientFactoryUtils> factoryMock = mockStatic(HttpClientFactoryUtils.class)) {
            factoryMock
                    .when(() -> HttpClientFactoryUtils.createHttpClient(any()))
                    .thenReturn(mock(java.net.http.HttpClient.class));

            IdpTokenService service = GRPCUtils.createIdpTokenService();
            assertTrue(service instanceof IdpTokenServiceClientSecretImpl);
        }
    }

    @Test
    void testCreateIdpTokenService_NoModeOrLegacyFlag_DefaultsToClientSecret() throws Exception {
        // Neither idp.auth.mode nor idp.mtls.enabled set at all.
        Properties props = new Properties();
        writeCommonConfig("common_empty.properties", props);

        try (MockedStatic<HttpClientFactoryUtils> factoryMock = mockStatic(HttpClientFactoryUtils.class)) {
            factoryMock
                    .when(() -> HttpClientFactoryUtils.createHttpClient(any()))
                    .thenReturn(mock(java.net.http.HttpClient.class));

            IdpTokenService service = GRPCUtils.createIdpTokenService();
            assertTrue(service instanceof IdpTokenServiceClientSecretImpl);
        }
    }

    @Test
    void testGenerateChannelCredentials() {
        PropertyUtil.init("test.properties");
        PropertyUtil.getInstance().properties.setProperty("client.p12FilePath", "nonexistent.p12");
        PropertyUtil.getInstance().properties.setProperty("client.p12Password", "pass");
        PropertyUtil.getInstance().properties.setProperty("client.truststoreFilePath", "nonexistent.jks");
        PropertyUtil.getInstance().properties.setProperty("client.truststorePassword", "pass");

        try (MockedStatic<SSLUtils> sslMock = mockStatic(SSLUtils.class)) {
            sslMock.when(() -> SSLUtils.createKeyManagerFromP12(anyString(), anyString()))
                    .thenReturn(new javax.net.ssl.KeyManager[0]);
            sslMock.when(() -> SSLUtils.createTrustManager(anyString(), anyString()))
                    .thenReturn(new javax.net.ssl.TrustManager[0]);

            ChannelCredentials creds = GRPCUtils.generateChannelCredentials();
            assertNotNull(creds);
        }
    }

    @Test
    void testConstructorIsPrivate() throws Exception {
        java.lang.reflect.Constructor<GRPCUtils> constructor = GRPCUtils.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, constructor::newInstance);
    }
}