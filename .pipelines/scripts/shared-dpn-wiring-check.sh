#!/usr/bin/env bash
# =============================================================================
# Shared DPN wiring check
#
# Verifies that ONE rendered shared DPN instance (helm template output of a
# certificate-manager, federator server/client or REST federator release) is
# wired only to its OWN Vault and credentials — never the primary's or another
# instance's:
#   1. every Vault URI         -> https://dpn-vault-https-<NN>.<ns>...:8200
#   2. every Vault secret ref  -> K8s Secret certificate-manager-<NN>-secrets
#   3. every Key Vault object  -> VAULT-APPROLE-SECRET-ID-<NN> / VAULT-TOKEN-<NN>
#   4. one DSM client id      (optionally == --expect-client-id)
#   5. one AppRole role id    (optionally == --expect-role-id)
#
# Identical copy kept in dpn-federator-certificate-manager, dpn-federator-gateway
# and dpn-rest-federator-gateway (.pipelines/scripts/).
#
# Usage:
#   shared-dpn-wiring-check.sh --rendered <file> --instance <NN> --label <name> \
#     [--expect-client-id <id>] [--expect-role-id <id>]
# Exit 1 (with ##[error] lines) on any mismatch.
# =============================================================================
set -euo pipefail

RENDERED="" N="" LABEL="instance" EXP_CLIENT="" EXP_ROLE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --rendered)         RENDERED="$2"; shift 2 ;;
    --instance)         N="$2"; shift 2 ;;
    --label)            LABEL="$2"; shift 2 ;;
    --expect-client-id) EXP_CLIENT="$2"; shift 2 ;;
    --expect-role-id)   EXP_ROLE="$2"; shift 2 ;;
    *) echo "##[error]unknown argument: $1"; exit 2 ;;
  esac
done
if [ -z "$RENDERED" ] || [ ! -s "$RENDERED" ] || [ -z "$N" ]; then
  echo "##[error]usage: $0 --rendered <file> --instance <NN> --label <name> [--expect-client-id ID] [--expect-role-id ID]"
  exit 2
fi

FAIL=0
err() { echo "##[error][$LABEL $N] $*"; FAIL=1; }
ok()  { echo "  [OK] [$LABEL $N] $*"; }

# Comment lines in templates/values mention primary names on purpose; ignore them.
BODY=$(grep -vE '^[[:space:]]*#' "$RENDERED" || true)

# 1. Vault URIs
VAULT_HOST="dpn-vault-https-$N"
URIS=$(printf '%s\n' "$BODY" | grep -oE 'https://[A-Za-z0-9.-]+:8200' | sort -u || true)
if [ -z "$URIS" ]; then
  err "no Vault URI rendered"
else
  for u in $URIS; do
    case "$u" in
      "https://$VAULT_HOST."*|"https://$VAULT_HOST:"*) ok "Vault URI $u" ;;
      *) err "points at the WRONG Vault: $u (expected https://$VAULT_HOST.<namespace>.svc.cluster.local:8200)" ;;
    esac
  done
fi

# 2. Kubernetes Secret holding the Vault credential
SECRETS=$(printf '%s\n' "$BODY" | grep -oE 'certificate-manager(-[0-9]+)?-secrets' | sort -u || true)
if [ -z "$SECRETS" ]; then
  err "no certificate-manager-*-secrets reference rendered"
fi
for s in $SECRETS; do
  if [ "$s" = "certificate-manager-$N-secrets" ]; then ok "Vault credential Secret $s"
  else err "uses the WRONG Vault credential Secret: $s (expected certificate-manager-$N-secrets)"; fi
done

# 3. Key Vault objects (only rendered by certificate-manager's SecretProviderClass)
for o in $(printf '%s\n' "$BODY" | grep -oE 'VAULT-(APPROLE-SECRET-ID|TOKEN)(-[0-9]+)?' | sort -u || true); do
  case "$o" in
    *-"$N") ok "Key Vault object $o" ;;
    *) err "pulls the WRONG Key Vault object: $o (expected the -$N one)" ;;
  esac
done

# 4. DSM client id (certificate-manager OAUTH2_CLIENT_ID / federator+REST idp.client.id;
#    redis.idp.* and OTEL_* use the internal dpn-service-client and are excluded)
CLIENTS=$(printf '%s\n' "$BODY" \
  | sed -nE 's/^[[:space:]]*OAUTH2_CLIENT_ID:[[:space:]]*"?([^"[:space:]]+)"?.*/\1/p; s/^[[:space:]]*idp\.client\.id=([^[:space:]]+).*/\1/p' \
  | sort -u || true)
COUNT=$(printf '%s\n' "$CLIENTS" | grep -c . || true)
if [ "$COUNT" -ne 1 ]; then
  err "expected exactly one DSM client id, found $COUNT: $(echo $CLIENTS)"
elif [ -n "$EXP_CLIENT" ] && [ "$CLIENTS" != "$EXP_CLIENT" ]; then
  err "DSM client id $CLIENTS does not match $EXP_CLIENT (dpn-certificate-manager-$N)"
else
  ok "DSM client id $CLIENTS${EXP_CLIENT:+ (matches dpn-certificate-manager-$N)}"
fi

# 5. AppRole role id (certificate-manager VAULT_APPROLE_ROLE_ID / federator+REST VAULT_ROLE_ID)
ROLES=$( { printf '%s\n' "$BODY" | sed -nE 's/^[[:space:]]*VAULT_APPROLE_ROLE_ID:[[:space:]]*"?([^"[:space:]]+)"?.*/\1/p'
           printf '%s\n' "$BODY" | grep -A1 -E '^[[:space:]]*- name: VAULT_ROLE_ID$' | sed -nE 's/^[[:space:]]*value:[[:space:]]*"?([^"[:space:]]+)"?.*/\1/p'; } \
  | sort -u || true)
COUNT=$(printf '%s\n' "$ROLES" | grep -c . || true)
if [ "$COUNT" -ne 1 ]; then
  err "expected exactly one AppRole role id, found $COUNT: $(echo $ROLES)"
elif [ -n "$EXP_ROLE" ] && [ "$ROLES" != "$EXP_ROLE" ]; then
  err "AppRole role id $ROLES does not match $EXP_ROLE (the role id of dpn-vault-https-$N)"
else
  ok "AppRole role id $ROLES${EXP_ROLE:+ (matches dpn-vault-https-$N)}"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "##[error][$LABEL $N] wiring check FAILED — instance $N must use only dpn-vault-https-$N and its own credentials."
  exit 1
fi
echo "[$LABEL $N] wiring check passed — wired only to dpn-vault-https-$N."
