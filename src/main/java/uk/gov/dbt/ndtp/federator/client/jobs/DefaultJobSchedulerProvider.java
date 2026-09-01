// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.jobrunr.configuration.JobRunr;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.scheduling.RecurringJobBuilder;
import org.jobrunr.storage.AbstractStorageProvider;
import org.jobrunr.storage.InMemoryStorageProvider;
import uk.gov.dbt.ndtp.federator.client.jobs.auth.BearerTokenVerifier;
import uk.gov.dbt.ndtp.federator.client.jobs.auth.JobRunnerAuthGateway;
import uk.gov.dbt.ndtp.federator.client.jobs.auth.JobRunnerAuthProperties;
import uk.gov.dbt.ndtp.federator.client.jobs.auth.OidcLoginFlow;
import uk.gov.dbt.ndtp.federator.client.jobs.params.JobParams;
import uk.gov.dbt.ndtp.federator.client.jobs.params.RecurrentJobRequest;
import uk.gov.dbt.ndtp.federator.client.lifecycle.ShutdownThread;
import uk.gov.dbt.ndtp.federator.common.service.secret.VaultTlsSupport;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.SSLUtils;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorSslException;

/**
 * Singleton provider to configure and manage the lifecycle of JobRunr background job scheduler.
 * <p>
 * Implements {@link JobSchedulerProvider} to provide a stable abstraction for components that need
 * to ensure the scheduler is running or to stop it during shutdown.
 * </p>
 * <p>
 * All scheduler configuration is centralised here so that both FederatorClient and FederatorServer
 * can reliably initialise and use the same JobRunr instance.
 * </p>
 * <p>
 * Configuration (optional, with defaults shown):
 * <ul>
 *   <li>jobs.dashboard.enabled = true</li>
 *   <li>jobs.background.enabled = true</li>
 *   <li>jobs.storage.provider = memory</li>
 *   <li>jobs.dashboard.https.enabled = false - fronts the dashboard with HTTPS via
 *       {@link HttpsDashboardProxy} (JobRunr's own dashboard has no TLS support). When enabled,
 *       jobs.dashboard.https.port is the one actually exposed publicly. Note: JobRunr's OSS
 *       dashboard still binds jobs.dashboard.port on 0.0.0.0 (no bind-address option), so any port
 *       that isn't meant to be public must be enforced as internal-only at the network layer
 *       (firewall / do not expose the port / NetworkPolicy) - a proxy's TLS or auth check in front
 *       of it is bypassable otherwise.</li>
 *   <li>jobs.dashboard.https.port = 8443</li>
 *   <li>jobs.dashboard.https.certFilePath / jobs.dashboard.https.keyFilePath - required when https enabled</li>
 *   <li>jobs.dashboard.auth.enabled = false - fronts the dashboard with Keycloak-issued bearer
 *       token verification via {@link uk.gov.dbt.ndtp.federator.client.jobs.auth.JobRunnerAuthGateway},
 *       listening on its own jobs.dashboard.auth.port and forwarding to jobs.dashboard.port (which
 *       never moves). See {@link uk.gov.dbt.ndtp.federator.client.jobs.auth.JobRunnerAuthProperties}
 *       for the full set of related properties.</li>
 * </ul>
 * Currently only the in-memory storage provider is supported without additional dependencies.
 * </p>
 * <p>
 * When both jobs.dashboard.https.enabled and jobs.dashboard.auth.enabled are true, the two are
 * chained: the HTTPS proxy (public) forwards to the auth gateway, which forwards to the plain
 * dashboard (innermost). When only one is enabled, that component's own port is the public entry
 * point fronting the dashboard directly.
 * </p>
 */
@Slf4j
public final class DefaultJobSchedulerProvider implements JobSchedulerProvider {

    public static final String CONSTANT_PROVIDER_TYPE_MEMORY = "memory";
    private static final DefaultJobSchedulerProvider INSTANCE = new DefaultJobSchedulerProvider();
    // Property keys
    private static final String PROP_DASHBOARD_ENABLED = "jobs.dashboard.enabled";
    private static final String PROP_DASHBOARD_PORT = "jobs.dashboard.port";
    private static final String PROP_BACKGROUND_ENABLED = "jobs.background.enabled";
    private static final String PROP_STORAGE_PROVIDER = "jobs.storage.provider"; // memory (default), future: redis, sql
    // JobRunr's dashboard has no TLS support of its own (see HttpsDashboardProxy), so when enabled
    // jobs.dashboard.port becomes an internal-only port and this proxy fronts it with HTTPS.
    private static final String PROP_DASHBOARD_HTTPS_ENABLED = "jobs.dashboard.https.enabled";
    private static final String PROP_DASHBOARD_HTTPS_PORT = "jobs.dashboard.https.port";
    private static final String PROP_DASHBOARD_HTTPS_CERT_FILE_PATH = "jobs.dashboard.https.certFilePath";
    private static final String PROP_DASHBOARD_HTTPS_KEY_FILE_PATH = "jobs.dashboard.https.keyFilePath";
    // Reused (not duplicated) so the auth gateway's JWKS fetch trusts whatever internal CA the
    // client's own mTLS truststore already trusts - e.g. an in-cluster Keycloak issuer signed by a
    // private CA that the JDK default trust store wouldn't otherwise recognise.
    private static final String PROP_CLIENT_TRUSTSTORE_FILE_PATH = "client.truststoreFilePath";
    private static final String PROP_CLIENT_TRUSTSTORE_PASSWORD = "client.truststorePassword";
    private final Object lifecycleLock = new Object();
    private boolean started = false;
    // Keep reference so we can close when stopping (for in-memory case)
    private AbstractStorageProvider storageProvider;
    private JobScheduler jobScheduler;
    private RecurringJobsAccess recurringJobsAccess;
    private HttpsDashboardProxy httpsDashboardProxy;
    // Present only when jobs.dashboard.auth.enabled=true; fronts the dashboard with Keycloak auth
    private JobRunnerAuthGateway authGateway;

    public DefaultJobSchedulerProvider() {
        // public constructor; instantiate and call ensureStarted() when needed
    }

    /**
     * Returns the singleton instance of DefaultJobSchedulerProvider.
     * Intended for use in production code and in tests that expect a global provider.
     */
    public static DefaultJobSchedulerProvider getInstance() {
        return INSTANCE;
    }

    /**
     * Factory for external dependency injection (e.g., tests or custom environments).
     * Returns a non-singleton instance wired with provided dependencies.
     */
    public static DefaultJobSchedulerProvider withDependencies(
            final JobScheduler scheduler, final AbstractStorageProvider storage, final RecurringJobsAccess jobsAccess) {
        DefaultJobSchedulerProvider instance = new DefaultJobSchedulerProvider();
        synchronized (instance.lifecycleLock) {
            instance.jobScheduler = scheduler;
            instance.storageProvider = storage;
            instance.recurringJobsAccess = (jobsAccess != null) ? jobsAccess : instance.defaultAccess();
            instance.started = true;
        }
        return instance;
    }

    /**
     * Ensure the JobRunr scheduler is started once. Safe for repeated calls.
     */
    @Override
    public void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (lifecycleLock) {
            if (started) {
                return;
            }

            // Read configuration with defaults
            boolean dashboardEnabled = PropertyUtil.getPropertyBooleanValue(PROP_DASHBOARD_ENABLED, "false");
            boolean backgroundEnabled = PropertyUtil.getPropertyBooleanValue(PROP_BACKGROUND_ENABLED, "true");
            String storage = PropertyUtil.getPropertyValue(PROP_STORAGE_PROVIDER, CONSTANT_PROVIDER_TYPE_MEMORY)
                    .trim()
                    .toLowerCase();
            int dashboardPort = PropertyUtil.getPropertyIntValue(PROP_DASHBOARD_PORT, "8080");

            // Only memory supported by default. If unsupported, fall back to in-memory to keep tests and runtime
            // stable.
            if (!CONSTANT_PROVIDER_TYPE_MEMORY.equals(storage)) {
                log.warn("Unsupported jobs.storage.provider='{}', falling back to 'memory'", storage);
            }
            storageProvider = new InMemoryStorageProvider();

            // set default recurring jobs access if not provided externally
            if (recurringJobsAccess == null) {
                recurringJobsAccess = defaultAccess();
            }

            JobRunnerAuthProperties authProperties =
                    dashboardEnabled ? JobRunnerAuthProperties.load(dashboardPort) : null;
            boolean dashboardAuthEnabled = authProperties != null && authProperties.isEnabled();

            var cfg = JobRunr.configure().useStorageProvider(storageProvider);
            if (backgroundEnabled) {
                cfg = cfg.useBackgroundJobServer();
            }
            if (dashboardEnabled) {
                // SECURITY: JobRunr's OSS dashboard server binds 0.0.0.0 (its configuration API
                // exposes only a port, no bind address), so dashboardPort is reachable on every
                // interface regardless of what fronts it. This is a DEPLOYMENT responsibility that
                // code cannot enforce: in Kubernetes do not list dashboardPort (or the auth
                // gateway's own port, when enabled) in the Service/container ports (and add a
                // NetworkPolicy); on a bare VM, firewall it. Only the outermost enabled layer
                // (HTTPS proxy, then auth gateway, then the dashboard itself) should ever be public.
                cfg = cfg.useDashboard(dashboardPort);
            }
            jobScheduler = cfg.initialize().getJobScheduler();

            if (dashboardAuthEnabled) {
                OidcLoginFlow oidcLoginFlow = buildOidcLoginFlow(authProperties);
                authGateway = new JobRunnerAuthGateway(
                        authProperties.getGatewayPort(),
                        authProperties,
                        buildBearerTokenVerifier(authProperties),
                        oidcLoginFlow);
                authGateway.start();
                log.info(
                        "Job Runner UI Keycloak authentication enabled (gateway on port {}, forwarding to dashboard"
                                + " port {}, browserLogin={})",
                        authProperties.getGatewayPort(),
                        dashboardPort,
                        oidcLoginFlow != null);
            }

            boolean dashboardHttpsEnabled = dashboardEnabled
                    && PropertyUtil.getPropertyBooleanValue(PROP_DASHBOARD_HTTPS_ENABLED, "false");
            if (dashboardHttpsEnabled) {
                int httpsPort = PropertyUtil.getPropertyIntValue(PROP_DASHBOARD_HTTPS_PORT, "8443");
                String certFilePath = PropertyUtil.getPropertyValue(PROP_DASHBOARD_HTTPS_CERT_FILE_PATH);
                String keyFilePath = PropertyUtil.getPropertyValue(PROP_DASHBOARD_HTTPS_KEY_FILE_PATH);
                KeyManager[] keyManagers = SSLUtils.createKeyManagerFromPem(certFilePath, keyFilePath);
                // Chain to the auth gateway when it's also enabled, otherwise straight to the dashboard.
                int httpsUpstreamPort = dashboardAuthEnabled ? authProperties.getGatewayPort() : dashboardPort;
                httpsDashboardProxy = new HttpsDashboardProxy(httpsPort, httpsUpstreamPort, keyManagers);
                httpsDashboardProxy.start();
            }

            started = true;

            log.info(
                    "JobRunr initialised (storage={}, background={}, dashboard={}, dashboardHttps={}, dashboardAuth={})",
                    CONSTANT_PROVIDER_TYPE_MEMORY,
                    backgroundEnabled,
                    dashboardEnabled,
                    dashboardHttpsEnabled,
                    dashboardAuthEnabled);

            // Register a shutdown task
            ShutdownThread.register(() -> {
                shutdown();
                log.info("JobRunr stopped");
            });
        }
    }

    /**
     * Builds the JWKS-fetching {@link BearerTokenVerifier} for the auth gateway, reusing whichever
     * mTLS trust source the rest of the client already uses to trust the Keycloak issuer's TLS
     * certificate - required when the issuer is signed by a private/internal CA (e.g. an in-cluster
     * hostname) that the JDK default trust store wouldn't otherwise recognise. Mirrors the same
     * {@code VaultTlsSupport.isVaultTlsEnabled()} switch used by {@link uk.gov.dbt.ndtp.federator.common.utils.GRPCUtils}
     * and {@link uk.gov.dbt.ndtp.federator.common.utils.HttpClientFactoryUtils}: when Vault-sourced
     * TLS is enabled, trust material is built in memory from Vault (no keystore files on disk -
     * required in environments where there is no SMB/EFS file share to mount); otherwise it falls
     * back to {@code client.truststoreFilePath}, and to the JDK default trust store when that's
     * unset - preserving prior behaviour for issuers with a publicly-trusted certificate (or plain
     * HTTP, e.g. local testing).
     */
    private BearerTokenVerifier buildBearerTokenVerifier(JobRunnerAuthProperties authProperties) {
        Supplier<java.net.http.HttpClient> supplier = buildAuthHttpClientSupplier();
        return supplier == null
                ? new BearerTokenVerifier(authProperties)
                : new BearerTokenVerifier(authProperties, supplier);
    }

    /**
     * Builds the {@link OidcLoginFlow} used for browser access to the Job Runner UI - null when
     * {@code jobs.dashboard.auth.oidc.client.id}/{@code .client.secret} aren't both configured,
     * in which case the gateway falls back to header-only (API-style) authentication. Reuses the
     * same mTLS trust source as {@link #buildBearerTokenVerifier} so the token-endpoint call also
     * trusts the Keycloak issuer's TLS certificate.
     */
    private OidcLoginFlow buildOidcLoginFlow(JobRunnerAuthProperties authProperties) {
        if (!authProperties.isBrowserLoginEnabled()) {
            return null;
        }
        Supplier<java.net.http.HttpClient> supplier = buildAuthHttpClientSupplier();
        return new OidcLoginFlow(
                authProperties, supplier != null ? supplier : java.net.http.HttpClient::newHttpClient);
    }

    /**
     * Builds the HttpClient trust source shared by the auth gateway's JWKS fetch and OIDC token
     * exchange - both need to trust the Keycloak issuer's TLS certificate, so both reuse whichever
     * mTLS trust source the rest of the client already uses: Vault-sourced trust material, then
     * {@code client.truststoreFilePath}, then the {@code jobs.dashboard.auth.trustStore*} system
     * properties BearerTokenVerifier's own default constructor falls back to (see
     * {@code BearerTokenVerifier.trustStoreSslContext()} - mirrored here so OidcLoginFlow's
     * token-endpoint call gets that same isolated trust, not just the JWKS fetch). Returns null
     * when none of these are configured, so callers fall back to their own default (JDK trust
     * store) - preserving prior behaviour for issuers with a publicly-trusted certificate (or
     * plain HTTP, e.g. local testing).
     */
    private Supplier<java.net.http.HttpClient> buildAuthHttpClientSupplier() {
        if (VaultTlsSupport.isVaultTlsEnabled()) {
            SSLContext sslContext = trustOnlySslContext(VaultTlsSupport.trustManagers());
            java.net.http.HttpClient httpClient =
                    java.net.http.HttpClient.newBuilder().sslContext(sslContext).build();
            return () -> httpClient;
        }
        String truststorePath = PropertyUtil.getPropertyValue(PROP_CLIENT_TRUSTSTORE_FILE_PATH, "");
        if (!truststorePath.isBlank()) {
            String truststorePassword = PropertyUtil.getPropertyValue(PROP_CLIENT_TRUSTSTORE_PASSWORD, "");
            SSLContext sslContext = SSLUtils.createSSLContextWithTrustStore(truststorePath, truststorePassword);
            java.net.http.HttpClient httpClient =
                    java.net.http.HttpClient.newBuilder().sslContext(sslContext).build();
            return () -> httpClient;
        }
        String authTrustStorePath = System.getProperty("jobs.dashboard.auth.trustStore");
        if (authTrustStorePath == null) {
            return null;
        }
        SSLContext sslContext = authTrustStoreSslContext(authTrustStorePath);
        java.net.http.HttpClient httpClient =
                java.net.http.HttpClient.newBuilder().sslContext(sslContext).build();
        return () -> httpClient;
    }

    private static SSLContext authTrustStoreSslContext(String trustStorePath) {
        try {
            String trustStoreType =
                    System.getProperty("jobs.dashboard.auth.trustStoreType", java.security.KeyStore.getDefaultType());
            String trustStorePassword = System.getProperty("jobs.dashboard.auth.trustStorePassword", "");
            java.security.KeyStore trustStore = java.security.KeyStore.getInstance(trustStoreType);
            try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(trustStorePath))) {
                trustStore.load(in, trustStorePassword.toCharArray());
            }
            javax.net.ssl.TrustManagerFactory tmf =
                    javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new FederatorSslException(
                    "Failed to build SSLContext from jobs.dashboard.auth.trustStore=" + trustStorePath, e);
        }
    }

    private static SSLContext trustOnlySslContext(TrustManager[] trustManagers) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, null);
            return sslContext;
        } catch (Exception e) {
            throw new FederatorSslException("Failed to create SSLContext from Vault trust material.", e);
        }
    }

    private void shutdown() {
        try {
            if (httpsDashboardProxy != null) {
                httpsDashboardProxy.stop();
            }
        } catch (Exception e) {
            log.debug("Ignoring exception while stopping HTTPS dashboard proxy", e);
        } finally {
            httpsDashboardProxy = null;
        }
        try {
            if (authGateway != null) {
                authGateway.stop();
            }
        } catch (Exception e) {
            log.debug("Ignoring exception while stopping Job Runner auth gateway", e);
        } finally {
            authGateway = null;
        }
        try {
            JobRunr.destroy();
        } catch (Exception e) {
            log.debug("Ignoring exception while destroying JobRunr", e);
        }
        try {
            if (storageProvider != null) {
                storageProvider.close();
            }
        } catch (Exception e) {
            log.debug("Ignoring exception while closing storage provider", e);
        }
    }

    private RecurringJobsAccess defaultAccess() {
        return new RecurringJobsAccess() {
            @Override
            public Map<String, JobParams> paramsById(AbstractStorageProvider sp) {
                Map<String, JobParams> map = new java.util.HashMap<>();
                for (RecurringJob r : sp.getRecurringJobs()) {
                    JobParams p = extractJobParamsFrom(r);
                    if (p != null) map.put(r.getId(), p);
                }
                return map;
            }

            @Override
            public java.util.Set<String> ids(AbstractStorageProvider sp) {
                return sp.getRecurringJobs().stream()
                        .map(RecurringJob::getId)
                        .collect(java.util.stream.Collectors.toSet());
            }
        };
    }

    /** Stops the scheduler if running. Normally invoked via the registered shutdown hook. */
    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            if (!started) return;
            shutdown();
            started = false;
            log.info("JobRunr stopped (explicit)");
        }
    }

    /**
     * Obtain the JobRunr JobScheduler instance.
     * <p>
     * This method is only valid after {@link #ensureStarted()} has successfully initialised the scheduler.
     * It will return the same instance for the lifetime of this provider until {@link #stop()} is called.
     * </p>
     *
     * @return the active JobScheduler instance
     * @throws IllegalStateException if called before the scheduler has been started or after it has been stopped
     */
    @Override
    public JobScheduler getJobScheduler() {
        if (started) return jobScheduler;
        else throw new IllegalStateException("JobScheduler not started");
    }

    @Override
    public void registerJob(Job job, JobParams params) {

        if (job == null) throw new IllegalArgumentException("Job cannot be null");

        if (params == null) throw new IllegalArgumentException("Params cannot be null");

        if (params.getRequireImmediateTrigger() != null && params.getRequireImmediateTrigger()) {
            log.info("Registering job {} with immediate trigger", params.getJobName());
            UUID id = UUID.randomUUID();
            jobScheduler.enqueue(id, () -> job.run(params));
            log.info("Enqueued immediate job name={} id={}", params.getJobName(), id);
        }

        log.info("Registering recurrent job {} ", params.getJobName());
        RecurringJobBuilder recurringJobBuilder = RecurringJobBuilder.aRecurringJob();
        recurringJobBuilder.withScheduleExpression(params.getScheduleExpression());
        recurringJobBuilder
                .withId(params.getJobId())
                .withZoneId(ZoneId.of("Europe/London"))
                .withDetails(() -> job.run(params));

        if (params.getAmountOfRetries() != null && params.getAmountOfRetries() > 0)
            recurringJobBuilder.withAmountOfRetries(params.getAmountOfRetries());

        jobScheduler.createRecurrently(recurringJobBuilder);
        log.info(
                "Registered recurring job id={} name={} interval={} retries={}",
                params.getJobId(),
                params.getJobName(),
                params.getScheduleExpression(),
                params.getAmountOfRetries());
    }

    @Override
    public void removeRecurringJob(String jobId) {
        log.info("Removing recurring job {}", jobId);
        jobScheduler.deleteRecurringJob(jobId);
        log.info("Deleted recurring job {}", jobId);
    }

    @Override
    public void reloadRecurrentJobs(final String managementNodeId, final List<RecurrentJobRequest> requests) {
        Objects.requireNonNull(managementNodeId, "Management Node Id can not be null");
        Objects.requireNonNull(requests, "params can not be null");

        // 1) Build a quick lookup of the requested jobs for this management node
        //    Key = jobId, Value = JobParams
        final Map<String, JobParams> requestedForNode = buildRequestedJobMapForNode(managementNodeId, requests);

        // 2) Remove jobs for this node that are no longer requested or whose params changed
        //    - Skip jobs that belong to other nodes
        //    - Skip jobs if we cannot safely read their JobParams
        removeObsoleteOrModifiedJobs(managementNodeId, requestedForNode);

        // 3) Add any requested jobs for this node that are not currently scheduled
        addMissingJobs(managementNodeId, requests);
    }

    /**
     * Builds a map of requested recurring jobs for a specific management node.
     */
    private Map<String, JobParams> buildRequestedJobMapForNode(
            final String managementNodeId, final List<RecurrentJobRequest> requests) {
        return requests.stream()
                .map(RecurrentJobRequest::getJobParams)
                .filter(Objects::nonNull)
                .filter(p -> managementNodeId.equals(p.getManagementNodeId()))
                .collect(java.util.stream.Collectors.toMap(
                        JobParams::getJobId, java.util.function.Function.identity(), (a, b) -> b));
    }

    /**
     * Remove recurring jobs that either:
     * - are not present in the new requests for this node (Remove), or
     * - have different parameters compared to the requested ones (Remove), or
     * - belong to another node (Skip), or
     * - have unreadable params (Skip for safety).
     * If a job is unchanged, we Skip it (keep it as-is).
     */
    private void removeObsoleteOrModifiedJobs(
            final String managementNodeId, final Map<String, JobParams> requestedForNode) {
        final java.util.Map<String, JobParams> existingMap = getExistingJobsMap();

        for (java.util.Map.Entry<String, JobParams> entry : existingMap.entrySet()) {
            final String existingId = entry.getKey();
            final JobParams existingParams = entry.getValue();

            final boolean manageable =
                    existingParams != null && managementNodeId.equals(existingParams.getManagementNodeId());

            if (!manageable) {
                log.warn(
                        "Skipping recurring job id={} for management node={} (not manageable)",
                        existingId,
                        managementNodeId);
                continue;
            }
            final JobParams requestedParams = requestedForNode.get(existingId);
            if (requestedParams == null) {
                // Not requested anymore -> Remove
                log.info(
                        "Deleting recurring job not present in requests id={} for management node={}",
                        existingId,
                        managementNodeId);
                removeRecurringJob(existingId);
            } else {
                boolean unchanged = requestedParams.equals(existingParams);

                if (!unchanged) {
                    // Parameters changed -> Remove (we will add it back with new params in the Add phase)
                    log.info(
                            "Deleting modified recurring job with id={} for management node={}",
                            existingId,
                            managementNodeId);
                    removeRecurringJob(existingId);
                } else {
                    // Parameters unchanged -> Skip
                    log.debug(
                            "Skipping unchanged recurring job id={} for management node={} (same hash/equality)",
                            existingId,
                            managementNodeId);
                }
            }
        }
    }

    /**
     * Adds any requested jobs for the node that are not currently scheduled after the removal phase.
     * - Add: job requested for this node but not present after removals
     * - Skip: job already present (unchanged) or belongs to a different node
     */
    private void addMissingJobs(final String managementNodeId, final List<RecurrentJobRequest> requests) {
        final java.util.Set<String> existingIdsAfterDeletion = recurringJobsAccess.ids(storageProvider);

        for (RecurrentJobRequest req : requests) {
            final JobParams jobParams = req.getJobParams();
            final boolean manageable = jobParams != null && managementNodeId.equals(jobParams.getManagementNodeId());
            if (manageable) {
                final String id = jobParams.getJobId();
                final boolean alreadyPresent = existingIdsAfterDeletion.contains(id);
                if (!alreadyPresent) {
                    try {
                        // Add
                        log.info(
                                "Registering missing/updated recurring job id={} for management node={}",
                                id,
                                managementNodeId);
                        registerJob(req.getJob(), jobParams);
                    } catch (Exception e) {
                        log.error(
                                "Failed to register recurring job id={} (management node={})", id, managementNodeId, e);
                    }
                }
            }
        }
    }

    /**
     * Safely extracts JobParams from a JobRunr RecurringJob. If extraction fails, returns null.
     */
    private JobParams extractJobParamsFrom(final RecurringJob existing) {
        try {
            final var details = existing.getJobDetails();
            final var params = (details != null) ? details.getJobParameters() : null;
            if (params != null && !params.isEmpty()) {
                final Object obj = params.getFirst().getObject();
                if (obj instanceof JobParams jobParams) {
                    return jobParams;
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect existing job parameters for id={}: {}", existing.getId(), e.toString());
        }
        return null;
    }

    private java.util.Map<String, JobParams> getExistingJobsMap() {
        return new java.util.HashMap<>(recurringJobsAccess.paramsById(storageProvider));
    }

    // Strategy to retrieve existing recurring jobs and their parameters
    public interface RecurringJobsAccess {
        Map<String, JobParams> paramsById(AbstractStorageProvider storageProvider);

        java.util.Set<String> ids(AbstractStorageProvider storageProvider);
    }
}
