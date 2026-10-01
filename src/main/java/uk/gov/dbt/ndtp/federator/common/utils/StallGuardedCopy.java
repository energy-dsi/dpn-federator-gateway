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
 */
public final class StallGuardedCopy {

    private static final Logger LOG = LoggerFactory.getLogger(StallGuardedCopy.class);

    private StallGuardedCopy() {}

    /**
     * Copies {@code in} to {@code out} in 8KB chunks, closing {@code in} (which unblocks any in-progress
     * read) if {@code stallTimeout} elapses without a successful read.
     *
     * @param flushEachChunk whether to flush {@code out} after every chunk - needed for responses streamed
     *                       incrementally to a browser (e.g. SSE), where buffering until the chunk fills
     *                       would delay delivery
     */
    public static void copy(InputStream in, OutputStream out, Duration stallTimeout, boolean flushEachChunk)
            throws IOException {
        AtomicLong lastProgressNanos = new AtomicLong(System.nanoTime());
        Thread watchdog = Thread.ofVirtual().start(() -> watch(in, stallTimeout, lastProgressNanos));
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
        } finally {
            watchdog.interrupt();
        }
    }

    private static void watch(InputStream in, Duration stallTimeout, AtomicLong lastProgressNanos) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(Duration.ofSeconds(5));
                long idle = System.nanoTime() - lastProgressNanos.get();
                if (idle > stallTimeout.toNanos()) {
                    LOG.warn(
                            "Upstream response body stalled for over {} with no data - closing the connection to "
                                    + "free the handler thread instead of blocking forever",
                            stallTimeout);
                    in.close();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Best effort - the read loop will surface the close as an IOException of its own.
        }
    }
}
