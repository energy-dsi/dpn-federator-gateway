package uk.gov.dbt.ndtp.federator.common.service.heartbeat;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.dbt.ndtp.federator.common.service.config.ProducerConfigService;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;

class HeartbeatSchedulerTest {

    @TempDir
    File tempDir;

    private ProducerConfigService producerConfigService;

    @BeforeEach
    void setUp() {
        PropertyUtil.clear();
        producerConfigService = mock(ProducerConfigService.class);
    }

    @AfterEach
    void tearDown() {
        PropertyUtil.clear();
    }

    private void initPropertiesWith(final String interval, final String enabled) throws Exception {
        Properties props = new Properties();
        props.setProperty("server.heartbeat.interval", interval);
        props.setProperty("server.heartbeat.enabled", enabled);
        props.setProperty("management.node.cache.ttl.seconds", "60");

        File propFile = new File(tempDir, "heartbeat-test.properties");
        try (FileOutputStream out = new FileOutputStream(propFile)) {
            props.store(out, null);
        }
        PropertyUtil.init(propFile);
    }

    @Test
    void start_firesImmediatelyAndThenOnInterval() throws Exception {
        initPropertiesWith("PT0.1S", "true");

        try (HeartbeatScheduler scheduler = new HeartbeatScheduler(producerConfigService)) {
            scheduler.start();
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(producerConfigService, atLeast(2)).refreshConfigurations());
        }
    }

    @Test
    void start_doesNothing_whenDisabled() throws Exception {
        initPropertiesWith("PT0.1S", "false");

        try (HeartbeatScheduler scheduler = new HeartbeatScheduler(producerConfigService)) {
            scheduler.start();
            Thread.sleep(300);
            verify(producerConfigService, never()).refreshConfigurations();
        }
    }

    @Test
    void sendHeartbeat_swallowsException_andContinuesScheduling() throws Exception {
        doThrow(new RuntimeException("boom")).when(producerConfigService).refreshConfigurations();
        initPropertiesWith("PT0.1S", "true");

        try (HeartbeatScheduler scheduler = new HeartbeatScheduler(producerConfigService)) {
            scheduler.start();
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(producerConfigService, atLeast(2)).refreshConfigurations());
        }
    }
}
