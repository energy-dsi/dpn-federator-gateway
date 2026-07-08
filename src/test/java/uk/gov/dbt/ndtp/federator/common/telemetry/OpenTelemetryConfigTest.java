// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenTelemetryConfig. Before this class existed, OpenTelemetryConfig had no
 * dedicated tests at all (only incidental coverage from other tests calling get()), which is why
 * coverage showed 14% methods / 9% lines despite the class doing meaningful work.
 *
 * <p>{@code openTelemetry} is a static singleton field with an early-return once set, so tests
 * reset it via reflection in {@link #resetStaticState()} to avoid ordering-dependent failures.
 *
 * <p>{@link OpenTelemetryConfig#applyLegacyEnvVarCompatibility()} reads System.getenv(...)
 * directly. Without changing production code, its translation branches (SERVICE_NAME,
 * SERVICE_VERSION, ENVIRONMENT) can't be exercised with specific fake values here - Mockito
 * refuses to mock {@code System.class} outright, and mutating real env vars needs junit-pioneer
 * plus {@code --add-opens java.base/java.lang=ALL-UNNAMED} on the test JVM. Only the "no legacy
 * vars set" no-op path is covered below, via reflection on the real private method, guarded by
 * {@code assumeTrue} against the actual running environment.
 */
class OpenTelemetryConfigTest {

    @AfterEach
    void resetStaticState() throws Exception {
        resetOpenTelemetryField();
        System.clearProperty("otel.service.name");
        System.clearProperty("otel.resource.attributes");
        System.clearProperty("otel.sdk.disabled");
        System.clearProperty("otel.exporter.otlp.protocol");
    }

    private static void resetOpenTelemetryField() throws Exception {
        Field field = OpenTelemetryConfig.class.getDeclaredField("openTelemetry");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static String invokeResolveComponentName() throws Exception {
        Method method = OpenTelemetryConfig.class.getDeclaredMethod("resolveComponentName");
        method.setAccessible(true);
        return (String) method.invoke(null);
    }

    // --- get() -----------------------------------------------------------------------------

    @Test
    void get_throwsIllegalStateException_whenNotInitialized() throws Exception {
        resetOpenTelemetryField();

        IllegalStateException ex = assertThrows(IllegalStateException.class, OpenTelemetryConfig::get);
        assertEquals(
                "OpenTelemetryConfig.initialize() must be called before OpenTelemetryConfig.get()",
                ex.getMessage());
    }

    @Test
    void get_returnsSameInstanceInitializeReturned() {
        System.setProperty("otel.sdk.disabled", "true");

        OpenTelemetry initialized = OpenTelemetryConfig.initialize();

        assertSame(initialized, OpenTelemetryConfig.get());
    }

    // --- initialize() ------------------------------------------------------------------------

    @Test
    void initialize_isIdempotent_secondCallReturnsSameInstance() {
        System.setProperty("otel.sdk.disabled", "true");

        OpenTelemetry first = OpenTelemetryConfig.initialize();
        OpenTelemetry second = OpenTelemetryConfig.initialize();

        assertSame(first, second, "initialize() should short-circuit on the second call");
    }

    @Test
    void initialize_succeedsWithSdkDisabled() {
        // otel.sdk.disabled=true lets autoconfigure succeed deterministically with no-op
        // providers and no real network call, exercising the success (try) branch including
        // OpenTelemetryAppender.install(...) and the shutdown hook registration.
        System.setProperty("otel.sdk.disabled", "true");

        OpenTelemetry result = OpenTelemetryConfig.initialize();

        assertNotNull(result);
    }

    @Test
    void initialize_fallsBackToNoop_whenAutoconfigureRejectsConfig() {
        // An unrecognised OTLP protocol value makes AutoConfiguredOpenTelemetrySdk.initialize()
        // throw during config validation, exercising the catch(Throwable) fallback branch. If
        // your OTel version stops validating this eagerly, swap for another value guaranteed to
        // fail synchronously.
        System.setProperty("otel.exporter.otlp.protocol", "not-a-real-protocol");

        OpenTelemetry result = OpenTelemetryConfig.initialize();

        assertNotNull(result, "should fall back to OpenTelemetry.noop() rather than throw");
    }

    // --- resolveComponentName() --------------------------------------------------------------

    @Test
    void resolveComponentName_prefersOtelServiceNameSystemProperty() throws Exception {
        System.setProperty("otel.service.name", "federator-client");

        assertEquals("federator-client", invokeResolveComponentName());
    }

    @Test
    void resolveComponentName_fallsBackToResourceAttributesSystemProperty() throws Exception {
        assumeTrue(System.getProperty("otel.service.name") == null);
        System.setProperty(
                "otel.resource.attributes",
                "service.version=1.2.3,service.name=federator-server,deployment.environment=dev");

        assertEquals("federator-server", invokeResolveComponentName());
    }

    @Test
    void resolveComponentName_resourceAttributesWithoutServiceNameFallsThroughToDefault()
            throws Exception {
        assumeTrue(System.getenv("OTEL_SERVICE_NAME") == null, "OTEL_SERVICE_NAME set in env");
        System.setProperty("otel.resource.attributes", "deployment.environment=dev");

        assertEquals("dpn-federator-gateway", invokeResolveComponentName());
    }

    @Test
    void resolveComponentName_defaultsWhenNothingConfigured() throws Exception {
        // Relies on OTEL_SERVICE_NAME / OTEL_RESOURCE_ATTRIBUTES not being set in the environment
        // running this test - typical for local/CI runs, but add junit-pioneer's
        // @ClearEnvironmentVariable if your pipeline ever sets these globally.
        assumeTrue(System.getenv("OTEL_SERVICE_NAME") == null, "OTEL_SERVICE_NAME set in env");
        assumeTrue(System.getenv("OTEL_RESOURCE_ATTRIBUTES") == null, "OTEL_RESOURCE_ATTRIBUTES set in env");

        assertEquals("dpn-federator-gateway", invokeResolveComponentName());
    }

    // --- applyLegacyEnvVarCompatibility() -----------------------------------------------------
    // This method reads System.getenv(...) directly. Without changing OpenTelemetryConfig.java,
    // there is no way to control what it returns: Mockito refuses to mock java.lang.System
    // outright ("It is not possible to mock static methods of java.lang.System"), and mutating
    // real env vars (junit-pioneer, System Rules, etc.) needs the test JVM launched with
    // --add-opens java.base/java.lang=ALL-UNNAMED, which this project is avoiding. So the only
    // honest test achievable here, without touching production code or JVM args, is invoking the
    // real method via reflection and asserting against whatever the actual environment is - this
    // only exercises the "no legacy vars set" no-op path, guarded by assumeTrue so it skips
    // rather than gives a false result if your environment happens to set these.
    //
    // To properly cover the SERVICE_NAME/SERVICE_VERSION/ENVIRONMENT translation branches, one of
    // the following would be required: (a) extract the env lookup into an injectable parameter
    // in OpenTelemetryConfig.java, (b) add junit-pioneer + --add-opens on the test JVM, or (c)
    // add a legacy-style env-mocking library. All three were ruled out per your last two
    // requests, so those branches are left uncovered here.

    @Test
    void applyLegacyEnvVarCompatibility_isNoOpWhenNoLegacyVarsSetInRealEnvironment() throws Exception {
        assumeTrue(System.getenv("SERVICE_NAME") == null, "SERVICE_NAME set in real environment");
        assumeTrue(System.getenv("SERVICE_VERSION") == null, "SERVICE_VERSION set in real environment");
        assumeTrue(System.getenv("ENVIRONMENT") == null, "ENVIRONMENT set in real environment");

        invokeApplyLegacyEnvVarCompatibility();

        assertNull(System.getProperty("otel.service.name"));
        assertNull(System.getProperty("otel.resource.attributes"));
    }

    private static void invokeApplyLegacyEnvVarCompatibility() throws Exception {
        Method method = OpenTelemetryConfig.class.getDeclaredMethod("applyLegacyEnvVarCompatibility");
        method.setAccessible(true);
        method.invoke(null);
    }
}
