package com.unitedair.ai.knowledge;

import java.util.List;
import java.util.Locale;

/**
 * Narrows mixed-audience document metadata when a chunk is explicitly an internal section.
 *
 * <p>Some policy documents intentionally serve both passengers and staff, but contain
 * dedicated revenue-management and override sections. Copying the document-level audience
 * to every chunk would make those internal sections passenger-retrievable. This resolver
 * preserves the declared audience for public policy sections and narrows only headings
 * that clearly identify internal operating material.
 */
final class ChunkAudienceResolver {

    private static final List<String> INTERNAL_HEADING_TERMS = List.of(
            "(airline staff)",
            "revenue band",
            "yield management",
            "availability override",
            "seat blocking",
            "upgrade inventory management",
            "governance & workflow",
            "non-compliance",
            "objectives",
            "introduction",
            "purpose & scope");

    private ChunkAudienceResolver() {
    }

    static String resolve(List<String> declaredAudiences, String section, String content) {
        String declared = String.join(",", declaredAudiences);
        if (!declaredAudiences.contains("Passenger")
                || !declaredAudiences.contains("Airline Staff")) {
            return declared;
        }

        String heading = section == null ? "" : section.toLowerCase(Locale.ROOT);
        boolean internal = INTERNAL_HEADING_TERMS.stream().anyMatch(heading::contains);
        if (!internal && content != null) {
            String opening = content.substring(0, Math.min(content.length(), 180))
                    .toLowerCase(Locale.ROOT);
            internal = opening.contains("(airline staff)");
        }
        return internal ? "Airline Staff" : declared;
    }
}
