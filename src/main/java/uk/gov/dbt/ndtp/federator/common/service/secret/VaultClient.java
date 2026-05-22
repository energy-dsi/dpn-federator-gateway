package uk.gov.dbt.ndtp.federator.common.service.secret;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.response.LogicalResponse;

public class VaultClient {

    private final Vault vault;

    public VaultClient(String vaultAddr, String token) throws Exception {
        VaultConfig config = new VaultConfig()
                .address(vaultAddr)
                .token(token)
                .build();

        this.vault = new Vault(config);
    }

    public String getSecret(String path, String key) {
        try {
            // normalize path safely
            String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
            String fullPath = "pki-client/" + normalizedPath;
//            System.out.println("FINAL VAULT PATH = " + fullPath);
            LogicalResponse response = vault.logical().read(fullPath);

            return response.getData().get(key);
        } catch (Exception e) {
            throw new RuntimeException("Vault read failed", e);
        }
    }
}