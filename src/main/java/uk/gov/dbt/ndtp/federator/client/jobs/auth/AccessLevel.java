// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs.auth;

/**
 * The level of access a verified Job Runner UI caller has, resolved from their Keycloak roles
 * (see {@link JobRunnerAuthProperties#getAdminRole()} / {@link JobRunnerAuthProperties#getReaderRole()}).
 * <p>
 * Ordered so a higher level always satisfies a requirement set at a lower level (natural enum
 * ordinal comparison): {@code ADMIN} can do everything {@code READER} can, {@code READER} can do
 * everything an anonymous/unmatched caller ({@code NONE}) can - which is nothing.
 */
public enum AccessLevel {
    /** No configured role matched; cannot view or modify anything. */
    NONE,
    /** Can view jobs/recurring jobs/servers (HTTP GET/HEAD/OPTIONS) but not trigger, requeue, or delete. */
    READER,
    /** Full access: viewing plus job-mutating actions (HTTP POST/PUT/DELETE/PATCH). */
    ADMIN
}
