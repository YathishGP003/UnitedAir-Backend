package com.unitedair.ai.knowledge;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only projection used by {@link KbQualityService}. It intentionally has no
 * insert/update/delete methods, so running diagnostics cannot alter the governed corpus.
 */
@Repository
public class KbQualityRepository {

    private final JdbcClient jdbc;

    public KbQualityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int activeDocumentCount() {
        return jdbc.sql("""
                SELECT COUNT(DISTINCT v.document_id)
                FROM kb_document_version v
                WHERE v.status = 'ACTIVE'
                """).query(Integer.class).single();
    }

    public List<ChunkQualityRow> activeChunks() {
        return jdbc.sql("""
                SELECT c.id, c.version_id, c.document_code, c.document_title,
                       c.section, c.page, c.file_type, c.category, c.audience,
                       CAST(c.tags AS CHAR) AS tags_text, c.content,
                       OCTET_LENGTH(c.embedding) AS vector_bytes
                FROM kb_chunk c
                JOIN kb_document_version v ON v.id = c.version_id
                WHERE v.status = 'ACTIVE'
                ORDER BY c.document_code, c.chunk_index
                """)
                .query((rs, rowNum) -> new ChunkQualityRow(
                        rs.getLong("id"),
                        rs.getLong("version_id"),
                        rs.getString("document_code"),
                        rs.getString("document_title"),
                        rs.getString("section"),
                        rs.getInt("page"),
                        rs.getString("file_type"),
                        rs.getString("category"),
                        rs.getString("audience"),
                        rs.getString("tags_text"),
                        rs.getString("content"),
                        (Integer) rs.getObject("vector_bytes")))
                .list();
    }

    public List<KbDtos.EmbeddingProvenanceView> activeEmbeddingProvenance() {
        return jdbc.sql("""
                    SELECT d.document_code, v.id AS version_id, v.embedding_model,
                           v.embedding_dimension, v.embedding_source, v.embedded_at,
                           v.embedding_content_checksum
                      FROM kb_document_version v
                      JOIN kb_document d ON d.id = v.document_id
                     WHERE v.status = 'ACTIVE'
                     ORDER BY d.document_code
                """)
                .query((rs, row) -> new KbDtos.EmbeddingProvenanceView(
                        rs.getString("document_code"),
                        rs.getLong("version_id"),
                        rs.getString("embedding_model"),
                        rs.getObject("embedding_dimension", Integer.class),
                        rs.getString("embedding_source"),
                        rs.getTimestamp("embedded_at") == null
                                ? null : rs.getTimestamp("embedded_at").toInstant(),
                        rs.getString("embedding_content_checksum")))
                .list();
    }

    public record ChunkQualityRow(
            long chunkId,
            long versionId,
            String documentCode,
            String documentTitle,
            String section,
            int page,
            String fileType,
            String category,
            String audience,
            String tags,
            String content,
            Integer vectorBytes) { }
}
