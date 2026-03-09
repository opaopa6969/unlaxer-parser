package org.unlaxer.dsl.ir;

import java.util.Objects;
import java.util.Set;

/**
 * Metadata for external parser adapters.
 */
public record ParserIrAdapterMetadata(
    String adapterId,
    Set<String> supportedIrVersions,
    Set<ParserIrFeature> supportedFeatures
) {
    public ParserIrAdapterMetadata {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        Objects.requireNonNull(supportedIrVersions, "supportedIrVersions");
        if (supportedIrVersions.isEmpty()) {
            throw new IllegalArgumentException("supportedIrVersions must not be empty");
        }
        supportedIrVersions = Set.copyOf(supportedIrVersions);
        supportedFeatures = supportedFeatures == null ? Set.of() : Set.copyOf(supportedFeatures);
    }
}
