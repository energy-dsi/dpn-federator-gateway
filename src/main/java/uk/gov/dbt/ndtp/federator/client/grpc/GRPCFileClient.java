// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.
package uk.gov.dbt.ndtp.federator.client.grpc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.client.connection.ConnectionProperties;
import uk.gov.dbt.ndtp.federator.client.grpc.file.FileChunkAssembler;
import uk.gov.dbt.ndtp.federator.client.grpc.file.FileVersionFinalizer;
import uk.gov.dbt.ndtp.federator.client.grpc.file.FileVersionPlanner;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;
import uk.gov.dbt.ndtp.federator.exceptions.FileAssemblyException;
import uk.gov.dbt.ndtp.grpc.FileChunk;
import uk.gov.dbt.ndtp.grpc.FileStreamEvent;
import uk.gov.dbt.ndtp.grpc.FileStreamRequest;
import uk.gov.dbt.ndtp.grpc.StreamWarning;
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumValidationReporter;

/**
 * GRPC client responsible for streaming files from the Federator service and assembling them locally.
 *
 * <p>Uses {@link uk.gov.dbt.ndtp.federator.client.grpc.file.FileChunkAssembler} to assemble files from
 * streamed {@link uk.gov.dbt.ndtp.grpc.FileChunk} messages. When a file is completed, the configured
 * storage provider is invoked (LOCAL or S3).
 */
@Slf4j
public class GRPCFileClient extends GRPCAbstractClient {

    /**
     * Creates a client using the provided {@link ConnectionProperties} and topic prefix.
     *
     * @param connectionProperties gRPC and TLS connection parameters
     * @param topicPrefix prefix used when composing topic names
     */
    public GRPCFileClient(ConnectionProperties connectionProperties, String topicPrefix) {
        this(
                connectionProperties.clientName(),
                connectionProperties.clientKey(),
                connectionProperties.serverName(),
                connectionProperties.serverHost(),
                connectionProperties.serverPort(),
                connectionProperties.tls(),
                topicPrefix);
    }

    /**
     * Creates a client with explicit connection parameters.
     *
     * @param client logical client name used for Redis keys and logs
     * @param key client authentication key
     * @param serverName server logical name used for namespacing
     * @param host target host for the Federator gRPC service
     * @param port target port for the Federator gRPC service
     * @param isTLSEnabled whether to use TLS when connecting
     * @param topicPrefix prefix used when composing topic names
     */
    public GRPCFileClient(
            String client,
            String key,
            String serverName,
            String host,
            int port,
            boolean isTLSEnabled,
            String topicPrefix) {
        super(client, key, serverName, host, port, isTLSEnabled, topicPrefix);
        log.info(
                "Initializing GRPCFileClient with client={}, serverName={}, host={}, port={}, isTLSEnabled={}, topicPrefix={}",
                client,
                serverName,
                host,
                port,
                isTLSEnabled,
                topicPrefix);
    }

    /**
     * Streams files for the given topic starting at the provided sequence id and stores them via
     * {@link uk.gov.dbt.ndtp.federator.client.grpc.file.FileChunkAssembler} and the configured storage provider.
     * When each file completes, the next sequence id is persisted to Redis.
     *
     * @param topic topic name to stream
     * @param offset sequence id to begin from
     * @param destination destination path for file storage
     * @throws FileAssemblyException if streaming or assembly encounters an unrecoverable error
     */
    public void processTopic(String topic, long offset, String destination) {
        log.info("Processing file topic: '{}' with start_sequence_id: '{}'", topic, offset);
        validatePrerequisites(topic, destination);

        FileStreamRequest request = buildFileStreamRequest(topic, offset);
        FileChunkAssembler assembler = new FileChunkAssembler(destination, this.serverName);

        try {
            processFileStream(topic, offset, destination, request, assembler);
        } catch (Exception e) {
            throw new FileAssemblyException("Unexpected error while processing file stream for topic " + topic, e);
        }
    }

    /**
     * Validates that Redis is accessible and destination is properly configured.
     *
     * @param topic topic name for error messages
     * @param destination destination path to validate
     * @throws FileAssemblyException if validation fails
     */
    private void validatePrerequisites(String topic, String destination) {
        RedisUtil.getInstance();
        log.debug("Redis connectivity check passed");

        if (destination == null || destination.isBlank()) {
            throw new FileAssemblyException("Destination is required but was null/blank for topic '" + topic + "'");
        }
    }

    /**
     * Builds a file stream request for the specified topic and offset.
     *
     * @param topic topic name to stream
     * @param offset sequence id to begin from
     * @return configured FileStreamRequest
     */
    private FileStreamRequest buildFileStreamRequest(String topic, long offset) {
        return FileStreamRequest.newBuilder()
                .setTopic(topic)
                .setStartSequenceId(offset)
                .build();
    }

    /**
     * Processes the file stream by iterating through events and delegating to specific handlers.
     *
     * @param topic topic name for logging
     * @param offset initial offset for tracking
     * @param request the stream request
     * @param assembler file chunk assembler for file reconstruction
     */
    private void processFileStream(
            String topic, long offset, String destination, FileStreamRequest request, FileChunkAssembler assembler) {
        Iterator<FileStreamEvent> events = getStub().getFilesStream(request);
        List<FileVersionPlanner.CollectedFile> collected = new ArrayList<>();

        while (events.hasNext()) {
            FileStreamEvent event = events.next();
            handleStreamEvent(topic, event, assembler, collected);
        }

        // End of stream: now that the full file list is known, apply versioning.
        //  - single file  -> stored without a version suffix
        //  - multiple files -> v1, v2, v3 ... continuing from the persisted Redis counter
        // Files are stored here (deferred from per-file), the counter is persisted, and the offset is
        // advanced to the last stored file only after all files in the stream are stored successfully.
        long lastStoredSeq = FileVersionFinalizer.finalizeStream(collected, destination);
        if (lastStoredSeq >= 0) {
            saveNextOffsetToRedis(topic, lastStoredSeq);
            log.info(
                    "Finalized {} file(s) for topic '{}'. Saved next sequence id {} to Redis.",
                    collected.size(),
                    topic,
                    lastStoredSeq + 1);
        } else {
            log.info(
                    "No files stored for topic '{}' (empty stream or upload failure); offset not advanced.", topic);
        }
        log.info("Finished processing stream for topic '{}'", topic);
    }

    /**
     * Handles a single stream event based on its type.
     *
     * @param topic topic name for logging
     * @param event the stream event to handle
     * @param assembler file chunk assembler for file reconstruction
     * @param collected accumulator of completed files for this stream
     */
    private void handleStreamEvent(
            String topic,
            FileStreamEvent event,
            FileChunkAssembler assembler,
            List<FileVersionPlanner.CollectedFile> collected) {
        switch (event.getEventCase()) {
            case CHUNK -> handleChunkEvent(topic, event.getChunk(), assembler, collected);
            case WARNING -> handleWarningEvent(topic, event.getWarning());
            case EVENT_NOT_SET -> log.warn("Received FileStreamEvent with no payload for topic '{}'", topic);
        }
    }

    /**
     * Assembles a file chunk to staging and, on completion, collects its descriptor for end-of-stream
     * versioning. Storage and offset advancement are deferred to finalization.
     *
     * @param topic     topic name for logging
     * @param chunk     the file chunk to process
     * @param assembler file chunk assembler for file reconstruction
     * @param collected accumulator of completed files for this stream
     */
    private void handleChunkEvent(
            String topic,
            FileChunk chunk,
            FileChunkAssembler assembler,
            List<FileVersionPlanner.CollectedFile> collected) {
        // Assemble to staging; storage + offset advancement are deferred to end-of-stream finalization,
        // so the single-vs-multiple versioning decision can be made over the whole collected list.
        FileVersionPlanner.CollectedFile staged = assembler.acceptToStaging(chunk);
        if (staged != null) {
            collected.add(staged);
            log.info(
                    "Collected file '{}' (seq {}) for topic '{}'; awaiting end-of-stream versioning",
                    chunk.getFileName(),
                    chunk.getFileSequenceId(),
                    topic);
        }
    }

    /**
     * Handles a stream warning event by recording the skipped sequence id and logging it.
     *
     * @param topic topic name for logging
     * @param warning the warning event
     */
    private void handleWarningEvent(String topic, StreamWarning warning) {
        saveNextOffsetToRedis(topic, warning.getSkippedSequenceId());
        log.warn(ChecksumValidationReporter.fileSkipped(
                warning.getSkippedSequenceId(),
                warning.getReason(),
                warning.getDetails()));
        // Behaviour preserved from the per-file implementation: a warning records the skipped sequence id
        // so upstream retry logic does not loop forever.
    }

    /**
     * Saves the next offset to Redis after processing the given sequence ID.
     *
     * @param topic topic name
     * @param lastProcessedSequenceId the sequence ID that was just processed
     */
    private void saveNextOffsetToRedis(String topic, long lastProcessedSequenceId) {
        long nextOffset = lastProcessedSequenceId + 1;
        saveOffsetToRedis(topic, nextOffset);
    }

    /**
     * Saves the next offset to Redis for the given topic.
     *
     * @param topic topic name
     * @param nextOffset offset to save
     */
    private void saveOffsetToRedis(String topic, long nextOffset) {
        RedisUtil.getInstance().setOffset(getRedisPrefix(), topic, nextOffset);
    }
}
