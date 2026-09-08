package uk.gov.dbt.ndtp.federator.client.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.JobParameter;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.AbstractStorageProvider;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import uk.gov.dbt.ndtp.federator.client.jobs.params.JobParams;
import uk.gov.dbt.ndtp.federator.client.jobs.params.RecurrentJobRequest;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import java.net.ServerSocket;

class DefaultJobSchedulerProviderTest {

    private File tempProps;

    @BeforeEach
    void setUp() throws IOException {
        // Ensure PropertyUtil is clean and init with empty properties
        PropertyUtil.clear();
        tempProps = Files.createTempFile("props", ".properties").toFile();
        PropertyUtil.init(tempProps);
        // Do not start provider here; individual tests will inject mocks as needed
    }

    @AfterEach
    void tearDown() {
        PropertyUtil.clear();
        if (tempProps != null) tempProps.delete();
    }

    @Test
    void getJobScheduler_throws_if_not_started() {
        DefaultJobSchedulerProvider provider = new DefaultJobSchedulerProvider();
        IllegalStateException ex = assertThrows(IllegalStateException.class, provider::getJobScheduler);
        assertTrue(ex.getMessage().contains("not started"));
    }

    @Test
    void ensureStarted_then_stop_toggles_scheduler_availability() {
        JobScheduler scheduler = mock(JobScheduler.class);
        AbstractStorageProvider storage = mock(AbstractStorageProvider.class);
        DefaultJobSchedulerProvider provider = DefaultJobSchedulerProvider.withDependencies(
                scheduler, storage, new DefaultJobSchedulerProvider.RecurringJobsAccess() {
                    @Override
                    public Map<String, JobParams> paramsById(AbstractStorageProvider sp) {
                        return java.util.Collections.emptyMap();
                    }

                    @Override
                    public java.util.Set<String> ids(AbstractStorageProvider sp) {
                        return java.util.Collections.emptySet();
                    }
                });

        assertNotNull(provider.getJobScheduler(), "JobScheduler should be available after mock start");

        provider.stop();
        assertThrows(
                IllegalStateException.class,
                provider::getJobScheduler,
                "JobScheduler should not be available after stop");
    }

    @Test
    void registerJob_validates_arguments_and_triggers_immediate_when_configured() {
        JobScheduler scheduler = mock(JobScheduler.class);
        AbstractStorageProvider storage = mock(AbstractStorageProvider.class);
        DefaultJobSchedulerProvider provider = DefaultJobSchedulerProvider.withDependencies(
                scheduler, storage, new DefaultJobSchedulerProvider.RecurringJobsAccess() {
                    @Override
                    public Map<String, JobParams> paramsById(AbstractStorageProvider sp) {
                        return java.util.Collections.emptyMap();
                    }

                    @Override
                    public java.util.Set<String> ids(AbstractStorageProvider sp) {
                        return java.util.Collections.emptySet();
                    }
                });

        // Null validations
        JobParams sampleParams = JobParams.builder()
                .jobId("job-null-test")
                .jobName("job-null-test")
                .managementNodeId("node-1")
                .scheduleExpression(JobsConstants.DEFAULT_DURATION_EVERY_HOUR)
                .build();
        assertThrows(IllegalArgumentException.class, () -> provider.registerJob(null, sampleParams));
        assertThrows(IllegalArgumentException.class, () -> provider.registerJob(value -> {}, null));

        // Immediate trigger path
        AtomicInteger executed = new AtomicInteger(0);
        Job job = value -> executed.incrementAndGet();
        JobParams params = JobParams.builder()
                .jobId("job-immediate-" + UUID.randomUUID())
                .jobName("immediate")
                .managementNodeId("node-1")
                .requireImmediateTrigger(true)
                .amountOfRetries(2)
                .scheduleExpression(JobsConstants.DEFAULT_DURATION_EVERY_HOUR)
                .build();

        provider.registerJob(job, params);

        // Verify interactions instead of actually running background services
        verify(scheduler, times(1)).createRecurrently(any());
    }

    @Test
    void test_getInstance_returnsSameInstance() {
        DefaultJobSchedulerProvider instance1 = DefaultJobSchedulerProvider.getInstance();
        DefaultJobSchedulerProvider instance2 = DefaultJobSchedulerProvider.getInstance();
        assertSame(instance1, instance2);
    }

    @Test
    void test_stop_whenNotStarted() {
        DefaultJobSchedulerProvider provider = new DefaultJobSchedulerProvider();
        assertDoesNotThrow(provider::stop);
    }

    @Test
    void reloadRecurrentJobs_removes_obsolete_removes_modified_and_adds_missing_while_skipping_other_nodes() {
        JobScheduler scheduler = mock(JobScheduler.class);
        AbstractStorageProvider storage = mock(AbstractStorageProvider.class);

        // Existing jobs: A (node-1), B (node-1), X (node-2)
        JobParams paramsA = JobParams.builder()
                .jobId("A")
                .jobName("A")
                .managementNodeId("node-1")
                .scheduleExpression(JobsConstants.DEFAULT_DURATION_EVERY_HOUR)
                .requireImmediateTrigger(false)
                .build();
        JobParams paramsB = JobParams.builder()
                .jobId("B")
                .jobName("B")
                .managementNodeId("node-1")
                .scheduleExpression(JobsConstants.DEFAULT_DURATION_EVERY_HOUR)
                .amountOfRetries(1)
                .requireImmediateTrigger(false)
                .build();
        JobParams paramsX = JobParams.builder()
                .jobId("X")
                .jobName("X")
                .managementNodeId("node-2")
                .scheduleExpression(JobsConstants.DEFAULT_DURATION_EVERY_HOUR)
                .requireImmediateTrigger(false)
                .build();

        DefaultJobSchedulerProvider provider = DefaultJobSchedulerProvider.withDependencies(
                scheduler, storage, new DefaultJobSchedulerProvider.RecurringJobsAccess() {
                    @Override
                    public Map<String, JobParams> paramsById(AbstractStorageProvider sp) {
                        return java.util.Map.of(
                                "A", paramsA,
                                "B", paramsB,
                                "X", paramsX);
                    }

                    @Override
                    public java.util.Set<String> ids(AbstractStorageProvider sp) {
                        return new java.util.HashSet<>(java.util.List.of("A", "B", "X"));
                    }
                });

        // Build new requests for node-1: B (modified) and C (new), both with immediate triggers
        List<RecurrentJobRequest> requests = new ArrayList<>();
        JobParams paramsBModified = JobParams.builder()
                .jobId("B")
                .jobName("B")
                .managementNodeId("node-1")
                .scheduleExpression(JobsConstants.DEFAULT_DURATION_EVERY_HOUR)
                .amountOfRetries(3)
                .requireImmediateTrigger(true)
                .build();
        JobParams paramsC = JobParams.builder()
                .jobId("C")
                .jobName("C")
                .managementNodeId("node-1")
                .scheduleExpression(JobsConstants.DEFAULT_DURATION_EVERY_HOUR)
                .requireImmediateTrigger(true)
                .build();

        // Provide dummy jobs for requests (they won't actually run)
        Job dummy = value -> {};
        requests.add(RecurrentJobRequest.builder()
                .job(dummy)
                .jobParams(paramsBModified)
                .build());
        requests.add(RecurrentJobRequest.builder().job(dummy).jobParams(paramsC).build());

        provider.reloadRecurrentJobs("node-1", requests);

        // Verify deletions: A (obsolete) and B (modified) should be deleted; X belongs to another node and should be
        // skipped
        verify(scheduler, times(1)).deleteRecurringJob("A");
        verify(scheduler, times(1)).deleteRecurringJob("B");
        verify(scheduler, never()).deleteRecurringJob("X");

        // Verify additions: B and C should be (re)registered -> recurrent creation each
        verify(scheduler, times(1)).createRecurrently(any());
    }

    @Test
    void getInstance_returnsSameInstance() {
        assertSame(DefaultJobSchedulerProvider.getInstance(), DefaultJobSchedulerProvider.getInstance());
    }

    /** Finds a free port on the local machine. */
    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Cannot find a free port", e);
        }
    }

    @Test
    void ensureStarted_withUnsupportedStorage_fallsBackToMemory() {
        try (MockedStatic<PropertyUtil> propMock = mockStatic(PropertyUtil.class)) {
            propMock.when(() -> PropertyUtil.getPropertyBooleanValue(anyString(), anyString()))
                    .thenReturn(false);
            propMock.when(() -> PropertyUtil.getPropertyValue(eq("jobs.storage.provider"), anyString()))
                    .thenReturn("unsupported");
            propMock.when(() -> PropertyUtil.getPropertyIntValue(anyString(), anyString()))
                    .thenReturn(freePort()); // use OS-assigned free port — avoids BindException

            DefaultJobSchedulerProvider provider = new DefaultJobSchedulerProvider();
            provider.ensureStarted();
            assertNotNull(provider.getJobScheduler());
            provider.stop();
        }
    }

    @Test
    void ensureStarted_withDashboardAndBackgroundEnabled() {
        try (MockedStatic<PropertyUtil> propMock = mockStatic(PropertyUtil.class)) {
            // NOTE: stubbing getPropertyBooleanValue(anyString(), anyString()) to always return true
            // also matches the internal "jobs.dashboard.https.enabled" check, forcing ensureStarted()
            // down the HTTPS-dashboard branch, which then reads unstubbed cert/key file path
            // properties (a different overload) as null and NPEs inside SSLUtils.createKeyManagerFromP12.
            // Scope the "true" stub to just the two properties this test actually means to enable, and
            // leave "jobs.dashboard.https.enabled" unstubbed so it falls back to its default of false.
            propMock.when(() -> PropertyUtil.getPropertyBooleanValue(eq("jobs.dashboard.enabled"), anyString()))
                    .thenReturn(true);
            propMock.when(() -> PropertyUtil.getPropertyBooleanValue(eq("jobs.background.enabled"), anyString()))
                    .thenReturn(true);
            // Job Runner UI Keycloak auth is a separate opt-in feature; keep it disabled here so
            // this test continues to exercise the plain (unauthenticated) dashboard path.
            propMock.when(() -> PropertyUtil.getPropertyBooleanValue(eq("jobs.dashboard.auth.enabled"), anyString()))
                    .thenReturn(false);
            propMock.when(() -> PropertyUtil.getPropertyValue(anyString(), anyString()))
                    .thenReturn("memory");
            propMock.when(() -> PropertyUtil.getPropertyIntValue(anyString(), anyString()))
                    .thenReturn(freePort()); // use OS-assigned free port — avoids BindException

            DefaultJobSchedulerProvider provider = new DefaultJobSchedulerProvider();
            provider.ensureStarted();
            assertNotNull(provider.getJobScheduler());
            provider.stop();
        }
    }

    @Test
    void ensureStarted_withDashboardAuthEnabled_rejectsRequestsWithoutBearerToken() throws Exception {
        int dashboardPort = freePort();
        int gatewayPort = freePort();

        // VaultTlsSupport.isVaultTlsEnabled() defaults to true when 'vault.tls.enabled' is unset -
        // disable it explicitly via a common-config file so this test exercises the legacy
        // client.truststoreFilePath path (unset here) rather than the Vault-backed trust source.
        File commonConfig = Files.createTempFile("common-config", ".properties").toFile();
        try (FileOutputStream out = new FileOutputStream(commonConfig)) {
            Properties vaultDisabled = new Properties();
            vaultDisabled.setProperty("vault.tls.enabled", "false");
            vaultDisabled.store(out, null);
        }

        Properties props = new Properties();
        props.setProperty("jobs.dashboard.enabled", "true");
        props.setProperty("jobs.background.enabled", "false");
        props.setProperty("jobs.dashboard.port", String.valueOf(dashboardPort));
        props.setProperty("jobs.dashboard.auth.enabled", "true");
        props.setProperty("jobs.dashboard.auth.issuer.url", "https://keycloak.invalid/realms/test");
        props.setProperty("jobs.dashboard.auth.port", String.valueOf(gatewayPort));
        props.setProperty("common.configuration", commonConfig.getAbsolutePath());

        PropertyUtil.clear();
        File authProps = Files.createTempFile("auth-props", ".properties").toFile();
        try (FileOutputStream out = new FileOutputStream(authProps)) {
            props.store(out, null);
        }
        PropertyUtil.init(authProps);

        DefaultJobSchedulerProvider provider = new DefaultJobSchedulerProvider();
        try {
            provider.ensureStarted();

            // The dashboard's own port never moves; the auth gateway sits in front on its own
            // dedicated port and must reject unauthenticated requests there.
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/"))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
        } finally {
            provider.stop();
            authProps.delete();
            commonConfig.delete();
        }
    }

    @Test
    void ensureStarted_withDashboardAuthEnabledAndVaultTls_usesVaultTrustManagers() throws Exception {
        int dashboardPort = freePort();
        int gatewayPort = freePort();

        // vault.tls.enabled defaults to true, so no common-config override is needed here; mock
        // VaultTlsSupport itself so this doesn't require a real Vault (mirrors the approach used
        // in GRPCUtilsTest for the same isVaultTlsEnabled() switch).
        Properties props = new Properties();
        props.setProperty("jobs.dashboard.enabled", "true");
        props.setProperty("jobs.background.enabled", "false");
        props.setProperty("jobs.dashboard.port", String.valueOf(dashboardPort));
        props.setProperty("jobs.dashboard.auth.enabled", "true");
        props.setProperty("jobs.dashboard.auth.issuer.url", "https://keycloak.invalid/realms/test");
        props.setProperty("jobs.dashboard.auth.port", String.valueOf(gatewayPort));

        PropertyUtil.clear();
        File authProps = Files.createTempFile("auth-props", ".properties").toFile();
        try (FileOutputStream out = new FileOutputStream(authProps)) {
            props.store(out, null);
        }
        PropertyUtil.init(authProps);

        DefaultJobSchedulerProvider provider = new DefaultJobSchedulerProvider();
        try (MockedStatic<uk.gov.dbt.ndtp.federator.common.service.secret.VaultTlsSupport> vaultMock =
                mockStatic(uk.gov.dbt.ndtp.federator.common.service.secret.VaultTlsSupport.class)) {
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);

            vaultMock.when(uk.gov.dbt.ndtp.federator.common.service.secret.VaultTlsSupport::isVaultTlsEnabled)
                    .thenReturn(true);
            vaultMock.when(uk.gov.dbt.ndtp.federator.common.service.secret.VaultTlsSupport::trustManagers)
                    .thenReturn(tmf.getTrustManagers());

            provider.ensureStarted();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/"))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
            vaultMock.verify(uk.gov.dbt.ndtp.federator.common.service.secret.VaultTlsSupport::trustManagers);
        } finally {
            provider.stop();
            authProps.delete();
        }
    }

    @Test
    void removeRecurringJob_callsScheduler() {
        JobScheduler scheduler = mock(JobScheduler.class);
        DefaultJobSchedulerProvider provider =
                DefaultJobSchedulerProvider.withDependencies(scheduler, mock(AbstractStorageProvider.class), null);
        provider.removeRecurringJob("test-job");
        verify(scheduler).deleteRecurringJob("test-job");
    }

    @Test
    void extractJobParamsFrom_coverage() throws Exception {
        AbstractStorageProvider storage = mock(AbstractStorageProvider.class);
        RecurringJob recurringJob = mock(RecurringJob.class);
        JobDetails details = mock(JobDetails.class);
        JobParams params = new JobParams();
        params.setJobId("test");

        org.jobrunr.storage.RecurringJobsResult recurringJobsResult =
                mock(org.jobrunr.storage.RecurringJobsResult.class);
        when(storage.getRecurringJobs()).thenReturn(recurringJobsResult);
        when(recurringJobsResult.iterator())
                .thenReturn(java.util.Collections.singletonList(recurringJob).iterator());

        when(recurringJob.getId()).thenReturn("test");
        when(recurringJob.getJobDetails()).thenReturn(details);
        when(details.getJobParameters())
                .thenAnswer(inv -> java.util.Collections.singletonList(new JobParameter(params)));

        DefaultJobSchedulerProvider provider =
                DefaultJobSchedulerProvider.withDependencies(mock(JobScheduler.class), storage, null);

        // Access private method extractJobParamsFrom
        java.lang.reflect.Method method =
                DefaultJobSchedulerProvider.class.getDeclaredMethod("extractJobParamsFrom", RecurringJob.class);
        method.setAccessible(true);
        JobParams extracted = (JobParams) method.invoke(provider, recurringJob);

        assertNotNull(extracted);
        assertEquals("test", extracted.getJobId());
    }

    @Test
    void extractJobParamsFrom_failure_cases() throws Exception {
        AbstractStorageProvider storage = mock(AbstractStorageProvider.class);
        RecurringJob recurringJob = mock(RecurringJob.class);

        org.jobrunr.storage.RecurringJobsResult recurringJobsResult =
                mock(org.jobrunr.storage.RecurringJobsResult.class);
        when(storage.getRecurringJobs()).thenReturn(recurringJobsResult);
        when(recurringJobsResult.iterator())
                .thenReturn(java.util.Collections.singletonList(recurringJob).iterator());

        when(recurringJob.getId()).thenReturn("fail");
        when(recurringJob.getJobDetails()).thenThrow(new RuntimeException("fail"));

        DefaultJobSchedulerProvider provider =
                DefaultJobSchedulerProvider.withDependencies(mock(JobScheduler.class), storage, null);

        // Access private method extractJobParamsFrom
        java.lang.reflect.Method method =
                DefaultJobSchedulerProvider.class.getDeclaredMethod("extractJobParamsFrom", RecurringJob.class);
        method.setAccessible(true);
        JobParams extracted = (JobParams) method.invoke(provider, recurringJob);

        assertNull(extracted);
    }
}
