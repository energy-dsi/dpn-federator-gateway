package uk.gov.dbt.ndtp.federator.common.service.secret;

public class VaultSecretProvider implements SecretProvider {

    private final VaultClient client;

    public VaultSecretProvider(String uri, String token, String truststorePath, String truststorePassword) {
        try {
            this.client = new VaultClient(uri, token, truststorePath, truststorePassword);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isEnabled() {
        return client != null;
    }

    @Override
    public String getSecret(String path, String key) {
        return client.getSecret(path, key);
    }
}