#!/usr/bin/env bash
#
# Configures HashiCorp Vault AppRole authentication for the federator component.
#
# Prerequisites:
#   - VAULT_ADDR and VAULT_TOKEN (a token with sufficient privileges to manage auth
#     methods, policies and AppRole roles) are exported in the environment.
#   - The "pki-client" secrets engine referenced by federator-policy.hcl already exists.
#
# Usage:
#   ./setup-approle.sh
#
set -euo pipefail

ROLE_NAME="federator"
POLICY_NAME="federator-policy"
POLICY_FILE="$(dirname "$0")/federator-policy.hcl"

echo "Enabling AppRole auth method (no-op if already enabled)..."
vault auth enable approle 2>/dev/null || echo "  approle auth method already enabled"

echo "Writing policy '${POLICY_NAME}'..."
vault policy write "${POLICY_NAME}" "${POLICY_FILE}"

echo "Creating/updating AppRole role '${ROLE_NAME}'..."
vault write "auth/approle/role/${ROLE_NAME}" \
    token_policies="${POLICY_NAME}" \
    token_ttl=1h \
    token_max_ttl=4h \
    secret_id_ttl=0 \
    secret_id_num_uses=0

echo
echo "role_id (set as vault.approle.role-id / VAULT_ROLE_ID, not sensitive):"
vault read -field=role_id "auth/approle/role/${ROLE_NAME}/role-id"

echo
echo "secret_id (set as vault.approle.secret-id / VAULT_SECRET_ID, TREAT AS SENSITIVE):"
vault write -field=secret_id -f "auth/approle/role/${ROLE_NAME}/secret-id"

cat <<'EOF'

To configure the federator to use AppRole, set the following properties
(see common-configuration.properties) and/or environment variables:

  vault.auth.method=approle
  vault.approle.role-id=<role_id from above>
  vault.approle.mount-path=approle           # optional, defaults to "approle"
  vault.approle.renewal-interval-seconds=300 # optional, defaults to 300

  # secret_id should be supplied via environment/secret store rather than the
  # properties file:
  export VAULT_SECRET_ID=<secret_id from above>

  # Alternatively, mount the secret_id as a file (e.g. from a Kubernetes Secret)
  # and reference it:
  vault.approle.secret-id-path=/vault/secrets/secret_id

vault.auth.method defaults to "token" if unset, preserving existing
VAULT_TOKEN-based deployments.
EOF
