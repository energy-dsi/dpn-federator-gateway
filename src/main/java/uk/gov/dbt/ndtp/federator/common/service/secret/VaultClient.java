package uk.gov.dbt.ndtp.federator.common.service.secret;

import com.bettercloud.vault.SslConfig;
import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.response.LogicalResponse;

import java.io.FileInputStream;
import java.security.KeyStore;

public class VaultClient {

    private static final String KEYSTORE_TYPE_JKS = "JKS";
    private static final String PKI_MOUNT = "pki-client";

    private final Vault vault;

    public VaultClient(String vaultAddr, String token, String trustStorePath, String trustStorePassword) throws Exception {

        // Load truststore manually
        KeyStore trustStore = KeyStore.getInstance(KEYSTORE_TYPE_JKS);

        try (FileInputStream fis = new FileInputStream(trustStorePath)) {
            trustStore.load(fis, trustStorePassword.toCharArray());
        } catch (Exception e) {
            trustStore = null;
        }

        // Configure SSL for Vault
        SslConfig sslConfig = null;
        if (trustStore != null) {
            sslConfig = new SslConfig()
                    .trustStore(trustStore)
                    .build();
        }

        VaultConfig config = new VaultConfig()
                .address(vaultAddr)
                .token(token)
                .sslConfig(sslConfig)
                .build();

        this.vault = new Vault(config);
    }

    public String getSecret(String path, String key) {
        try {
            // normalize path safely
            String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
            String fullPath = PKI_MOUNT + "/" + normalizedPath;
//            System.out.println("FINAL VAULT PATH = " + fullPath);
            LogicalResponse response = vault.logical().read(fullPath);

            return response.getData().get(key);
        } catch (Exception e) {
            throw new RuntimeException("Vault read failed", e);
        }
    }
}