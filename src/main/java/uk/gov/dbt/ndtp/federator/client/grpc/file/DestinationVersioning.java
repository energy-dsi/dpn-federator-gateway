// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.grpc.file;

import java.io.File;
import uk.gov.dbt.ndtp.federator.common.checksum.DestinationMetadata;

/**
 * Builds destination names for received files.
 *
 * <p>The product template version in {@code {schema}-{org}-{product}-{version}.{ext}} is always
 * <b>dropped</b>. Two outputs are produced depending on how many files the stream delivered:
 * <ul>
 *   <li>{@link #unversionedDestination} — single file: {@code {schema}-{org}-{product}.{ext}} (no suffix).</li>
 *   <li>{@link #versionedDestination} — multiple files: {@code {schema}-{org}-{product}-v{n}.{ext}}.</li>
 * </ul>
 *
 * <p>Other destination shapes (prefix ending in {@code /}, blank, or non-convention names like
 * {@code eqbdpggas.nt}) are handled by inserting/omitting the version before the extension and using the
 * source file name where there is no convention name. Pure and stateless.
 *
 * <p>Assumption: schema/org/product segments contain no hyphens — the same assumption the existing
 * {@link DestinationMetadata} parser already makes.
 */
public final class DestinationVersioning {

    private DestinationVersioning() {}

    /** Destination for a single-file stream: template {version} dropped, no file version added. */
    public static String unversionedDestination(String destination, String sourceFileName) {
        if (destination == null || destination.isBlank()) {
            return baseName(sourceFileName);
        }
        String d = destination.trim();
        if (d.endsWith("/")) {
            return d + baseName(sourceFileName);
        }
        String dir = directoryOf(d);
        String fileName = fileNameOf(d);
        DestinationMetadata meta = DestinationMetadata.from(fileName);
        if (meta.isPresent()) {
            // rebuild schema-org-product, dropping the template {version}, keep extension
            return dir + meta.schemaType + "-" + meta.orgName + "-" + meta.productName + extensionOf(fileName);
        }
        // non-convention concrete name: leave as-is
        return d;
    }

    /** Destination for a multi-file stream: template {version} dropped, replaced by v{version}. */
    public static String versionedDestination(String destination, String sourceFileName, int version) {
        String token = "v" + version;

        if (destination == null || destination.isBlank()) {
            return insertVersionBeforeExt(baseName(sourceFileName), token);
        }
        String d = destination.trim();
        if (d.endsWith("/")) {
            return d + insertVersionBeforeExt(baseName(sourceFileName), token);
        }
        String dir = directoryOf(d);
        String fileName = fileNameOf(d);
        String ext = extensionOf(fileName);
        DestinationMetadata meta = DestinationMetadata.from(fileName);
        if (meta.isPresent()) {
            String base = meta.schemaType + "-" + meta.orgName + "-" + meta.productName;
            return dir + base + "-" + token + ext;
        }
        return dir + insertVersionBeforeExt(fileName, token);
    }

    /**
     * Stable per-product key used to namespace the Redis version counter. For a convention destination
     * this is {@code {schema}-{org}-{product}}; otherwise the destination's file-name base (no extension).
     */
    public static String productKey(String destination) {
        if (destination == null || destination.isBlank()) {
            return "default";
        }
        String d = destination.trim();
        String fileName = fileNameOf(d.endsWith("/") ? d.substring(0, d.length() - 1) : d);
        DestinationMetadata meta = DestinationMetadata.from(fileName);
        if (meta.isPresent()) {
            return meta.schemaType + "-" + meta.orgName + "-" + meta.productName;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    static String insertVersionBeforeExt(String fileName, String token) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName + "-" + token;
        }
        return fileName.substring(0, dot) + "-" + token + fileName.substring(dot);
    }

    static String directoryOf(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(0, idx + 1) : "";
    }

    static String fileNameOf(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : "";
    }

    static String baseName(String name) {
        return name == null ? "" : new File(name).getName();
    }
}
