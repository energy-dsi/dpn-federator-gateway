/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.common.service.idp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;
import uk.gov.dbt.ndtp.federator.common.utils.ResilienceSupport;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorTokenException;

class IdpTokenServiceClientSecretImplTest {

    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private MockedStatic<PropertyUtil> propertyUtilMockedStatic;
    private MockedStatic<RedisUtil> redisUtilMockedStatic;
    private RedisUtil redisUtil;
    private Properties properties;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        objectMapper = mock(ObjectMapper.class);
        propertyUtilMockedStatic = mockStatic(PropertyUtil.class);
        redisUtilMockedStatic = mockStatic(RedisUtil.class);
        redisUtil = mock(RedisUtil.class);
        redisUtilMockedStatic.when(RedisUtil::getInstance).thenReturn(redisUtil);
        when(redisUtil.getValue(anyString(), eq(String.class), eq(true))).thenReturn(null);

        properties = new Properties();
        properties.setProperty("idp.jwks.url", "http://localhost/jwks");
        properties.setProperty("idp.token.url", "http://localhost/token");
        properties.setProperty("idp.client.id", "test-client-id");
        properties.setProperty("idp.client.secret", "test-client-secret");
        propertyUtilMockedStatic
                .when(() -> PropertyUtil.getPropertiesFromFilePath(anyString()))
                .thenReturn(properties);
        propertyUtilMockedStatic
                .when(() -> PropertyUtil.getPropertyIntValue(anyString(), anyString()))
                .thenReturn(3);
        propertyUtilMockedStatic
                .when(() -> PropertyUtil.getPropertyDurationValue(anyString(), anyString()))
                .thenReturn(java.time.Duration.ofMinutes(1));
        propertyUtilMockedStatic
                .when(() -> PropertyUtil.getPropertyValue(anyString(), anyString()))
                .thenReturn("java.lang.RuntimeException");

        ResilienceSupport.clearForTests();
    }

    @AfterEach
    void tearDown() {
        propertyUtilMockedStatic.close();
        redisUtilMockedStatic.close();
    }

    @Test
    void fetchToken_success() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\": \"secret-token\", \"expires_in\": 3600}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of("access_token", "secret-token", "expires_in", 3600));

        IdpTokenServiceClientSecretImpl service =
                new IdpTokenServiceClientSecretImpl((Supplier<HttpClient>) () -> httpClient, objectMapper);
        String token = service.fetchToken("node-1");

        assertEquals("secret-token", token);
        verify(redisUtil).setValue("management_node_node-1_test-client-id_access_token", "secret-token", 3600L);
    }

    @Test
    void fetchToken_returnsCachedToken() {
        when(redisUtil.getValue(anyString(), eq(String.class), eq(true))).thenReturn("cached-token");

        IdpTokenServiceClientSecretImpl service =
                new IdpTokenServiceClientSecretImpl((Supplier<HttpClient>) () -> httpClient, objectMapper);
        String token = service.fetchToken("node-1");

        assertEquals("cached-token", token);
        verifyNoInteractions(httpClient);
    }

    @Test
    void fetchToken_throwsExceptionOnIdpError() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn("unauthorized");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        IdpTokenServiceClientSecretImpl service = new IdpTokenServiceClientSecretImpl(() -> httpClient, objectMapper);
        assertThrows(FederatorTokenException.class, () -> service.fetchToken("node-1"));
    }

    @Test
    void fetchToken_noArgs() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\": \"resilient-secret-token\", \"expires_in\": 3600}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of("access_token", "resilient-secret-token", "expires_in", 3600));

        IdpTokenServiceClientSecretImpl service = new IdpTokenServiceClientSecretImpl(() -> httpClient, objectMapper);
        String token = service.fetchToken();

        assertEquals("resilient-secret-token", token);
    }

    @Test
    void fetchToken_usesConfiguredPrefix() throws Exception {
        properties.setProperty("redis.idp.jwks.url", "http://localhost/redis-jwks");
        properties.setProperty("redis.idp.token.url", "http://localhost/redis-token");
        properties.setProperty("redis.idp.client.id", "redis-client-id");
        properties.setProperty("redis.idp.client.secret", "redis-client-secret");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\": \"redis-token-value\", \"expires_in\": 60}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of("access_token", "redis-token-value", "expires_in", 60));

        IdpTokenServiceClientSecretImpl service =
                new IdpTokenServiceClientSecretImpl(() -> httpClient, objectMapper, "redis.idp.");
        String token = service.fetchToken("node-1");

        assertEquals("redis-token-value", token);
        verify(redisUtil).setValue("management_node_node-1_redis-client-id_access_token", "redis-token-value", 60L);
    }
}
