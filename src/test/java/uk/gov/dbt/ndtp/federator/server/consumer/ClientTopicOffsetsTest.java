/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.server.consumer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientTopicOffsetsTest {

    @Test
    void constructor_andGetters_returnExpectedValues() {
        ClientTopicOffsets offsets = new ClientTopicOffsets("client-a", "my-topic", 42L);

        assertEquals("client-a", offsets.getClient());
        assertEquals("my-topic", offsets.getTopic());
        assertEquals(42L, offsets.getOffset());
    }

    @Test
    void threeArgConstructor_defaultsForceResetToFalse() {
        assertFalse(new ClientTopicOffsets("c", "t", 1L).isForceReset());
    }

    @Test
    void fourArgConstructor_carriesForceReset() {
        assertTrue(new ClientTopicOffsets("c", "t", 0L, true).isForceReset());
        assertFalse(new ClientTopicOffsets("c", "t", 0L, false).isForceReset());
    }

    @Test
    void zeroOffset_isAccepted() {
        ClientTopicOffsets offsets = new ClientTopicOffsets("c", "t", 0L);
        assertEquals(0L, offsets.getOffset());
    }

    @Test
    void negativeOffset_isAccepted() {
        // Negative offsets may represent "start from beginning" semantics in some Kafka consumers
        ClientTopicOffsets offsets = new ClientTopicOffsets("c", "t", -1L);
        assertEquals(-1L, offsets.getOffset());
    }

    @Test
    void maxLongOffset_isAccepted() {
        ClientTopicOffsets offsets = new ClientTopicOffsets("c", "t", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, offsets.getOffset());
    }

    @Test
    void nullClientAndTopic_areAccepted() {
        // The class itself imposes no null checks; callers are responsible
        ClientTopicOffsets offsets = new ClientTopicOffsets(null, null, 0L);
        assertNull(offsets.getClient());
        assertNull(offsets.getTopic());
    }

    @Test
    void emptyStrings_areAccepted() {
        ClientTopicOffsets offsets = new ClientTopicOffsets("", "", 1L);
        assertEquals("", offsets.getClient());
        assertEquals("", offsets.getTopic());
    }
}
