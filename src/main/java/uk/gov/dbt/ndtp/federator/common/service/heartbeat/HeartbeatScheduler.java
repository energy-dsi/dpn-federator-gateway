package uk.gov.dbt.ndtp.federator.common.service.heartbeat;

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.common.service.config.ProducerConfigService;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.ThreadFactoryWithNamePrefix;

/**
 * Periodically calls the Management Node's "get producer config" endpoint
 * (via {@link ProducerConfigService}) so that this Federator server announces
 * its presence/liveness to the Management Node at a regular cadence.
 * <p>
 * The first call is made as soon as the scheduler is {@link #start() started}
 * (i.e. on server startup), followed by further calls at a fixed interval
 * defined by {@code federator.heartbeat.interval} (ISO-8601 duration, e.g.
 * {@code PT30S}).
 * <p>
 * Each tick calls {@link ProducerConfigService#refreshConfigurations()} which
 * clears the in-memory cache and re-fetches from the Management Node, so as
 * long as {@code federator.heartbeat.interval} is set greater than
 * {@code management.node.cache.ttl.seconds}, every heartbeat results in a
 * genuine outbound HTTP call rather than a cache hit.
 */
@Slf4j
public class HeartbeatScheduler implements Closeable {

    private static final String INTERVAL_PROP = "server.heartbeat.interval";
    private static final String DEFAULT_INTERVAL = "PT30S";
    private static final String ENABLED_PROP = "server.heartbeat.enabled";
    private static final String DEFAULT_ENABLED = "true";
    private static final String CACHE_TTL_PROP = "management.node.cache.ttl.seconds";

    private final ProducerConfigService producerConfigService;
    private final ScheduledExecutorService executorService;
    private final Duration interval;
    private final boolean enabled;
    private ScheduledFuture<?> scheduledFuture;

    /**
     * Constructs the scheduler. Does not start any scheduling until {@link #start()} is called.
     *
     * @param producerConfigService service used to ping the Management Node's producer config endpoint
     */
    public HeartbeatScheduler(final ProducerConfigService producerConfigService) {
        this.producerConfigService = producerConfigService;
        this.interval = PropertyUtil.getPropertyDurationValue(INTERVAL_PROP, DEFAULT_INTERVAL);
        this.enabled = PropertyUtil.getPropertyBooleanValue(ENABLED_PROP, DEFAULT_ENABLED);
        this.executorService =
                Executors.newSingleThreadScheduledExecutor(new ThreadFactoryWithNamePrefix("Heartbeat"));
        warnIfIntervalNotGreaterThanCacheTtl();
    }

    /**
     * Starts the heartbeat: fires immediately, then again every {@link #interval}.
     * No-op if {@code federator.heartbeat.enabled} is false.
     */
    public void start() {
        if (!enabled) {
            log.info("Heartbeat disabled via {}=false; not scheduling producer config pings.", ENABLED_PROP);
            return;
        }
        log.info("Starting heartbeat to Management Node every {} (initial call fires immediately).", interval);
        scheduledFuture = executorService.scheduleAtFixedRate(
                this::sendHeartbeat, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        try {
            producerConfigService.refreshConfigurations();
            log.debug("Heartbeat to Management Node succeeded.");
        } catch (Exception e) {
            // Never let a failed heartbeat kill the scheduled task loop.
            log.warn("Heartbeat to Management Node failed: {}", e.getMessage(), e);
        }
    }

    private void warnIfIntervalNotGreaterThanCacheTtl() {
        long ttlSeconds = PropertyUtil.getPropertyLongValue(CACHE_TTL_PROP, "3600");
        if (interval.getSeconds() <= ttlSeconds) {
            log.warn(
                    "{} ({}s) is not greater than {} ({}s); some heartbeat ticks may be served from cache "
                            + "instead of reaching the Management Node.",
                    INTERVAL_PROP,
                    interval.getSeconds(),
                    CACHE_TTL_PROP,
                    ttlSeconds);
        }
    }

    /**
     * Stops the heartbeat and releases the scheduler thread. Safe to call multiple times.
     */
    @Override
    public void close() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        executorService.shutdown();
        log.info("Heartbeat scheduler stopped.");
    }
}
