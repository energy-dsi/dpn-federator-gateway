package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.Test;

/**
 * StallGuardedCopyTest
 *
 * <p>The stall scenario here mirrors what was found stuck in production thread dumps: a read
 * parked in {@code BlockingQueue.take()} (as the JDK HttpClient's internal
 * {@code HttpResponseInputStream} does) that never receives more data and is never closed by the
 * sender. An earlier version of {@link StallGuardedCopy} only closed the stream from the watchdog,
 * which did not reliably unblock that kind of read; threads were observed still parked there many
 * minutes past the configured stall timeout. This test fails fast (instead of hanging the test
 * run) if that regresses.
 */
class StallGuardedCopyTest {

    @Test
    void test_copy_unblocksViaInterrupt_whenUpstreamStallsMidBody() {
        // given: a stream whose read() blocks forever once the queue is drained, and which -
        // like the real JDK HttpResponseInputStream - propagates an interrupt received while
        // blocked as an unchecked exception rather than IOException
        BlockingQueue<Integer> upstream = new ArrayBlockingQueue<>(1);
        InputStream stalledUpstream = new InputStream() {
            @Override
            public int read() {
                try {
                    return upstream.take();
                } catch (InterruptedException e) {
                    throw new RuntimeException("blocked read interrupted", e);
                }
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Duration stallTimeout = Duration.ofMillis(300);

        // when
        long start = System.nanoTime();
        IOException thrown = assertThrows(
                IOException.class, () -> StallGuardedCopy.copy(stalledUpstream, out, stallTimeout, false));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // then: it must fail shortly after the stall timeout, not hang (the production bug
        // manifested as threads still blocked 15+ minutes later), and the failure should be
        // attributed to the stall rather than surfacing as an opaque RuntimeException.
        // The watchdog polls every 5s regardless of stallTimeout (irrelevant against the 60s
        // timeouts used in production, but it dominates this test's much shorter timeout), so
        // the bound here is "one poll tick plus slack", not the stall timeout itself.
        assertTrue(
                elapsedMs < 8000,
                "copy() should unblock within a poll tick of the " + stallTimeout + " stall timeout, took "
                        + elapsedMs + "ms");
        assertTrue(
                thrown.getMessage() != null && thrown.getMessage().contains("stalled"),
                "expected a stall-specific message, got: " + thrown.getMessage());
    }

    @Test
    void test_copy_copiesAllBytes_whenUpstreamNeverStalls() throws IOException {
        // given
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // when
        StallGuardedCopy.copy(in, out, Duration.ofSeconds(5), false);

        // then
        assertArrayEquals(data, out.toByteArray());
    }
}
