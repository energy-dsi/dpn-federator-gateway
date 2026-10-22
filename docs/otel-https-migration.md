# OTel Collector HTTPS Migration — Understanding Document

## Goal

The DPN OTel Collector now terminates TLS on its OTLP receivers. FederatorClient
(and FederatorServer, which shares the same telemetry wiring) must switch from
plaintext OTLP export to `https://<collector>:4318`.

## Current state

- Both `FederatorClient` and `FederatorServer` initialize telemetry through the
  same class: `OpenTelemetryConfig.java`
  (`src/main/java/uk/gov/dbt/ndtp/federator/common/telemetry/OpenTelemetryConfig.java`).
  It builds the SDK via `AutoConfiguredOpenTelemetrySdk.builder()` — there is
  **no hand-written `OtlpHttpSpanExporter`/`OtlpHttpMetricExporter` or SSL
  context code**. Every exporter setting (endpoint, protocol, TLS) is driven
  entirely by `OTEL_*` environment variables that the autoconfigure module
  reads at startup.
- Today those env vars are set per-environment in Helm values
  (`charts/dpn-platform/values-<env>.yaml`, e.g. `values-dev-dpn01.yaml:397-399`)
  and injected into the containers in
  `charts/dpn-platform/templates/federator-client-deployment.yaml:126-142` and
  `federator-server-deployment.yaml:109-125`:
  ```yaml
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://dpn-otel-collector.ns-dpn-health-01.svc.cluster.local:4317"
  OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
  OTEL_EXPORTER_OTLP_INSECURE: "true"
  ```
  Note the current setup is actually **gRPC on 4317**, not HTTP on 4318, and
  TLS is explicitly disabled everywhere (dev through puat/test).
- Locally, `docker/otel-collector-config.yaml:12-18` runs a collector with
  both `0.0.0.0:4317` (grpc) and `0.0.0.0:4318` (http) receivers, exporting
  only to `debug` — no TLS, and no `docker-compose*.yml` even wires up an
  `otel-collector` container today.
- The repo already has reusable TLS building blocks used for other HTTPS/mTLS
  integrations (JobRunr dashboard, gRPC, Vault), so the pattern to follow is
  established even though it isn't wired into OTel yet:
  - `SSLUtils.java` (`createSSLContextWithTrustStore`, `createSSLContext`,
    `createKeyManagerFromPem`) — generic keystore/truststore helpers.
  - `HttpsDashboardProxy.java` — PEM `.crt`/`.key` → SSLContext pattern for
    JobRunr's dashboard (see `[[jobrunr-dashboard-https-pem]]` memory).
  - `VaultTlsSupport.java` / `HttpClientFactoryUtils.java` — Vault-backed
    mTLS with a file-based fallback, used elsewhere for HTTP clients.
  - Certs already checked into `docker/docker-grpc-resources/certs/`,
    including a `dpn-observability/` subfolder (`dpn-observability.crt`,
    `dpn-observability-fullchain.crt`, `rootCA.crt`) that is not currently
    referenced by anything — it looks earmarked for exactly this migration.

## What actually needs to change

Because telemetry is wired through the OTel Java **autoconfigure** module
(not manual exporter code), most of this migration is configuration, not
Java code.

### 1. Endpoint/protocol/TLS env vars (Helm values, per environment)

In every `charts/dpn-platform/values-*.yaml` file that currently sets
`OTEL_EXPORTER_OTLP_*`:

```yaml
OTEL_EXPORTER_OTLP_ENDPOINT: "https://dpn-otel-collector.ns-dpn-health-01.svc.cluster.local:4318"
OTEL_EXPORTER_OTLP_PROTOCOL: "http/protobuf"   # 4318 is the HTTP receiver, not grpc
OTEL_EXPORTER_OTLP_INSECURE: "false"           # or remove — false is the default once scheme is https
OTEL_EXPORTER_OTLP_CERTIFICATE: "/etc/otel/certs/ca.crt"   # trust anchor for the collector's server cert
```

- If the collector's cert is issued by a CA the JVM's default trust store
  already trusts, `OTEL_EXPORTER_OTLP_CERTIFICATE` can be omitted. If it's a
  private/internal CA (likely, given `dpn-observability` certs are already
  in-repo), the CA PEM must be supplied — the autoconfigure module reads
  `OTEL_EXPORTER_OTLP_CERTIFICATE` directly as a PEM file path, no code
  needed.
- If the collector also requires client-cert auth (mTLS, not just server-side
  TLS termination), also set `OTEL_EXPORTER_OTLP_CLIENT_KEY` and
  `OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE` (PEM paths) — confirm with the OTel
  Collector team whether the receiver requires client certs or just serves
  TLS.

### 2. Mount the trust CA into the containers

`federator-client-deployment.yaml` and `federator-server-deployment.yaml`
need a volume + volumeMount for the CA cert, the same way `HttpsDashboardProxy`'s
certs are delivered as a plain file for JobRunr (no Vault involved). This
means adding a k8s Secret or ConfigMap containing the collector's CA PEM
(sourced from the `dpn-observability` bundle), mounted at the path referenced
by `OTEL_EXPORTER_OTLP_CERTIFICATE` above.

### 3. Local dev collector (docker)

`docker/otel-collector-config.yaml` needs `tls:` config added under
`receivers.otlp.protocols.http` (cert_file/key_file), and a docker-compose
service definition for the collector needs to exist and mount those certs —
currently there isn't one, so local FederatorClient/Server runs presumably
point at a manually-run collector or skip telemetry. The existing
`dpn-observability` certs in `docker/docker-grpc-resources/certs/` are the
likely candidates to reuse here (self-signed, similar to the JobRunr
dashboard pattern per `[[jobrunr-dashboard-https-pem]]`).

### 4. Java code

No changes expected in `OpenTelemetryConfig.java` itself — the autoconfigure
module already honors `OTEL_EXPORTER_OTLP_CERTIFICATE` (a plain PEM file
path) out of the box, and the cert is delivered as a mounted file (like
JobRunr's dashboard cert), not sourced from Vault. Code changes would only be
needed if certificate hot-reload/rotation without a pod restart is required.

### 5. `FederatorClient` config docs

`docs/client-configuration.md` and `docs/server-configuration.md` likely
document the `OTEL_EXPORTER_OTLP_*` env vars for operators and should be
updated to describe the new HTTPS endpoint and required cert env vars.

## Decisions (confirmed)

1. **Server-only TLS.** The collector's OTLP HTTP receiver only presents a
   server certificate; it does not require a client certificate. So only a
   trust anchor (CA cert) is needed — `OTEL_EXPORTER_OTLP_CLIENT_KEY` /
   `CLIENT_CERTIFICATE` are **not** required.
2. **Trust source: the `dpn-observability` cert bundle** already checked
   into `docker/docker-grpc-resources/certs/dpn-observability/`
   (`dpn-observability.crt`, `dpn-observability-fullchain.crt`, `rootCA.crt`)
   is the CA to trust — confirming this bundle was indeed earmarked for this
   migration.
3. **Cert delivery: plain file, no Vault** — same style as the JobRunr
   dashboard's HTTPS setup (`HttpsDashboardProxy.java`, PEM file loaded via
   `SSLUtils`, nothing sourced from Vault). The CA PEM is mounted into the
   pod filesystem (as a k8s Secret/ConfigMap volume backed by the
   `dpn-observability` bundle) or read from a local folder for docker/dev,
   and referenced by a file path — consistently across **all** environments,
   no Vault involvement for this cert.
4. **Rollout scope:** the switch to `https://<collector>:4318` rolls out
   **across all environments together**, not staged per-environment — so all
   `charts/dpn-platform/values-*.yaml` files change in the same PR/release.

### Why this needs no Java code change

Unlike the gRPC client↔server mTLS path (which sources identity/trust
material from Vault via `VaultTlsSupport`, requiring a programmatic
`SSLContext`), the OTel Java **autoconfigure** module already used in
`OpenTelemetryConfig.java` (`AutoConfiguredOpenTelemetrySdk`) natively reads
a **CA PEM file path** from the `OTEL_EXPORTER_OTLP_CERTIFICATE` environment
variable and uses it to trust the collector's server certificate — no
`ConfigurableSpanExporterProvider`, manual `OtlpHttpSpanExporter` builder, or
custom `SSLContext` wiring is required. Going file-based (rather than Vault)
means this is a **pure configuration + deployment change**:
just point that env var at the mounted `dpn-observability` CA file.

## Implementation status: done (revised - JKS truststore, not a CA PEM file)

**Revision note:** the first pass of this migration used a plain CA PEM file
(`OTEL_EXPORTER_OTLP_CERTIFICATE`, a new `dpn-observability-ca` Secret). That
was **replaced** with a JKS truststore + password, because that's the
trust-material style this project standardises on, not a raw CA cert file.
This required actual Java code, since OTel's autoconfigure SDK has no native
JKS+password support (only a PEM file path). A second pass then dropped an
initial idea of reusing *Kafka's* truststore (`dpn-tls`/`kafka-secrets`,
conditional on `kafka.authMode`) in favour of reusing the **common
truststore Vault's own TLS connection already uses**
(`vaultConfig.truststorePath`/`VAULT_TRUSTSTORE_PASSWORD`, already
unconditional) — per review feedback that OTel's HTTPS trust shouldn't be
conditional on Kafka's auth mode at all, and should reuse an existing
common truststore rather than Kafka's own.

1. **`charts/dpn-platform/values-*.yaml`** (all 9 environments):
   ```yaml
   OTEL_EXPORTER_OTLP_ENDPOINT: "https://dpn-otel-collector.ns-dpn-health-01.svc.cluster.local:4318"
   OTEL_EXPORTER_OTLP_PROTOCOL: "http/protobuf"
   OTEL_EXPORTER_OTLP_INSECURE: "false"
   ```
   (`OTEL_EXPORTER_OTLP_CERTIFICATE` removed - no longer used.)
2. **`federator-client-deployment.yaml` / `federator-server-deployment.yaml`**
   — added `OTEL_EXPORTER_OTLP_TRUSTSTORE_PATH` (static path,
   `{{ .Values.vaultConfig.truststorePath }}` — the same
   `/vault/truststore/truststore.jks` Vault's own TLS connection already
   uses) and `OTEL_EXPORTER_OTLP_TRUSTSTORE_PASSWORD` (`valueFrom.secretKeyRef`,
   **the same** `certificate-manager-secrets`/`VAULT_SSL_TRUST_STORE_PASSWORD`
   the existing `VAULT_TRUSTSTORE_PASSWORD` env var already uses) —
   **unconditional**, not guarded by `kafka.authMode`. **No new volume,
   mount, or Secret** - this reuses the `cert-manager-truststore` Secret
   (the `vault-truststore` volume), already mounted at `/vault/truststore`
   in both deployments regardless of `kafka.authMode`. (Both the earlier
   `otel-ca` volume/mount + `dpn-observability-ca` Secret, and the later
   Kafka-truststore/`kafka.authMode`-conditional approach, were removed.)
4. **`OtlpTruststoreSupport.java`** (new,
   `src/main/java/uk/gov/dbt/ndtp/federator/common/telemetry/`) — resolves
   `OTEL_EXPORTER_OTLP_TRUSTSTORE_PATH`/`_PASSWORD`, loads the JKS via the
   existing `SSLUtils.createTrustManager(path, password)` helper (already
   used elsewhere for Kafka/gRPC TLS), and builds a trust-only `SSLContext`.
   Returns `null` (no-op) if either env var is unset.
5. **`OpenTelemetryConfig.java`** — now wires that `SSLContext` into the
   OTLP exporters via three of the OTel autoconfigure SDK's own hooks:
   `addSpanExporterCustomizer`/`addMetricExporterCustomizer`/
   `addLogRecordExporterCustomizer`. Each customizer checks
   `instanceof OtlpHttp{Span,Metric,LogRecord}Exporter`, and if so calls the
   exporter's own `.toBuilder().setSslContext(sslContext, trustManager).build()`
   (both APIs confirmed present on the pinned `opentelemetry-exporter-otlp`
   1.63.0) - preserving every other autoconfigure-resolved setting
   (endpoint, protocol, batching, resource attributes) unchanged. When the
   truststore env vars are unset, the customizers are no-ops and behaviour
   is identical to before this change.
6. **Local docker** (`docker/otel-collector-config.yaml`) — unchanged from
   the first pass: TLS enabled on the `http` OTLP receiver using the
   `dpn-observability` cert/key already used for Keycloak locally (this is
   the collector's own **server** identity, unrelated to the client-side
   truststore change above).
7. **Local JKS truststore**: converted the existing
   `dpn-observability-truststore.p12` (PKCS12) into
   `dpn-observability-truststore.jks` (JKS, same `changeit` password) via
   `keytool -importkeystore`, for local `OTEL_EXPORTER_OTLP_TRUSTSTORE_PATH`
   use - both files live in
   `docker/docker-grpc-resources/certs/dpn-observability/`, untracked like
   the rest of that folder.

**Verified end-to-end**: ran `OpenTelemetryConfig.initialize()` directly
(compiled against the project's own classpath, outside Maven) with
`OTEL_EXPORTER_OTLP_TRUSTSTORE_PATH`/`_PASSWORD` pointed at the local JKS
file and the collector's HTTPS endpoint, emitted a test span, and confirmed
via the collector's `debug` exporter logs that both the span and log records
arrived successfully with no TLS/trust errors.

### Outstanding, not code — an ops/platform action

None new for the CA/trust side - this reuses the `cert-manager-truststore`/
`certificate-manager-secrets` Secrets that already exist in every
environment (Vault's own TLS connection already depends on them), so **no
new Secret needs provisioning** for OTel specifically. The only outstanding
item is confirming the real OTel Collector's OTLP HTTPS receiver in
`ns-dpn-health-01` presents a certificate this same common truststore
actually trusts - if the collector's cert is issued by a different CA than
whatever's already in that truststore, it would need the collector's CA
added to it (an ops action against an existing Secret, not a new one).

### Known gap: `.pipelines/` chart mirror is stale and untouched

`.pipelines/github-actions-pipelines/azure-pipelines/dpn-federator-gateway/charts/dpn-platform/`
contains a **separate, already-diverged copy** of this chart (different
container registry, missing the `kafka.authMode` toggle, etc.) that also sets
`OTEL_EXPORTER_OTLP_ENDPOINT`. It was **not** updated here since it's already
out of sync with `charts/dpn-platform/` in unrelated ways predating this
change — worth flagging to whoever owns that pipeline rather than silently
patching one variable in an otherwise-stale copy.
