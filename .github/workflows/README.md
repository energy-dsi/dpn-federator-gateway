# GitHub Actions CD (deployment)

`dpn-gha-federator-gateway-cd.yaml` is self-contained and runnable directly from here — `workflow_dispatch` from the Actions tab, no dependency on any other repo. (This file is separate from this repo's existing release/CI workflows above — `maven.yml`, `docker-ghcr.yml`, etc. — which build and publish the OSS artifact itself.)

- `dpn-gha-certificate-manager-role-id-creation.yaml` — provisions this component's own Vault AppRole identity (`federator` role, via the pre-existing `vault/setup-approle.sh` + `vault/federator-policy.hcl`), publishing `role_id`/`secret_id` so `dpn-gha-federator-gateway-cd.yaml` can fetch them automatically instead of the current documented stopgap of reusing Certificate Manager's own role_id. Relocated here from `dpn-rest-federator-gateway`, where it duplicated that repo's own dedicated role-id workflow — this repo had no AppRole-provisioning workflow of its own at all.
- `dpn-gha-federator-gateway-rollback.yaml` — `helm rollback` to a specific or auto-resolved previous revision. Requires typing the release name (`dpn-platform`) again to confirm.
- `dpn-gha-federator-gateway-uninstall.yaml` — `helm uninstall`. Requires the same confirmation. Kafka's PVCs are left in place; the Vault AppRole identity is not revoked.

## One-time setup required (not a workflow change)

- **GitHub Environments** named `<environment>-<cluster>` for each target (e.g. `dev-dpn01`, `test-dpn02`).
- **Repository or Environment secrets**: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID` (Azure OIDC federated identity).
- **AWS path**: relies on OIDC role-chaining into the shared hub role, then your account's `oidc_github_runner_admin` role — no AWS secret needed, but that role/trust must exist.
- **Self-hosted runner**: targets `runs-on: [self-hosted, vmss]` — register a runner with that label, or change `runs-on` to a pool you control.
- **Per-environment config**: `.github/config/{aws,azure}/<environment>-<cluster>.json` already has real target values — update if pointing at different infrastructure.

Nothing in the workflow logic itself needs editing to run it.
