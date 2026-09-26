# Vault AppRole authentication - Federator

This adds a second HashiCorp Vault authentication method, **AppRole**, alongside the existing
**token**-based authentication (e.g. a root token via `VAULT_TOKEN`). The method is selected by
configuration and defaults to `token`, so existing deployments are unaffected.

## What changed

### New classes (`uk.gov.dbt.ndtp.federator.common.service.secret`)

- **`VaultAuthMethod`** - enum `TOKEN` / `APPROLE`, parsed via `fromString(...)`
  (defaults to `TOKEN` when unset).
- **`VaultAuthConfig`** - immutable description of how to authenticate
  (`forToken(token)` / `forAppRole(roleId, secretId, mountPath, renewalIntervalSeconds)`).
- **`VaultTokenRenewalManager`** - for AppRole only, runs a daemon background thread that
  periodically renews the Vault client token (`auth/token/renew-self`) and, if renewal fails,
  performs a fresh AppRole login and updates the shared `VaultConfig` with the new token. No-op
  for token auth.

### Modified classes

- **`VaultClient`** - new constructor `VaultClient(vaultAddr, VaultAuthConfig, trustStorePath, trustStorePassword)`.
  The existing `VaultClient(vaultAddr, token, trustStorePath, trustStorePassword)` constructor is
  retained and delegates to the new one with `VaultAuthConfig.forToken(token)`. For AppRole, the
  constructor performs `auth/approle/login` and starts the renewal manager. `getSecret(path, key)`
  is unchanged.
- **`VaultSecretProvider`** - new constructor overload accepting `VaultAuthConfig`; existing
  `(uri, token, truststorePath, truststorePassword)` constructor retained for backward
  compatibility.
- **`PropertyUtil`** - `createSecretProvider(Properties)` now:
  1. Returns a `NoopSecretProvider` if `vault.uri` is not set (unchanged).
  2. Resolves `vault.auth.method` (default `token`).
  3. For `token`, reads `VAULT_TOKEN` from the environment as before.
  4. For `approle`, resolves `role_id`/`secret_id` (see below) and builds a `VaultAuthConfig`.
  5. Caches the resulting `SecretProvider` per distinct Vault configuration, so repeated calls
     to `createSecretProvider` (once per properties file loaded) reuse the same Vault
     connection/AppRole session rather than each opening a new one.
  6. Falls back to `NoopSecretProvider` on any configuration or connection error.

## New configuration properties

| Property | Environment variable | Description | Default |
|---|---|---|---|
| `vault.auth.method` | - | `token` or `approle` | `token` |
| `vault.approle.role-id` | `VAULT_ROLE_ID` (takes precedence) | AppRole `role_id` | - |
| `vault.approle.secret-id` | `VAULT_SECRET_ID` (takes precedence) | AppRole `secret_id` | - |
| `vault.approle.secret-id-path` | - | Path to a file containing the `secret_id` (e.g. a mounted Kubernetes Secret); used if `VAULT_SECRET_ID` is not set | - |
| `vault.approle.mount-path` | - | Vault mount path for the AppRole auth method | `approle` |
| `vault.approle.renewal-interval-seconds` | - | How often the renewal manager attempts `renew-self` | `300` |

`role_id`/`secret_id` resolution order: `VAULT_ROLE_ID`/`VAULT_SECRET_ID` env vars, then
`vault.approle.secret-id-path` (for `secret_id` only), then the `vault.approle.role-id` /
`vault.approle.secret-id` properties.

## Migration notes

- No action required for existing deployments: `vault.auth.method` defaults to `token`, and
  `VAULT_TOKEN` continues to be read exactly as before.
- To switch to AppRole, set `vault.auth.method=approle` plus the AppRole properties above. See
  `vault/setup-approle.sh` for the Vault-side setup and `vault/federator-policy.hcl` for the
  minimal policy required (`pki-client/*` read/list).

## Tests

- `VaultAuthMethodTest`, `VaultAuthConfigTest` - pure unit tests of the new value types.
- `VaultTokenRenewalManagerTest` - exercises the renew / re-login / both-fail scenarios using a
  mocked `Vault`/`Auth` and an injected `ScheduledExecutorService`.
- `PropertyUtilVaultAppRoleTest` - covers the new branches in `createSecretProvider`
  (missing URI, missing AppRole credentials, unreachable Vault with AppRole credentials,
  invalid `vault.auth.method`).
- Existing `VaultClientTest`, `VaultSecretProviderTest`, `PropertyUtilVaultOverrideTest` require
  **no changes** - the token-auth code path makes no network calls during construction, so the
  existing reflection-based mock injection (`vault` / `client` fields) continues to work.

> AppRole login (`auth/approle/login`) makes a real HTTP call to Vault during `VaultClient`
> construction, so it is best verified with an integration test against a Vault dev server
> (`vault server -dev`) rather than a unit test - none of the unit tests above attempt a live
> AppRole login.
