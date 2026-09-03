// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs.auth;

/**
 * Outcome of verifying a bearer token presented to the {@link JobRunnerAuthGateway}.
 *
 * @param authorized  whether the token itself was valid (signature, expiry, issuer, audience) -
 *                     independent of what the caller is allowed to do with it
 * @param subject     the token subject, when available (for logging/audit)
 * @param reason      a short, non-sensitive reason for failure, when {@code authorized} is false
 * @param accessLevel the caller's resolved {@link AccessLevel}; {@link AccessLevel#NONE} when
 *                     {@code authorized} is false, or when the token is valid but matched neither
 *                     the configured admin nor reader role
 */
public record AuthResult(boolean authorized, String subject, String reason, AccessLevel accessLevel) {

    public static AuthResult success(String subject, AccessLevel accessLevel) {
        return new AuthResult(true, subject, null, accessLevel);
    }

    public static AuthResult failure(String reason) {
        return new AuthResult(false, null, reason, AccessLevel.NONE);
    }
}
