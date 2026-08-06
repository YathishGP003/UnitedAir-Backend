package com.unitedair.ai.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable ingestion-attempt ledger.
 *
 * <p>Every method owns an independent transaction. Consequently the audit survives when
 * the document/version/chunk transaction rolls back during validation or persistence.
 */
@Service
public class IngestionAttemptService {

    private final JdbcClient jdbc;

    public IngestionAttemptService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String start(Long adminUserId, String filename) {
        String uuid = UUID.randomUUID().toString();
        jdbc.sql("""
                    INSERT INTO kb_ingestion_attempt
                        (attempt_uuid, triggered_by, source_filename, status)
                    VALUES (:uuid, :userId, :filename, 'STARTED')
                """)
                .param("uuid", uuid)
                .param("userId", adminUserId, java.sql.Types.BIGINT)
                .param("filename", safeFilename(filename))
                .update();
        return uuid;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void succeed(
            String attemptUuid,
            Long documentId,
            Long versionId,
            String jobUuid,
            int chunks,
            long elapsedMs) {
        jdbc.sql("""
                    UPDATE kb_ingestion_attempt
                       SET status = 'SUCCEEDED',
                           document_id = :documentId,
                           version_id = :versionId,
                           job_uuid = :jobUuid,
                           chunks_created = :chunks,
                           ingestion_time_ms = :elapsed,
                           finished_at = CURRENT_TIMESTAMP(6)
                     WHERE attempt_uuid = :uuid
                """)
                .param("documentId", documentId, java.sql.Types.BIGINT)
                .param("versionId", versionId, java.sql.Types.BIGINT)
                .param("jobUuid", jobUuid)
                .param("chunks", chunks)
                .param("elapsed", elapsedMs)
                .param("uuid", attemptUuid)
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String attemptUuid, FailurePhase phase, String reason, long elapsedMs) {
        jdbc.sql("""
                    UPDATE kb_ingestion_attempt
                       SET status = 'FAILED',
                           failure_phase = :phase,
                           failure_reason = :reason,
                           ingestion_time_ms = :elapsed,
                           finished_at = CURRENT_TIMESTAMP(6)
                     WHERE attempt_uuid = :uuid
                """)
                .param("phase", phase.name())
                .param("reason", truncate(reason, 1000))
                .param("elapsed", elapsedMs)
                .param("uuid", attemptUuid)
                .update();
    }

    public List<AttemptView> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.sql("""
                    SELECT attempt_uuid, source_filename, status, failure_phase,
                           failure_reason, chunks_created, ingestion_time_ms,
                           started_at, finished_at
                      FROM kb_ingestion_attempt
                     ORDER BY started_at DESC
                     LIMIT :limit
                """)
                .param("limit", safeLimit)
                .query((rs, row) -> new AttemptView(
                        rs.getString("attempt_uuid"),
                        rs.getString("source_filename"),
                        rs.getString("status"),
                        rs.getString("failure_phase"),
                        rs.getString("failure_reason"),
                        rs.getInt("chunks_created"),
                        rs.getObject("ingestion_time_ms", Long.class),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null
                                ? null : rs.getTimestamp("finished_at").toInstant()))
                .list();
    }

    private static String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "upload" : filename;
        return truncate(value, 255);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public enum FailurePhase {
        VALIDATION,
        TYPE_DETECTION,
        EXTRACTION,
        FRONT_MATTER,
        CHUNKING,
        EMBEDDING,
        PERSISTENCE,
        ACTIVATION
    }

    public record AttemptView(
            String attemptUuid,
            String sourceFilename,
            String status,
            String failurePhase,
            String failureReason,
            int chunksCreated,
            Long ingestionTimeMs,
            Instant startedAt,
            Instant finishedAt) { }
}
