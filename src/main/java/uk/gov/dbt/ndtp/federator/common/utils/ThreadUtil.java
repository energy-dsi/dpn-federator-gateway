// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
 *  Modifications made by the National Digital Twin Programme (NDTP)
 *  © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
 *  and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.common.utils;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for handling threads.
 */
public class ThreadUtil {

    public static final Logger LOGGER = LoggerFactory.getLogger("ThreadUtil");

    private ThreadUtil() {}

    public static void awaitShutdown(
            List<Future<?>> futureList, AutoCloseable process, ExecutorService threadExecutor) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down...");
            for (Future<?> future : futureList) {
                try {
                    future.cancel(true);
                } catch (Exception t) {
                    LOGGER.error("Exception occurred", t);
                }
            }
            try {
                process.close();
                threadExecutor.shutdown();
            } catch (Exception e) {
                LOGGER.info("Exception occurred during shutdown, ignoring.", e);
            }
        }));
        awaitFutures(futureList);
    }

    public static void awaitFutures(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
                LOGGER.info("Future processed: {}", future);
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.error("Error in processing: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * DSI EDIT: returns an executor that propagates the current gRPC/OTel Context to every
     * submitted task. Without this, trace_id/span_id appear blank for log lines that run on
     * threads from this pool, even when the work originated from inside an actively-traced
     * gRPC call - plain ExecutorService.submit(...) does not carry thread-local context across
     * the handoff to a worker thread.
     *
     * <p>io.grpc.Context.currentContextExecutor(...) is the standard gRPC utility for this.
     * It works for OTel spans too (not just gRPC's own deadline/cancellation context) because
     * opentelemetry-grpc-1.6's instrumentation installs a Context.Storage bridge that makes
     * io.grpc.Context and the OTel Context refer to each other - so wrapping with gRPC's own
     * propagation utility also correctly carries the active span.
     */
    /**
     * DSI EDIT: returns an executor that propagates the current gRPC/OTel Context to every
     * submitted task. Without this, trace_id/span_id appear blank for log lines that run on
     * threads from this pool, even when the work originated from inside an actively-traced
     * gRPC call - plain ExecutorService.submit(...) does not carry thread-local context across
     * the handoff to a worker thread.
     *
     * <p>Wraps each task with the gRPC Context active at submission time. This also carries
     * OTel spans because opentelemetry-grpc-1.6's instrumentation installs a Context.Storage
     * bridge making io.grpc.Context and the OTel Context refer to each other - so capturing the
     * gRPC Context here also captures whichever OTel span is current.
     */
    public static ExecutorService threadExecutor(String threadNamePrefix) {
        ExecutorService delegate = Executors.newCachedThreadPool(new ThreadFactoryWithNamePrefix(threadNamePrefix));
        return new ContextPropagatingExecutorService(delegate);
    }

    /**
     * Minimal ExecutorService decorator that captures io.grpc.Context.current() at the moment
     * each task is submitted, and restores it on the worker thread for the duration of that
     * task - preserving ExecutorService's full API (submit/invokeAll/shutdown/etc.), unlike
     * io.grpc.Context.currentContextExecutor(...) which only returns a plain Executor.
     */
    private static final class ContextPropagatingExecutorService extends AbstractExecutorService {
        private final ExecutorService delegate;

        private ContextPropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(io.grpc.Context.current().wrap(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
