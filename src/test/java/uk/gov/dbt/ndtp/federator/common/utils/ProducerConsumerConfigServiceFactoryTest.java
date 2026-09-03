/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import uk.gov.dbt.ndtp.federator.common.service.config.ProducerConfigService;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;

class ProducerConsumerConfigServiceFactoryTest {

    @AfterEach
    void tearDown() throws Exception {
        // Prevent the singleton created by this test from leaking into other test classes
        // that run later in the same JVM (e.g. ConsumerVerificationServerInterceptorTest,
        // KafkaStreamServiceTest), which would otherwise bypass their own MockedStatic setups.
        // Reset is done via reflection here (test-only) so production code is left untouched.
        Field field = ProducerConsumerConfigServiceFactory.class.getDeclaredField("producerConfigService");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void getProducerConfigService_returnsSingleton() {
        try (MockedStatic<PropertyUtil> propMock = mockStatic(PropertyUtil.class);
                MockedStatic<GRPCUtils> grpcMock = mockStatic(GRPCUtils.class);
                MockedStatic<HttpClientFactoryUtils> httpMock = mockStatic(HttpClientFactoryUtils.class)) {

            Properties props = new Properties();
            props.setProperty("management.node.base.url", "http://localhost");
            propMock.when(() -> PropertyUtil.getPropertiesFromFilePath(anyString()))
                    .thenReturn(props);
            propMock.when(() -> PropertyUtil.getPropertyValue(eq("management.node.base.url"), anyString()))
                    .thenReturn("http://localhost");
            propMock.when(() -> PropertyUtil.getPropertyValue(eq("management.node.request.timeout"), anyString()))
                    .thenReturn("5");
            grpcMock.when(GRPCUtils::createIdpTokenService).thenReturn(mock(IdpTokenService.class));
            grpcMock.when(GRPCUtils::createRedisIdpTokenService).thenReturn(mock(IdpTokenService.class));
            httpMock.when(() -> HttpClientFactoryUtils.createHttpClientWithMtls(any()))
                    .thenReturn(mock(HttpClient.class));

            ProducerConfigService service1 = ProducerConsumerConfigServiceFactory.getProducerConfigService();
            ProducerConfigService service2 = ProducerConsumerConfigServiceFactory.getProducerConfigService();

            assertNotNull(service1);
            assertSame(service1, service2);
        }
    }
}
