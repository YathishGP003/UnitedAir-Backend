package com.unitedair.ai.knowledge;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Governance metadata declared in a KB document's header table.
 *
 * <p>Every UnitedAir KB document opens with a {@code Field | Value} block naming its
 * document code, version, category, audience, effective date and ingestion tags. Those
 * fields are exactly the metadata filters SRS 4.3.2 requires, so ingestion lifts them from
 * the document rather than asking an administrator to retype them - a document and its
 * governance metadata cannot drift apart if only one of them exists.
 *
 * @param category       raw category as written, e.g. "Booking &amp; Reservations"
 * @param srsCategory    normalised to the SRS 4.3.2 vocabulary
 * @param audiences      audience labels this document may be served to
 * @param servesFrs      FR ids from the Requirements Coverage table, for traceability
 */
public record KbFrontMatter(
        String documentCode,
        String title,
        String versionLabel,
        String category,
        String srsCategory,
        List<String> audiences,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String approvedBy,
        String classification,
        Map<String, String> ingestionTags,
        List<String> servesFrs) {

    /** Audience column value as stored on the version row and copied onto every chunk. */
    public String audienceCsv() {
        return audiences.isEmpty() ? "All" : String.join(", ", audiences);
    }
}
