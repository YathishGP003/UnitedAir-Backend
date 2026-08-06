package com.unitedair.ai.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the two retrieval lanes.
 *
 * <p>Both lanes read the same {@code kb_chunk} rows and apply the same filter predicates,
 * so they are directly comparable and fusion is meaningful. Only the scoring expression and
 * the ordering differ.
 *
 * <p>Filters are composed into the {@code WHERE} clause rather than applied to the result
 * set. SRS 4.3.2 says metadata filters are applied before similarity scoring, and the
 * difference is not academic: post-filtering a top-12 vector search can return nothing at
 * all for a Passenger if all twelve nearest chunks happen to be staff-only.
 */
@Repository
public class KbRepository {

    private static final String SELECT_COLUMNS = """
                c.id, c.chunk_uuid, c.document_code, c.document_title, c.section, c.page,
                c.file_type, c.category, c.audience, c.content
            """;

    private final JdbcClient jdbc;

    public KbRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Semantic lane, ordered and scored only by cosine similarity. */
    public List<RetrievalDtos.Chunk> vectorSearch(float[] queryVector,
                                                  String query,
                                                  RetrievalDtos.Filter filter,
                                                  int topK) {
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(filter, params);
        params.put("vec", toVectorLiteral(queryVector));
        params.put("topK", topK);

        String sql = """
                SELECT %s,
                       1 - VEC_DISTANCE_COSINE(c.embedding, VEC_FromText(:vec)) AS vector_score,
                       NULL AS lexical_score
                FROM kb_chunk c
                JOIN kb_document_version v ON v.id = c.version_id
                WHERE %s
                ORDER BY VEC_DISTANCE_COSINE(c.embedding, VEC_FromText(:vec))
                LIMIT :topK
                """.formatted(SELECT_COLUMNS, where);

        return runQuery(sql, params);
    }

    /**
     * Lexical lane. Airline queries are dense with exact tokens - fare classes, IATA codes,
     * regulation numbers such as CAR-7 - and an embedding blurs precisely those. Full-text
     * search catches what the semantic lane misses.
     */
    public List<RetrievalDtos.Chunk> lexicalSearch(String query,
                                                   float[] queryVector,
                                                   RetrievalDtos.Filter filter,
                                                   int topK) {
        String cleaned = sanitiseForFullText(query);
        if (cleaned.isBlank()) {
            return List.of();
        }

        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(filter, params);
        params.put("q", cleaned);
        params.put("vec", toVectorLiteral(queryVector));
        params.put("topK", topK);

        String sql = """
                SELECT %s,
                       1 - VEC_DISTANCE_COSINE(c.embedding, VEC_FromText(:vec)) AS vector_score,
                       MATCH(c.content) AGAINST (:q IN NATURAL LANGUAGE MODE) AS lexical_score
                FROM kb_chunk c
                JOIN kb_document_version v ON v.id = c.version_id
                WHERE %s
                  AND MATCH(c.content) AGAINST (:q IN NATURAL LANGUAGE MODE) > 0
                ORDER BY lexical_score DESC
                LIMIT :topK
                """.formatted(SELECT_COLUMNS, where);

        return runQuery(sql, params);
    }

    /**
     * Lexical-only form used to overlap full-text retrieval with query embedding.
     * It deliberately does not calculate cosine similarity because the vector is not
     * available yet; candidates also found by the vector lane receive that score during
     * fusion, while lexical relevance remains sufficient for lexical-only candidates.
     */
    public List<RetrievalDtos.Chunk> lexicalSearch(String query,
                                                   RetrievalDtos.Filter filter,
                                                   int topK) {
        String cleaned = sanitiseForFullText(query);
        if (cleaned.isBlank()) {
            return List.of();
        }

        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(filter, params);
        params.put("q", cleaned);
        params.put("topK", topK);

        String sql = """
                SELECT %s,
                       NULL AS vector_score,
                       MATCH(c.content) AGAINST (:q IN NATURAL LANGUAGE MODE) AS lexical_score
                FROM kb_chunk c
                JOIN kb_document_version v ON v.id = c.version_id
                WHERE %s
                  AND MATCH(c.content) AGAINST (:q IN NATURAL LANGUAGE MODE) > 0
                ORDER BY lexical_score DESC
                LIMIT :topK
                """.formatted(SELECT_COLUMNS, where);

        return runQuery(sql, params);
    }

    private List<RetrievalDtos.Chunk> runQuery(String sql, Map<String, Object> params) {
        var spec = jdbc.sql(sql);
        for (var entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query((rs, rowNum) -> map(
                rs,
                nullableDouble(rs, "vector_score"),
                nullableDouble(rs, "lexical_score"))).list();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Number number = (Number) rs.getObject(column);
        return number == null ? null : number.doubleValue();
    }

    public long countActiveChunks() {
        return jdbc.sql("""
                    SELECT COUNT(*) FROM kb_chunk c
                    JOIN kb_document_version v ON v.id = c.version_id
                    WHERE v.status = 'ACTIVE'
                """).query(Long.class).single();
    }

    /**
     * Returns the vector space used by the active corpus.
     *
     * <p>A query vector is comparable only when it was produced in this same space.
     * Mixing hosted and deterministic vectors creates plausible-looking but meaningless
     * cosine scores, so a mixed corpus is reported explicitly and the vector lane can
     * decline to compare it.
     */
    public String activeEmbeddingSource() {
        Set<String> sources = jdbc.sql("""
                    SELECT DISTINCT UPPER(COALESCE(embedding_source, 'UNKNOWN'))
                    FROM kb_document_version
                    WHERE status = 'ACTIVE'
                """)
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toSet());
        return sources.size() == 1 ? sources.iterator().next() : "MIXED";
    }

    // ------------------------------------------------------------- filtering ---

    /**
     * Builds the shared predicate. Only ACTIVE document versions are ever retrievable, so a
     * superseded fare rule cannot resurface once a newer version is published.
     */
    private String buildWhere(RetrievalDtos.Filter filter, Map<String, Object> params) {
        StringJoiner where = new StringJoiner("\n  AND ");
        where.add("v.status = 'ACTIVE'");

        // Audience: a chunk is visible when any of its declared audiences is one this role
        // may read. Audiences are stored comma-separated without spaces for FIND_IN_SET.
        List<String> audiences = filter.audiences();
        if (audiences != null && !audiences.isEmpty()) {
            StringJoiner audienceClause = new StringJoiner(" OR ", "(", ")");
            for (int i = 0; i < audiences.size(); i++) {
                String key = "aud" + i;
                audienceClause.add("FIND_IN_SET(:" + key + ", c.audience) > 0");
                params.put(key, audiences.get(i));
            }
            where.add(audienceClause.toString());
        }

        addInClause(where, params, "c.file_type", "ft", filter.fileTypes());
        addInClause(where, params, "c.category", "cat", filter.categories());
        addInClause(where, params, "c.document_code", "doc", filter.documentCodes());

        // Interval-overlap semantics. A document [docFrom, docTo] overlaps the requested
        // [from, to] when docFrom <= to and docTo >= from. Open bounds stay open.
        if (filter.effectiveTo() != null) {
            where.add("(c.effective_from IS NULL OR c.effective_from <= :effectiveTo)");
            params.put("effectiveTo", java.sql.Date.valueOf(filter.effectiveTo()));
        }
        if (filter.effectiveFrom() != null) {
            where.add("(c.effective_to IS NULL OR c.effective_to >= :effectiveFrom)");
            params.put("effectiveFrom", java.sql.Date.valueOf(filter.effectiveFrom()));
        }

        if (filter.tags() != null && !filter.tags().isEmpty()) {
            int i = 0;
            for (var tag : filter.tags().entrySet()) {
                String pathKey = "tagPath" + i;
                String valKey = "tagVal" + i;
                where.add("JSON_VALUE(c.tags, :" + pathKey + ") = :" + valKey);
                params.put(pathKey, "$." + tag.getKey());
                params.put(valKey, tag.getValue());
                i++;
            }
        }

        return where.toString();
    }

    private void addInClause(StringJoiner where,
                             Map<String, Object> params,
                             String column,
                             String prefix,
                             java.util.Set<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        StringJoiner in = new StringJoiner(", ", column + " IN (", ")");
        int i = 0;
        for (String value : values) {
            String key = prefix + i++;
            in.add(":" + key);
            params.put(key, value);
        }
        where.add(in.toString());
    }

    // --------------------------------------------------------------- mapping ---

    private static RetrievalDtos.Chunk map(ResultSet rs, Double vectorScore, Double lexicalScore)
            throws SQLException {
        return new RetrievalDtos.Chunk(
                rs.getLong("id"),
                rs.getString("chunk_uuid"),
                rs.getString("document_code"),
                rs.getString("document_title"),
                rs.getString("section"),
                rs.getInt("page"),
                rs.getString("file_type"),
                rs.getString("category"),
                rs.getString("audience"),
                rs.getString("content"),
                vectorScore,
                lexicalScore);
    }

    /** MariaDB parses vectors from a JSON-style array literal: {@code [0.1,-0.2,...]}. */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * Strips characters that InnoDB full-text treats as operators and drops tokens shorter
     * than the configured minimum. Natural-language mode is forgiving, but punctuation-heavy
     * queries ("+91 98765-43210?") still produce useless matches without this.
     */
    static String sanitiseForFullText(String query) {
        if (query == null) {
            return "";
        }
        String[] tokens = query.replaceAll("[^\\p{L}\\p{N}\\s-]", " ").split("\\s+");
        List<String> kept = new ArrayList<>();
        for (String token : tokens) {
            String t = token.replaceAll("^-+|-+$", "");
            if (t.length() >= 2) {
                kept.add(t);
            }
        }
        return String.join(" ", kept);
    }
}
