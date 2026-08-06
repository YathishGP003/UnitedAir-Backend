package com.unitedair.ai.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-side queries for the Admin Knowledge Base console. */
@Repository
public class KbCatalogRepository {

    private final JdbcClient jdbc;

    public KbCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<KbDtos.DocumentSummary> listDocuments() {
        return jdbc.sql("""
                    SELECT d.id                AS document_id,
                           d.document_code,
                           d.title,
                           d.created_at,
                           v.id                AS version_id,
                           v.version_label,
                           v.status,
                           v.file_type,
                           v.category,
                           v.audience,
                           v.effective_from,
                           v.approved_by,
                           v.serves_frs,
                           (SELECT COUNT(*) FROM kb_chunk c WHERE c.version_id = v.id)        AS chunk_count,
                           (SELECT COUNT(*) FROM kb_document_version x WHERE x.document_id = d.id) AS version_count
                    FROM kb_document d
                    LEFT JOIN kb_document_version v
                           ON v.document_id = d.id AND v.status = 'ACTIVE'
                    ORDER BY d.document_code
                """).query(KbCatalogRepository::mapDocument).list();
    }

    public List<KbDtos.VersionSummary> listVersions(String documentCode) {
        return jdbc.sql("""
                    SELECT v.id, v.version_label, v.status, v.file_type, v.source_filename,
                           v.category, v.audience, v.effective_from, v.created_at, v.activated_at,
                           (SELECT COUNT(*) FROM kb_chunk c WHERE c.version_id = v.id) AS chunk_count
                    FROM kb_document_version v
                    JOIN kb_document d ON d.id = v.document_id
                    WHERE d.document_code = :code
                    ORDER BY v.created_at DESC
                """)
                .param("code", documentCode)
                .query((rs, n) -> new KbDtos.VersionSummary(
                        rs.getLong("id"),
                        rs.getString("version_label"),
                        rs.getString("status"),
                        rs.getString("file_type"),
                        rs.getString("source_filename"),
                        rs.getString("category"),
                        rs.getString("audience"),
                        rs.getDate("effective_from") == null ? null : rs.getDate("effective_from").toLocalDate(),
                        rs.getInt("chunk_count"),
                        instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("activated_at"))))
                .list();
    }

    public List<KbDtos.IngestionJobView> listJobs(int limit) {
        return jdbc.sql("""
                    SELECT j.job_uuid, j.version_id, j.status, j.failure_reason,
                           j.chunks_created, j.tokens_embedded, j.ingestion_time_ms,
                           j.started_at, j.finished_at,
                           d.document_code, v.source_filename, u.email AS triggered_by_email
                    FROM kb_ingestion_job j
                    JOIN kb_document_version v ON v.id = j.version_id
                    JOIN kb_document d ON d.id = v.document_id
                    LEFT JOIN app_user u ON u.id = j.triggered_by
                    ORDER BY j.id DESC
                    LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> new KbDtos.IngestionJobView(
                        rs.getString("job_uuid"),
                        rs.getLong("version_id"),
                        rs.getString("document_code"),
                        rs.getString("source_filename"),
                        rs.getString("status"),
                        rs.getString("failure_reason"),
                        rs.getInt("chunks_created"),
                        rs.getInt("tokens_embedded"),
                        (Long) rs.getObject("ingestion_time_ms"),
                        rs.getString("triggered_by_email"),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("finished_at"))))
                .list();
    }

    public KbDtos.KbStatistics statistics() {
        int documents = intOf("SELECT COUNT(*) FROM kb_document");
        int activeVersions = intOf("SELECT COUNT(*) FROM kb_document_version WHERE status = 'ACTIVE'");
        long chunks = longOf("""
                SELECT COUNT(*) FROM kb_chunk c
                JOIN kb_document_version v ON v.id = c.version_id
                WHERE v.status = 'ACTIVE'
                """);
        int succeeded = intOf("SELECT COUNT(*) FROM kb_ingestion_job WHERE status = 'SUCCEEDED'");
        int failed = intOf("SELECT COUNT(*) FROM kb_ingestion_job WHERE status = 'FAILED'");

        List<String> categories = jdbc.sql(
                "SELECT DISTINCT category FROM kb_document_version WHERE status = 'ACTIVE' ORDER BY category")
                .query(String.class).list();
        List<String> audiences = jdbc.sql(
                "SELECT DISTINCT audience FROM kb_document_version WHERE status = 'ACTIVE' ORDER BY audience")
                .query(String.class).list();

        return new KbDtos.KbStatistics(documents, activeVersions, chunks,
                succeeded, failed, categories, audiences);
    }

    /** True when nothing has ever been ingested, which is what triggers seeding. */
    public boolean isEmpty() {
        return longOf("SELECT COUNT(*) FROM kb_document") == 0;
    }

    public boolean documentCodeExists(String code) {
        Long n = jdbc.sql("SELECT COUNT(*) FROM kb_document WHERE document_code = :c")
                .param("c", code).query(Long.class).single();
        return n != null && n > 0;
    }

    private int intOf(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0 : value.intValue();
    }

    private long longOf(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private static KbDtos.DocumentSummary mapDocument(ResultSet rs, int rowNum) throws SQLException {
        return new KbDtos.DocumentSummary(
                rs.getLong("document_id"),
                rs.getString("document_code"),
                rs.getString("title"),
                (Long) rs.getObject("version_id"),
                rs.getString("version_label"),
                rs.getString("status"),
                rs.getString("file_type"),
                rs.getString("category"),
                rs.getString("audience"),
                rs.getDate("effective_from") == null ? null : rs.getDate("effective_from").toLocalDate(),
                rs.getString("approved_by"),
                rs.getString("serves_frs"),
                rs.getInt("chunk_count"),
                rs.getInt("version_count"),
                instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
