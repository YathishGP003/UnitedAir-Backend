package com.unitedair.ai.privacy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The outcome of redacting one piece of text.
 *
 * <p>{@link #redacted()} is the only form that may be persisted, logged, placed in
 * ChatMemory, or sent to the model.
 *
 * <p>{@link #originals()} is the request-scoped vault. It exists because naive redaction
 * breaks the tools: once a PNR has become {@code [AIR-PNR-REDACTED]}, BookingManagementTool
 * has nothing to look up. Tools resolve the real value through this map, which lives only
 * as long as the request and is never written anywhere.
 *
 * <p>Several values of the same type can appear in one message, so originals are held as an
 * ordered list per type. {@link #first(PiiType)} returns the first occurrence, which is what
 * a tool wants when the user has mentioned exactly one booking.
 */
public record RedactionResult(
        String redacted,
        Map<PiiType, List<String>> originals,
        int redactionCount) {

    public static RedactionResult unchanged(String text) {
        return new RedactionResult(text, Map.of(), 0);
    }

    public boolean hasRedactions() {
        return redactionCount > 0;
    }

    public Optional<String> first(PiiType type) {
        List<String> values = originals.get(type);
        return (values == null || values.isEmpty()) ? Optional.empty() : Optional.of(values.get(0));
    }

    public List<String> all(PiiType type) {
        return originals.getOrDefault(type, List.of());
    }

    /**
     * Type to occurrence count. Safe to persist in the audit trail: it records that a PNR
     * was seen without recording which PNR.
     */
    public Map<String, Integer> detectionSummary() {
        Map<String, Integer> summary = new LinkedHashMap<>();
        originals.forEach((type, values) -> summary.put(type.name(), values.size()));
        return summary;
    }
}
