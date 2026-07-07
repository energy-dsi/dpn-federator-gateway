// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

package uk.gov.dbt.ndtp.federator.common.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.dbt.ndtp.federator.common.utils.GRPCExceptionUtils;
import uk.gov.dbt.ndtp.federator.exceptions.RetryableException;

/**
 * Unit tests for GRPCExceptionUtils - previously had zero coverage.
 *
 * <p>Note: the class javadoc says UNAUTHENTICATED means "wrong credentials, retrying would be
 * pointless", but {@code RETRYABLE_STATUSES} actually includes both UNAUTHENTICATED and
 * PERMISSION_DENIED as retryable. Tests below assert the code's actual behaviour, not the
 * javadoc's stated intent - worth flagging to whoever owns this class in case one of the two is
 * wrong.
 */
class GRPCExceptionUtilsTest {

    @ParameterizedTest
    @EnumSource(
            value = Status.Code.class,
            names = {
                "UNAVAILABLE",
                "DEADLINE_EXCEEDED",
                "RESOURCE_EXHAUSTED",
                "DATA_LOSS",
                "CANCELLED",
                "ABORTED",
                "PERMISSION_DENIED",
                "UNAUTHENTICATED"
            })
    void isRetryableException_returnsTrueForRetryableStatuses(Status.Code code) {
        StatusRuntimeException exception = new StatusRuntimeException(Status.fromCode(code));

        assertTrue(GRPCExceptionUtils.isRetryableException(exception));
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.Code.class,
            names = {
                "OK",
                "UNKNOWN",
                "INVALID_ARGUMENT",
                "NOT_FOUND",
                "ALREADY_EXISTS",
                "FAILED_PRECONDITION",
                "OUT_OF_RANGE",
                "UNIMPLEMENTED",
                "INTERNAL"
            })
    void isRetryableException_returnsFalseForNonRetryableStatuses(Status.Code code) {
        StatusRuntimeException exception = new StatusRuntimeException(Status.fromCode(code));

        assertFalse(GRPCExceptionUtils.isRetryableException(exception));
    }

    @Test
    void handleGRPCException_retryableStatus_throwsRetryableExceptionWrappingOriginal() {
        StatusRuntimeException original = new StatusRuntimeException(Status.UNAVAILABLE);

        RetryableException thrown = assertThrows(
                RetryableException.class, () -> GRPCExceptionUtils.handleGRPCException(original));

        assertSame(original, thrown.getCause());
    }

    @Test
    void handleGRPCException_nonRetryableStatus_rethrowsSameExceptionInstance() {
        StatusRuntimeException original = new StatusRuntimeException(Status.NOT_FOUND);

        StatusRuntimeException thrown = assertThrows(
                StatusRuntimeException.class, () -> GRPCExceptionUtils.handleGRPCException(original));

        assertSame(original, thrown, "non-retryable exceptions must be rethrown as-is, not wrapped");
    }
}
