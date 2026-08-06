package com.unitedair.ai.knowledge;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.unitedair.ai.identity.Role;

/** Types describing what to retrieve and what came back. */
public final class RetrievalDtos {

    private RetrievalDtos() { }

    /**
     * The metadata filters of SRS 4.3.2, applied <em>before</em> similarity scoring.
     *
     * <p>{@code audiences} is not optional and is never supplied by the caller's request
     * body - it is derived from the authenticated role. That is what makes it impossible
     * for a Passenger's question to retrieve a staff-only chunk, no matter how the question
     * is phrased.
     */
    public record Filter(
            List<String> audiences,
            Set<String> fileTypes,
            Set<String> categories,
            Set<String> documentCodes,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Map<String, String> tags) {

        public static Filter forRole(Role role) {
            return new Filter(role.readableAudiences(), Set.of(), Set.of(), Set.of(),
                    LocalDate.now(), LocalDate.now(), Map.of());
        }

        /** Compatibility constructor for the former single effective-date filter. */
        public Filter(
                List<String> audiences,
                Set<String> fileTypes,
                Set<String> categories,
                Set<String> documentCodes,
                LocalDate effectiveOn,
                Map<String, String> tags) {
            this(audiences, fileTypes, categories, documentCodes,
                    effectiveOn, effectiveOn, tags);
        }

        public Filter withFileTypes(Set<String> types) {
            return new Filter(audiences, types, categories, documentCodes,
                    effectiveFrom, effectiveTo, tags);
        }

        public Filter withCategories(Set<String> cats) {
            return new Filter(audiences, fileTypes, cats, documentCodes,
                    effectiveFrom, effectiveTo, tags);
        }

        public Filter withDocumentCodes(Set<String> codes) {
            return new Filter(audiences, fileTypes, categories, codes,
                    effectiveFrom, effectiveTo, tags);
        }

        public Filter withEffectiveRange(LocalDate from, LocalDate to) {
            return new Filter(audiences, fileTypes, categories, documentCodes,
                    from, to, tags);
        }

        public Filter withTags(Map<String, String> values) {
            return new Filter(audiences, fileTypes, categories, documentCodes,
                    effectiveFrom, effectiveTo, values);
        }

        /** Loggable form for the audit trail, so a run can be reproduced later. */
        public Map<String, Object> describe() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("audiences", audiences);
            if (!fileTypes.isEmpty()) {
                map.put("fileTypes", fileTypes);
            }
            if (!categories.isEmpty()) {
                map.put("categories", categories);
            }
            if (!documentCodes.isEmpty()) {
                map.put("documentCodes", documentCodes);
            }
            if (effectiveFrom != null) {
                map.put("effectiveFrom", effectiveFrom.toString());
            }
            if (effectiveTo != null) {
                map.put("effectiveTo", effectiveTo.toString());
            }
            if (!tags.isEmpty()) {
                map.put("tags", tags);
            }
            return map;
        }
    }

    /** One chunk returned by a retrieval lane. */
    public record Chunk(
            Long id,
            String chunkUuid,
            String documentCode,
            String documentTitle,
            String section,
            int page,
            String fileType,
            String category,
            String audience,
            String content,
            Double vectorScore,
            Double lexicalScore) {

        public Chunk withScores(Double vector, Double lexical) {
            return new Chunk(id, chunkUuid, documentCode, documentTitle, section, page,
                    fileType, category, audience, content, vector, lexical);
        }
    }

    /** A chunk after fusion and reranking, carrying its final position and score. */
    public record Ranked(
            Chunk chunk,
            double fusedScore,
            double rerankScore,
            int rank) { }

    /** FAST is the default lane; DEEP is the repair lane the evaluator escalates to. */
    public enum Lane {
        FAST,
        DEEP
    }

    /**
     * The complete result of a retrieval attempt.
     *
     * @param confidence 0-1, computed from the evidence itself rather than self-reported
     */
    public record Result(
            List<Ranked> evidence,
            Lane lane,
            int attempt,
            int vectorHits,
            int lexicalHits,
            int fusedHits,
            double topSimilarity,
            double confidence,
            long durationMs,
            Filter filter) {

        public boolean isEmpty() {
            return evidence.isEmpty();
        }
    }
}
