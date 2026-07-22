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

package uk.gov.dbt.ndtp.federator.server.processor;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Set;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.checksum.PayloadChecksumUtil;
import uk.gov.dbt.ndtp.federator.server.grpc.LimitedServerCallStreamObserver;
import uk.gov.dbt.ndtp.federator.server.interfaces.StreamObservable;
import uk.gov.dbt.ndtp.federator.server.processor.kafka.RdfKafkaEventMessageProcessor;
import uk.gov.dbt.ndtp.grpc.KafkaByteBatch;
import uk.gov.dbt.ndtp.federator.eventsource.payloads.RdfPayload;
import uk.gov.dbt.ndtp.federator.eventsource.kafka.KafkaEvent;

class RdfKafkaEventMessageProcessorTest {

    final StreamObservable mockObserver = mock(LimitedServerCallStreamObserver.class);
    final Set<String> sharedHeaders = Set.of();
    final RdfKafkaEventMessageProcessor cut = new RdfKafkaEventMessageProcessor(mockObserver, sharedHeaders);

    /**
     * A minimal, valid, serialisable RdfPayload fixture - an empty RDF dataset.
     * production code's serializer does not (and per Kafka Serializer convention,
     * arguably should not have to) special-case a null RdfPayload value, so fixtures
     * exercising the "happy path" / "missing key" / "checksum" scenarios need a real
     * payload here rather than null.
     */
    private static RdfPayload emptyPayload() {
        return new RdfPayload(DatasetGraphFactory.create());
    }

    void test_process_happyPath() {
        // given
        KafkaEvent<String, RdfPayload> message =
                new KafkaEvent<>(new ConsumerRecord<>("topic", 1, 1, "key", emptyPayload()));
        // when
        cut.process(message);
        // then
        verify(mockObserver).onNext(any(KafkaByteBatch.class));
    }

    @Test
    void test_process_nullEvent() {
        // given
        // when
        cut.process(null);
        // then
        verify(mockObserver).onError(any(NullPointerException.class));
    }

    @Test
    void test_process_missingKey() {
        // given
        KafkaEvent<String, RdfPayload> message =
                new KafkaEvent<>(new ConsumerRecord<>("topic", 1, 1, null, emptyPayload()));

        // when
        cut.process(message);
        // then
        verify(mockObserver).onNext(any(KafkaByteBatch.class));
    }

    @Test
    void test_process_setsPayloadChecksum() {
        // given
        KafkaEvent<String, RdfPayload> message = new KafkaEvent<>(
                new ConsumerRecord<>("topic", 1, 1, "key", emptyPayload()));
        // when
        cut.process(message);
        // then — verify batch sent to observer has checksum field set
        verify(mockObserver).onNext(argThat(batch -> {
            String checksum = ((KafkaByteBatch) batch).getPayloadChecksum();
            return checksum != null && checksum.length() == 64; // SHA-256 = 64 hex chars
        }));
    }

    @Test
    void test_process_checksumMatchesPayload() {
        // given
        KafkaEvent<String, RdfPayload> message = new KafkaEvent<>(
                new ConsumerRecord<>("topic", 1, 1, "key", emptyPayload()));
        // capture the batch sent to observer
        var batchCaptor = org.mockito.ArgumentCaptor
                .forClass(KafkaByteBatch.class);
        // when
        cut.process(message);
        // then
        verify(mockObserver).onNext(batchCaptor.capture());
        KafkaByteBatch batch = batchCaptor.getValue();
        String expected = PayloadChecksumUtil.compute(batch.getValue());
        assertEquals(expected, batch.getPayloadChecksum());
    }
}
