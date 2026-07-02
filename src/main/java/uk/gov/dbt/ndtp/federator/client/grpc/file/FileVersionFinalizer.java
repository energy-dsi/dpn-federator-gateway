// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.grpc.file;

import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.client.grpc.file.FileVersionPlanner.CollectedFile;
import uk.gov.dbt.ndtp.federator.client.grpc.file.FileVersionPlanner.Plan;
import uk.gov.dbt.ndtp.federator.client.grpc.file.FileVersionPlanner.PlannedFile;
import uk.gov.dbt.ndtp.federator.client.storage.ReceivedFileStorage;
import uk.gov.dbt.ndtp.federator.client.storage.ReceivedFileStorageFactory;
import uk.gov.dbt.ndtp.federator.client.storage.StoredFileResult;
import uk.gov.dbt.ndtp.federator.client.storage.impl.GCPReceivedFileStorage;
import uk.gov.dbt.ndtp.federator.client.storage.impl.S3ReceivedFileStorage;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;

/**
 * Finalizes a collected file stream: applies versioning (single → no suffix, multiple → v1, v2, v3 …
 * continuing from the persisted Redis counter), stores each staged file via the configured provider,
 * and persists the new counter. Run once, after the whole stream has been collected.
 *
 * <p>The per-product Redis counter lives under {@code fileversion:{productKey}}. Clearing Redis removes
 * it, so the next full re-pull starts again from v1.
 */
@Slf4j
public final class FileVersionFinalizer {

    private static final String COUNTER_PREFIX = "fileversion:";

    private FileVersionFinalizer() {}

    /**
     * Stores all collected files under their versioned/unversioned names and persists the counter.
     *
     * @param collected   files gathered from the stream (in arrival order)
     * @param destination configured per-product destination
     * @return the highest sequence id stored (for offset advancement), or -1 if nothing was stored
     */
    public static long finalizeStream(List<CollectedFile> collected, String destination) {
        if (collected == null || collected.isEmpty()) {
            return -1L;
        }

        ReceivedFileStorage storage = ReceivedFileStorageFactory.get();
        RedisUtil redis = RedisUtil.getInstance();

        String counterKey = COUNTER_PREFIX + DestinationVersioning.productKey(destination);
        long startCounter = readCounter(redis, counterKey);

        Plan plan = FileVersionPlanner.plan(collected, destination, startCounter);

        long lastStoredSeq = -1L;
        for (PlannedFile pf : plan.files()) {
            Path staged = Path.of(pf.stagedPath());
            StoredFileResult result = storage.store(staged, pf.sourceFileName(), pf.finalDestination());

            boolean remoteProvider =
                    storage instanceof S3ReceivedFileStorage || storage instanceof GCPReceivedFileStorage;
            if (remoteProvider && result.remoteUriOpt().isEmpty()) {
                // Upload failed; stop here so the counter/offset are not advanced past a missing file.
                log.warn(
                        "Upload failed for file '{}' -> '{}'. Halting finalize before persisting counter/offset.",
                        pf.sourceFileName(),
                        pf.finalDestination());
                return lastStoredSeq;
            }
            result.remoteUriOpt().ifPresent(uri -> log.info("Stored file at remote location: {}", uri));
            log.info("Stored '{}' as '{}'", pf.sourceFileName(), pf.finalDestination());
            lastStoredSeq = Math.max(lastStoredSeq, pf.sequenceId());
        }

        // Persist the advanced counter only after all files in the stream stored successfully.
        redis.setValue(counterKey, plan.newCounter());
        log.info("Persisted file version counter '{}' = {}", counterKey, plan.newCounter());

        return lastStoredSeq;
    }

    private static long readCounter(RedisUtil redis, String counterKey) {
        try {
            Long current = redis.getValue(counterKey, Long.class, false);
            return current == null ? 0L : current;
        } catch (Exception e) {
            // Missing key / cleared Redis -> start from 0 (versioning restarts at v1).
            log.debug("No existing version counter for '{}'; starting at 0", counterKey);
            return 0L;
        }
    }
}
