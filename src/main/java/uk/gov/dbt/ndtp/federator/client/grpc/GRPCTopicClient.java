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

package uk.gov.dbt.ndtp.federator.client.grpc;

import io.grpc.Context;
import io.grpc.Context.CancellableContext;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.SneakyThrows;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.utils.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.client.connection.ConnectionProperties;
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumMismatchAction;          // NEW
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumValidationException;      // NEW
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumValidationReporter;       // NEW
import uk.gov.dbt.ndtp.federator.common.checksum.PayloadChecksumUtil;              // NEW
import uk.gov.dbt.ndtp.federator.common.utils.KafkaUtil;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;
import uk.gov.dbt.ndtp.federator.exceptions.ClientGRPCJobException;
import uk.gov.dbt.ndtp.federator.exceptions.RetryableException;
import uk.gov.dbt.ndtp.grpc.KafkaByteBatch;
import uk.gov.dbt.ndtp.grpc.TopicRequest;
import uk.gov.dbt.ndtp.secure.agent.sources.Event;
import uk.gov.dbt.ndtp.secure.agent.sources.Header;
import uk.gov.dbt.ndtp.secure.agent.sources.kafka.sinks.KafkaSink;
import uk.gov.dbt.ndtp.secure.agent.sources.memory.SimpleEvent;
import uk.gov.dbt.ndtp.federator.common.utils.SecurityLabelUtil;
import uk.gov.dbt.ndtp.federator.common.utils.ObjectMapperUtil;
import java.util.Map;
import java.util.Objects;


/**
 * GRPCTopicClient is a client for the FederatorService GRPC service.
 * It is used to obtain topics and consume messages from the GRPC service.
 * It also sends messages to the KafkaSink.
 * It is also used to test connectivity to the KafkaSink.
 * It is also used to test connectivity to the GRPC service.
 * It is also used to close the GRPC client.
 * It is also used to process a topic.
 * It is also used to consume messages and send them to the KafkaSink.
 */
@SuppressWarnings("all")
public class GRPCTopicClient extends GRPCAbstractClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("GRPClient");
    private static final String CLIENT_IDLE_TIMEOUT = "client.idleTimeout.secs";

    private final ChecksumMismatchAction mismatchAction; // NEW
    private static final String PLAYBOOK_URL =
            "https://github.com/energy-dsi/dpn-integration-playbook";


    public GRPCTopicClient(ConnectionProperties connectionProperties, String topicPrefix) {
        this(
                connectionProperties.clientName(),
                connectionProperties.clientKey(),
                connectionProperties.serverName(),
                connectionProperties.serverHost(),
                connectionProperties.serverPort(),
                connectionProperties.tls(),
                topicPrefix);
    }

    public GRPCTopicClient(
            String client,
            String key,
            String serverName,
            String host,
            int port,
            boolean isTLSEnabled,
            String topicPrefix) {
        super(client, key, serverName, host, port, isTLSEnabled, topicPrefix);
        LOGGER.info(
                "Initializing GRPCTopicClient with client={}, serverName={}, host={}, port={}, isTLSEnabled={}, topicPrefix={}",
                client,
                serverName,
                host,
                port,
                isTLSEnabled,
                topicPrefix);
        this.mismatchAction = resolveChecksumAction(); // NEW
    }

    /**
     * Protected pass-through constructor to support tests that need to inject a mock channel.
     */
    protected GRPCTopicClient(
            String client, String key, String serverName, String topicPrefix, ManagedChannel channel) {
        super(client, key, serverName, topicPrefix, channel);
        LOGGER.info(
                "Initializing GRPCTopicClient (injected channel) with client={}, serverName={}, topicPrefix={}",
                client,
                serverName,
                topicPrefix);
        this.mismatchAction = resolveChecksumAction(); // NEW
    }

    public static void sendMessage(KafkaSink<Bytes, Bytes> sink, KafkaByteBatch batch) {
        LOGGER.debug("Creating message to send");
        Bytes key = new Bytes(batch.getKey().toByteArray());
        Bytes value = new Bytes(batch.getValue().toByteArray());
        List<Header> headers = batch.getSharedList().stream()
                .map(h -> new Header(h.getKey(), h.getValue()))
                .toList();
        Event<Bytes, Bytes> event = new SimpleEvent<>(headers, key, value);
        LOGGER.debug("Sending message");
        sink.send(event);
        LOGGER.debug("Sent event");
    }

    public static KafkaSink<Bytes, Bytes> getSender(String topic, String targetTopic, String serverName) {
        if (targetTopic != null && !targetTopic.isEmpty()) {
            return KafkaUtil.getKafkaSink(targetTopic);
        }
        return KafkaUtil.getKafkaSink(serverName + "-" + topic);
    }

    public static String concatCompoundTopicName(String topic, String topicPrefix, String serverName) {
        if (topicPrefix == null || topicPrefix.isEmpty()) {
            return String.join("-", serverName, topic);
        }
        return String.join("-", topicPrefix, serverName, topic);
    }

    public void processTopic(String topic, long offset) {
        LOGGER.info("Processing topic: '{}' with offset: '{}'", topic, offset);
        RedisUtil.getInstance();
        LOGGER.debug("Redis connectivity check passed");
        TopicRequest topicRequest =
                TopicRequest.newBuilder().setTopic(topic).setOffset(offset).build();

        try (KafkaSink<Bytes, Bytes> sink = getSender(topic, this.topicPrefix, this.serverName)) {
            LOGGER.debug("Kafka sink created successfully");
            try (Context.CancellableContext withCancellation = Context.current().withCancellation()) {
                withCancellation.run(() -> consumeMessagesAndSendOn(topicRequest, sink));
                LOGGER.info("Topic {} processed", topic);
            } catch (StatusRuntimeException exception) {
                if (Status.INVALID_ARGUMENT
                        .getCode()
                        .equals(exception.getStatus().getCode())) {
                    LOGGER.error("Topic ({}) no longer valid for client ({})", topic, client);
                } else {
                    logConnectionFailureGuidance(exception);
                }
                throw exception;
            }
        } catch (KafkaException e) {
            throw new RetryableException(e);
        }

    }
    /**
     * Logs a connection/RPC failure with actionable guidance describing the likely cause
     * and next steps, plus a link to the DPN integration playbook for further troubleshooting.
     *
     * @param exception the gRPC failure that occurred while talking to the federator server
     */

    public String logConnectionFailureGuidance(StatusRuntimeException exception) {

        String guidance = switch (exception.getStatus().getCode()) {

            case UNAVAILABLE -> "Server is unreachable. Check that the host and port are correct "

                    + "and the federator server is running. Verify network connectivity and firewall rules.";

            case UNAUTHENTICATED -> "Authentication failed. Check that the IDP token service is "

                    + "reachable, the client ID and secret are correct, and the token has not expired.";

            case PERMISSION_DENIED -> "Authorisation denied. Check that this consumer is registered "

                    + "in the management node and has been granted access to the target producer.";

            case DEADLINE_EXCEEDED -> "Connection timed out. The server may be overloaded or the "

                    + "network is slow. Check server health and consider increasing the connection timeout.";

            default -> "An unexpected error occurred while connecting to the federator server.";

        };

        LOGGER.error(

                "Topic processing stopped due to connection error. status={}, cause={}. {} "
                        + "See the DPN integration playbook for troubleshooting steps: {}",
                exception.getStatus().getCode(), exception.getMessage(), guidance, PLAYBOOK_URL);

        return guidance;
    }

        public void consumeMessagesAndSendOn(TopicRequest req, KafkaSink<Bytes, Bytes> sink) {
        LOGGER.info("Consuming messages for topic: {}", req.getTopic());

        long idleSeconds = PropertyUtil.getPropertyIntValue(CLIENT_IDLE_TIMEOUT, TEN);

        ExecutorService threadExecutor = null;
        CancellableContext context = null;
        try {
            threadExecutor = Executors.newSingleThreadExecutor();
            context = Context.current().withCancellation();
            Iterator<KafkaByteBatch> iterator = context.call(() -> getStub().getKafkaConsumer(req));

            while (true) {
                // Note that with the source being a blocking iterator, the call to next() will block until a message is
                // available.
                // To avoid blocking indefinitely (as idle timeouts will not be reached), we use a Future with a timeout
                // to limit how long we wait for a message.
                Future<KafkaByteBatch> futureNext = threadExecutor.submit(context.wrap(iterator::next));
                KafkaByteBatch batch = getNextBatch(futureNext, idleSeconds, context);
                if (batch == null) {
                    break;
                }

                LOGGER.debug("Consuming message: {}, {} : {}", batch.getTopic(), batch.getOffset(), batch.getValue());

                // ── CHECKSUM VERIFICATION (NEW) ───────────────────────────────────────
                int     inBytes  = batch.getValue().size();
                String  expected = batch.getPayloadChecksum(); // ← TEMP FAILURE TEST
                String  actual   = PayloadChecksumUtil.compute(batch.getValue());
                boolean pass     = PayloadChecksumUtil.verify(
                        batch.getValue(), expected, batch.getTopic(), batch.getOffset());

                // ── ORG / SCHEMA / PRODUCT NAME — priority order:
                //   1. metadata header JSON  {"orgName":"neso","schemaType":"eq","productType":"eqsample1",...}
                //      ← highest priority; always present per spec
                //   2. Security-Label header ORGANISATION_TYPE=ENV  ← fallback for orgName only
                //   3. serverName from management node DB           ← final fallback for orgName

                // Priority 1: scan ALL headers — try to parse each value as JSON.
                //   The header KEY is not required to be a specific name.
                //   We accept the first header whose value is a JSON object
                //   containing any of: "orgName", "schemaType", "productType".
                //   e.g. value = {"orgName":"neso","schemaType":"eq","productType":"eqsample1",...}
                String orgFromMetadata     = null;
                String schemaFromMetadata  = null;
                String productFromMetadata = null;

                for (var h : batch.getSharedList()) {
                    String key = h.getKey();
                    String val = h.getValue();
                    if (val == null || val.isBlank()) continue;

                    // Approach 1 — individual header per field (Kafka UI v0.7.2 sends JSON
                    //              as individual headers: key='orgName' value='neso' etc.)
                    if ("orgName".equalsIgnoreCase(key))     { orgFromMetadata     = val.trim(); continue; }
                    if ("schemaType".equalsIgnoreCase(key))  { schemaFromMetadata  = val.trim(); continue; }
                    if ("productType".equalsIgnoreCase(key)) { productFromMetadata = val.trim(); continue; }

                    // Approach 2 — single header whose value is a JSON object
                    //              {"orgName":"neso","schemaType":"eq","productType":"eqsample1",...}
                    if (!val.trim().startsWith("{")) continue;
                    try {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> json =
                                ObjectMapperUtil.getInstance()
                                        .readValue(val, java.util.Map.class);
                        // Accept this header if it contains at least one of our fields
                        if (json.containsKey("orgName") || json.containsKey("schemaType")
                                || json.containsKey("productType")) {
                            if (json.get("orgName")     != null) orgFromMetadata     = json.get("orgName").toString().trim();
                            if (json.get("schemaType")  != null) schemaFromMetadata  = json.get("schemaType").toString().trim();
                            if (json.get("productType") != null) productFromMetadata = json.get("productType").toString().trim();
                            LOGGER.debug("Metadata found in header key='{}': org={} schema={} product={}",
                                    h.getKey(), orgFromMetadata, schemaFromMetadata, productFromMetadata);
                            break; // stop at first matching header
                        }
                    } catch (Exception e) {
                        // not JSON or wrong structure — skip silently
                        LOGGER.trace("Header key='{}' value is not metadata JSON", h.getKey());
                    }
                }

                // Priority 2: extract ORGANISATION_TYPE from Security-Label (orgName only)
                String orgFromLabel = (orgFromMetadata != null) ? null :
                        batch.getSharedList().stream()
                        .filter(h -> "Security-Label".equalsIgnoreCase(h.getKey()))
                        .map(h -> {
                            try {
                                Map<String, String> parsed =
                                        SecurityLabelUtil.parse(h.getValue()).asMap();
                                String org = parsed.get("ORGANISATION_TYPE");
                                if (org == null) org = parsed.get("ORGANISATION");
                                return org;
                            } catch (Exception e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

                // Priority 3: serverName from management node DB (always available)
                String orgName     = (orgFromMetadata != null) ? orgFromMetadata
                                   : (orgFromLabel    != null) ? orgFromLabel
                                   : this.serverName;
                String schemaName  = schemaFromMetadata;   // null if header absent
                String productName = productFromMetadata;  // null if header absent

                if (!pass) {
                    ChecksumValidationException ex = new ChecksumValidationException(
                            batch.getTopic(), batch.getOffset(), expected, actual);

                    switch (mismatchAction) {
                        case SKIP -> {
                            LOGGER.warn(ChecksumValidationReporter.stream(
                                    batch.getTopic(), batch.getOffset(),
                                    inBytes, -1, expected, actual, ex,
                                    orgName, schemaName, productName));
                            RedisUtil.getInstance().setOffset(
                                    getRedisPrefix(), req.getTopic(), batch.getOffset() + 1);
                            continue;
                        }
                        case ABORT -> {
                            LOGGER.error(ChecksumValidationReporter.stream(
                                    batch.getTopic(), batch.getOffset(),
                                    inBytes, -1, expected, actual, ex,
                                    orgName, schemaName, productName));
                            throw new ClientGRPCJobException(
                                    "Checksum ABORT on topic=" + batch.getTopic()
                                    + " offset=" + batch.getOffset(), ex);
                        }
                        case LOG_ONLY -> {
                            LOGGER.error(ChecksumValidationReporter.stream(
                                    batch.getTopic(), batch.getOffset(),
                                    inBytes, inBytes, expected, actual, ex,
                                    orgName, schemaName, productName));
                        }
                    }
                } else {
                    LOGGER.info(ChecksumValidationReporter.stream(
                            batch.getTopic(), batch.getOffset(),
                            inBytes, inBytes, expected, actual, null,
                            orgName, schemaName, productName));
                }
                // ─────────────────────────────────────────────────────────────────────

                sendMessage(sink, batch);

                // The persisted offset here is read when a new job starts.
                // Store the next offset to be read to avoid record overlaps.
                long nextOffset = batch.getOffset() + 1;
                RedisUtil.getInstance().setOffset(getRedisPrefix(), req.getTopic(), nextOffset);
                LOGGER.debug("Wrote next offset {} to redis for topic {}", nextOffset, req.getTopic());
            }
        } catch (Exception e) {
            throw new ClientGRPCJobException("Error encountered whilst consuming topic", e);
        } finally {
            if (context != null) {
                context.cancel(null);
            }
            if (threadExecutor != null) {
                threadExecutor.shutdownNow();
                try {
                    if (!threadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        LOGGER.warn("Thread executor did not terminate in time");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            LOGGER.info("Finished consuming topic");
        }
    }


    /***
     * Gets the next batch from the future, with a timeout to avoid blocking indefinitely.
     * @param futureNext The future to get the next batch from.
     * @param idleSeconds The number of seconds to wait before timing out.
     * @param context The cancellable context to cancel if the timeout is reached.
     * @return The next batch, or null if the timeout is reached.
     * @throws Exception If an error occurs while getting the next batch.
     */
    @SneakyThrows
    private KafkaByteBatch getNextBatch(
            Future<KafkaByteBatch> futureNext, long idleSeconds, CancellableContext context) {

        // To avoid blocking indefinitely (as idle timeouts will not be reached),
        // use a Future with a timeout to limit how long we wait for a message.
        try {
            return futureNext.get(idleSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            context.cancel(null);
            LOGGER.info("No messages received for {}s. Closing consumer.", idleSeconds);
            return null;
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof StatusRuntimeException sre) {
                Status.Code code = sre.getStatus().getCode();
                // Server closed or call cancelled/deadline: treat as end of stream
                if (code == Status.Code.OUT_OF_RANGE
                        || code == Status.Code.CANCELLED
                        || code == Status.Code.DEADLINE_EXCEEDED) {
                    LOGGER.info("Stream ended: {}", code);
                    return null;
                }
            }
            throw ee;
        }
    }

    /**

     * Tests connectivity to the federator server without consuming any data.

     * On failure, logs actionable guidance describing the likely cause and a link to the

     * DPN integration playbook, then rethrows so the caller can surface a clear pass/fail result.

     */

    public void testConnectivity() {

        try {

            getStub().withDeadlineAfter(5, TimeUnit.SECONDS)

                    .getKafkaConsumer(TopicRequest.getDefaultInstance());

        } catch (StatusRuntimeException exception) {

            logConnectionFailureGuidance(exception);

            throw exception;

        }

    }


    // NEW — resolves checksum.on.mismatch from client.properties
    // Defaults to SKIP if the property is missing or unrecognised.
    private ChecksumMismatchAction resolveChecksumAction() {
        String raw = PropertyUtil.getPropertyValue("checksum.on.mismatch", "SKIP")
                .toUpperCase().trim();
        try {
            return ChecksumMismatchAction.valueOf(raw);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown checksum.on.mismatch value '{}', defaulting to SKIP", raw);
            return ChecksumMismatchAction.SKIP;
        }
    }
}


