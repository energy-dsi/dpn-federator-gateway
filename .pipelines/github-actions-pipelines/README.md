# GitHub Actions deployment reference

Reference copy of this component's GitHub Actions deployment tooling, sourced from `dpn-containerised-deployment-service` (branch `feature/merge-azure-aws`) on 2026-09-22. Replaces a previous, incorrectly-shaped copy of this folder.

**This is a reference copy, not a runnable pipeline from this location, and does not affect the existing Azure DevOps pipelines under `.pipelines/azure-pipelines/`.** The actual GitHub Actions CD pipeline still runs centrally from `dpn-containerised-deployment-service`, which checks out this repo's chart directly — GitHub Actions only auto-discovers workflows under a repo's own `.github/workflows/`, so nothing here is triggerable from this repo as-is. This folder exists so this repo's own history reflects its GitHub Actions deployment configuration.

- `actions/cloud-login/` — the shared composite action every workflow here uses to authenticate to whichever cloud (azure/aws/gcp) is selected at dispatch time
- `config/{aws,azure}/*.json` — per-environment config, one set per cloud (shared across all DPN components deployed to that environment; this component's workflow reads only the keys it needs)
- `workflows/dpn-gha-federator-gateway-cd.yaml` — the install/deploy workflow, handles all three clouds via its own `cloud` input (not three separate files)

The Helm values for this chart on each cloud now live where they belong — alongside the chart itself, under `charts/dpn-platform/values/<cloud>/<environment>-<cluster>.yaml` (e.g. `values/aws/dev-dpn01.yaml`) — not in this folder. This mirrors the `values/{aws,azure,gcp}/<environment>-<cluster>.yaml` folder structure used in the source repo `dpn-containerised-deployment-service` (GCP excluded here). It sits alongside, and does not touch, the existing flat `values-<environment>-<cluster>.yaml` files used by the Azure DevOps pipelines.

No uninstall or rollback workflow exists yet for this component.
