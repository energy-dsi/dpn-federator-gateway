/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.common.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies an {@link InputStream} to an {@link OutputStream}, aborting the copy if no bytes arrive within
 * {@code stallTimeout}.
 *
 * <p>{@link java.net.http.HttpRequest.Builder#timeout} only bounds the wait for response headers, not the
 * time spent reading the body afterward. A response-body read ({@code InputStream.read}/{@code transferTo})
 * has no timeout of its own, so if a sender starts a response and then stalls mid-body, that read blocks
 * forever - permanently leaking the thread performing the copy and whatever client-facing resource (e.g. an
 * {@code HttpExchange}) is waiting on it to finish.
 *
 * <p>The blocked read observed in production is the JDK HttpClient's
 * {@code jdk.internal.net.http.ResponseSubscribers$HttpResponseInputStream} parked in
 * {@code ArrayBlockingQueue.take()}. Closing that stream from another thread did not reliably unblock it
 * (threads were observed still parked there many minutes past the stall timeout). Interrupting the thread
 * performing the read does: {@code BlockingQueue.take()} is contractually guaranteed by the JDK to throw
 * {@link InterruptedException} as soon as the waiting thread is interrupted, independent of whatever
 * {@code close()} does internally. This class does both - interrupt first, close second - and treats
 * whatever failure shape results (checked or unchecked) as a clean {@link IOException} when the stall
 * fired, so callers don't need to know which one actually unblocked the read.
 *
 * <p>This guarantee matters beyond just this one copy call: callers that gate concurrent access to this
 * copy with a shared resource (e.g. a {@link java.util.concurrent.Semaphore} limiting how many proxy
 * requests are in flight at once) depend on every acquisition being released within a bounded time. Without
 * a reliable stall timeout, a single stuck upstream response would hold such a permit forever, and that
 * loss would be permanent and cumulative - eventually exhausting the shared pool entirely and hanging every
 * future request, not just the stalled one.
 */
public final class StallGuardedCopy {

    private static final Logger LOG = LoggerFactory.getLogger(StallGuardedCopy.class);

    private StallGuardedCopy() {}

    /**
     * Copies {@code in} to {@code out} in 8KB chunks, interrupting the calling thread and closing
     * {@code in} if {@code stallTimeout} elapses without a successful read.
     *
     * @param flushEachChunk whether to flush {@code out} after every chunk - needed for responses streamed
     *                       incrementally to a browser (e.g. SSE), where buffering until the chunk fills
     *                       would delay delivery
     */
    public static void copy(InputStream in, OutputStream out, Duration stallTimeout, boolean flushEachChunk)
            throws IOException {
        Thread copyThread = Thread.currentThread();
        AtomicLong lastProgressNanos = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean(false);
        Thread watchdog =
                Thread.ofVirtual().start(() -> watch(in, copyThread, stallTimeout, lastProgressNanos, stalled));
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (flushEachChunk) {
                    out.flush();
                }
                lastProgressNanos.set(System.nanoTime());
            }
        } catch (Throwable t) {
            if (stalled.get()) {
                throw new IOException(
                        "Upstream response body stalled for over " + stallTimeout + " with no data", t);
            }
            if (t instanceof IOException ioe) {
                throw ioe;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw (RuntimeException) t;
        } finally {
            watchdog.interrupt();
            // This thread is pooled (it's an HttpExchange handler thread); clear any interrupt status the
            // watchdog raced in setting so it doesn't leak into whatever task this thread picks up next.
            Thread.interrupted();
        }
    }

    private static void watch(
            InputStream in, Thread copyThread, Duration stallTimeout, AtomicLong lastProgressNanos, AtomicBoolean stalled) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(Duration.ofSeconds(5));
                long idle = System.nanoTime() - lastProgressNanos.get();
                if (idle > stallTimeout.toNanos()) {
                    stalled.set(true);
                    LOG.warn(
                            "Upstream response body stalled for over {} with no data - interrupting the handler "
                                    + "thread and closing the connection to free it instead of blocking forever",
                            stallTimeout);
                    copyThread.interrupt();
                    try {
                        in.close();
                    } catch (Throwable t) {
                        LOG.warn(
                                "Closing the stalled upstream stream threw - the interrupt above should still "
                                        + "unblock the handler thread",
                                t);
                    }
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
