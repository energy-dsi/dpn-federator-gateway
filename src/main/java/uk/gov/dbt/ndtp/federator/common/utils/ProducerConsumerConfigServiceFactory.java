// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced,
// and maintained by the National Digital Twin Programme.

/**
 * Factory class for creating and managing a singleton instance of ProducerConsumerConfigService.
 */
package uk.gov.dbt.ndtp.federator.common.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.Properties;
import uk.gov.dbt.ndtp.federator.common.management.ManagementNodeDataHandler;
import uk.gov.dbt.ndtp.federator.common.service.config.ProducerConfigService;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.service.secret.SecretProvider;
import uk.gov.dbt.ndtp.federator.common.storage.InMemoryConfigurationStore;

import static uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil.ENV_VAULT_TOKEN;
import static uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil.VAULT_URI;

public class ProducerConsumerConfigServiceFactory {
    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";
    private static ProducerConfigService producerConfigService;

    private ProducerConsumerConfigServiceFactory() {}

    /**
     * Returns the singleton instance of ProducerConsumerConfigService.
     * If the instance does not exist, it is created in a thread-safe manner.
     *
     * @return Singleton instance of ProducerConsumerConfigService
     */
    public static ProducerConfigService getProducerConfigService() {
        if (producerConfigService == null) {
            synchronized (ProducerConsumerConfigServiceFactory.class) {
                ObjectMapper mapper = ObjectMapperUtil.getInstance();
                Properties properties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);
                String vaultUri = properties.getProperty(VAULT_URI);

                // Get token from ENV
                String vaultToken = System.getenv(ENV_VAULT_TOKEN);

                SecretProvider vaultSecretProvider = PropertyUtil.createVaultSecretProvider(vaultUri, vaultToken);
                PropertyUtil.overrideWithSecrets(properties, vaultSecretProvider);

                IdpTokenService tokenService = GRPCUtils.createIdpTokenService();
                HttpClient httpClient = HttpClientFactoryUtils.createHttpClientWithMtls(properties);
                var managementNodeDataHandler = new ManagementNodeDataHandler(httpClient, mapper, tokenService);
                InMemoryConfigurationStore store = InMemoryConfigurationStore.getInstance();
                producerConfigService = new ProducerConfigService(managementNodeDataHandler, store);
            }
        }
        return producerConfigService;
    }
}
