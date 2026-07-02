// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.grpc.file;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides the final destination name for every file collected from a single file stream, applying the
 * versioning rules:
 *
 * <ul>
 *   <li><b>Single file</b> (list size 1) → stored without a version suffix; the counter is not advanced.</li>
 *   <li><b>Multiple files</b> (list size &gt;= 2) → each file gets {@code v{n}} where {@code n} continues
 *       from the persisted Redis counter (last counter 3 → next pull v4, v5 …).</li>
 * </ul>
 *
 * <p>Pure and stateless: callers supply the collected files and the current counter, and receive the
 * planned mappings plus the new counter value to persist. Storage and Redis I/O happen elsewhere.
 */
public final class FileVersionPlanner {

    private FileVersionPlanner() {}

    /** A single file's planned final destination. */
    public record PlannedFile(long sequenceId, String sourceFileName, String stagedPath, String finalDestination) {}

    /** The full plan: per-file destinations and the counter value to persist after storing. */
    public record Plan(List<PlannedFile> files, long newCounter) {}

    /**
     * Plans destinations for the collected files.
     *
     * @param files        ordered files collected from the stream (arrival order preserved)
     * @param destination  configured per-product destination (e.g. {@code jsonschema-testorg-sampleproduct-v1.json})
     * @param startCounter last persisted counter for this product (0 if none / Redis cleared)
     * @return the plan, including the new counter to persist
     */
    public static Plan plan(List<CollectedFile> files, String destination, long startCounter) {
        List<PlannedFile> planned = new ArrayList<>();

        if (files == null || files.isEmpty()) {
            return new Plan(planned, startCounter);
        }

        if (files.size() == 1) {
            // Single file → no version; counter unchanged.
            CollectedFile only = files.get(0);
            planned.add(new PlannedFile(
                    only.sequenceId(),
                    only.sourceFileName(),
                    only.stagedPath(),
                    DestinationVersioning.unversionedDestination(destination, only.sourceFileName())));
            return new Plan(planned, startCounter);
        }

        // Multiple files → version each, continuing from the persisted counter.
        long n = startCounter;
        for (CollectedFile f : files) {
            n++;
            planned.add(new PlannedFile(
                    f.sequenceId(),
                    f.sourceFileName(),
                    f.stagedPath(),
                    DestinationVersioning.versionedDestination(destination, f.sourceFileName(), (int) n)));
        }
        return new Plan(planned, n);
    }

    /** A file collected (assembled to staging) from the stream, awaiting final placement. */
    public record CollectedFile(long sequenceId, String sourceFileName, String stagedPath) {}
}
