# Vault ACL policy for the federator's AppRole identity.
#
# The federator's VaultClient (uk.gov.dbt.ndtp.federator.common.service.secret.VaultClient)
# reads secrets (e.g. keystore/truststore passwords) by prefixing the configured path with
# "pki-client/", e.g. VaultClient.getSecret("client/keystore", "password") reads
# "pki-client/client/keystore".

path "pki-client/*" {
  capabilities = ["read", "list"]
}
