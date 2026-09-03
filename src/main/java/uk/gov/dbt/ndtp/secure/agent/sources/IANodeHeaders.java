// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.IANodeHeaders.
// Pure constants - verified byte-for-byte identical to the original via decompilation,
// so wire compatibility with any peer still on secure-agents is unaffected.

package uk.gov.dbt.ndtp.secure.agent.sources;

public final class IANodeHeaders {

    public static final String SECURITY_LABEL = "Security-Label";
    public static final String EXEC_PATH = "Exec-Path";
    public static final String DEAD_LETTER_REASON = "Dead-Letter-Reason";
    public static final String INPUT_REQUEST_ID = "Input-Request-ID";
    public static final String REQUEST_ID = "Request-ID";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String DATA_SOURCE_NAME = "Data-Source-Name";
    public static final String DATA_SOURCE_TYPE = "Data-Source-Type";

    private IANodeHeaders() {}
}
