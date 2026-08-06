package com.unitedair.ai.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.llm.EmbeddingGateway;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.Json;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an uploaded document into retrievable, governed Knowledge Base content.
 *
 * <p>Ingestion is versioned rather than destructive. Uploading a new revision of a document
 * creates a new version, ingests it, and only then supersedes the previous one - so a
 * failed ingestion leaves the currently published policy untouched and still answerable.
 * Retrieval only ever reads {@code ACTIVE} versions.
 *
 * <p>FR-032 requires every ingestion to be logged with the admin, the document, the
 * timestamp, the status and the chunk count. {@code kb_ingestion_job} is that record, and
 * it is written before work starts so a crash mid-ingestion still leaves evidence.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    /**
     * Vector-field weight for the governed section label.
     *
     * <p>Policy chunks are intentionally section-sized so citations remain meaningful.
     * Repeating only the section label in the embedding input lets a short semantic query
     * match that section above the SRS threshold without enabling lexical retrieval. The
     * stored and cited content is unchanged.
     */
    static final int SECTION_HEADING_WEIGHT = 8;

    private final JdbcClient jdbc;
    private final TextExtractor extractor;
    private final KbFrontMatterParser frontMatterParser;
    private final KbChunker chunker;
    private final EmbeddingGateway embeddings;
    private final IngestionAttemptService attempts;
    private final AuditService audit;
    private final UnitedAirProperties.Ingestion config;

    public IngestionService(JdbcClient jdbc,
                            TextExtractor extractor,
                            KbFrontMatterParser frontMatterParser,
                            KbChunker chunker,
                            EmbeddingGateway embeddings,
                            IngestionAttemptService attempts,
                            AuditService audit,
                            UnitedAirProperties properties) {
        this.jdbc = jdbc;
        this.extractor = extractor;
        this.frontMatterParser = frontMatterParser;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.attempts = attempts;
        this.audit = audit;
        this.config = properties.getIngestion();
    }

    /**
     * Ingests one document end to end.
     *
     * @param adminUserId the Admin who triggered this, recorded for FR-032
     */
    @Transactional
    public KbDtos.IngestionResult ingest(byte[] content,
                                         String filename,
                                         Long adminUserId,
                                         String adminRole) {
        long startedAt = System.nanoTime();
        String attemptUuid = attempts.start(adminUserId, filename);
        IngestionAttemptService.FailurePhase phase =
                IngestionAttemptService.FailurePhase.VALIDATION;
        Long documentId = null;
        Long versionId = null;
        String jobUuid = null;
        Long jobId = null;

        try {
            validateSize(content, filename);

            phase = IngestionAttemptService.FailurePhase.TYPE_DETECTION;
            String fileType = extractor.detectType(filename);
            if (!config.getAllowedTypes().contains(fileType)) {
                throw new ApiExceptions.BadRequest(
                        "File type " + fileType + " is not accepted. Allowed: "
                                + config.getAllowedTypes());
            }

            phase = IngestionAttemptService.FailurePhase.EXTRACTION;
            String text = extractor.extract(content, filename);
            if (text == null || text.isBlank()) {
                throw new ApiExceptions.BadRequest(
                        "No text could be extracted from '" + filename + "'. "
                                + "If this is a scanned PDF it must be OCRed before ingestion.");
            }

            phase = IngestionAttemptService.FailurePhase.FRONT_MATTER;
            KbFrontMatter frontMatter =
                    frontMatterParser.parse(text, stripExtension(filename));

            phase = IngestionAttemptService.FailurePhase.PERSISTENCE;
            documentId = upsertDocument(frontMatter);
            String checksum = sha256(content);
            versionId = insertVersion(documentId, frontMatter, fileType, filename,
                    content.length, checksum, adminUserId);
            jobUuid = UUID.randomUUID().toString();
            jobId = insertJob(jobUuid, versionId, adminUserId);

            phase = IngestionAttemptService.FailurePhase.CHUNKING;
            List<KbChunker.ChunkDraft> drafts = chunker.chunk(text);
            if (drafts.isEmpty()) {
                throw new ApiExceptions.BadRequest(
                        "'" + filename + "' produced no usable text chunks.");
            }

            phase = IngestionAttemptService.FailurePhase.EMBEDDING;
            List<String> passageText = drafts.stream()
                    .map(IngestionService::retrievalPassageText)
                    .toList();
            EmbeddingGateway.EmbeddingBatch embeddingBatch =
                    embeddings.embedWithProvenance(passageText);

            phase = IngestionAttemptService.FailurePhase.PERSISTENCE;
            recordEmbeddingProvenance(
                    versionId, embeddingBatch, sha256(String.join("\n", passageText)
                            .getBytes(StandardCharsets.UTF_8)));
            insertChunks(
                    versionId, frontMatter, fileType, drafts, embeddingBatch.vectors());

            phase = IngestionAttemptService.FailurePhase.ACTIVATION;
            activateVersion(documentId, versionId);

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            completeJob(jobId, "SUCCEEDED", null, drafts.size(),
                    drafts.stream().mapToInt(KbChunker.ChunkDraft::tokenEstimate).sum(), elapsedMs);
            attempts.succeed(
                    attemptUuid, documentId, versionId, jobUuid, drafts.size(), elapsedMs);

            // FR-032
            audit.record("KB_INGESTED", adminRole, adminUserId, Map.of(
                    "documentId", documentId,
                    "versionId", versionId,
                    "documentCode", frontMatter.documentCode(),
                    "filename", filename,
                    "status", "SUCCEEDED",
                    "chunksCreated", drafts.size(),
                    "ingestionTimeMs", elapsedMs));

            log.info("Ingested {} ({}) - {} chunks in {} ms",
                    frontMatter.documentCode(), filename, drafts.size(), elapsedMs);

            return new KbDtos.IngestionResult(
                    documentId,
                    versionId,
                    jobUuid,
                    frontMatter.documentCode(),
                    frontMatter.title(),
                    drafts.size(),
                    elapsedMs,
                    describeMetadata(frontMatter, fileType));

        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (jobId != null) {
                completeJob(jobId, "FAILED", reason, 0, 0, elapsedMs);
            }
            if (versionId != null) {
                markVersionFailed(versionId);
            }
            attempts.fail(attemptUuid, phase, reason, elapsedMs);
            audit.record("KB_INGESTION_FAILED", adminRole, adminUserId, Map.of(
                    "attemptUuid", attemptUuid,
                    "phase", phase.name(),
                    "filename", filename,
                    "reason", reason));
            throw e;
        }
    }

    // ------------------------------------------------------------- persistence ---

    private void validateSize(byte[] content, String filename) {
        long maxBytes = (long) config.getMaxFileMb() * 1024 * 1024;
        if (content == null || content.length == 0) {
            throw new ApiExceptions.BadRequest("'" + filename + "' is empty.");
        }
        if (content.length > maxBytes) {
            throw new ApiExceptions.BadRequest(
                    "'" + filename + "' is " + (content.length / (1024 * 1024))
                            + " MB; the limit is " + config.getMaxFileMb() + " MB.");
        }
    }

    private Long upsertDocument(KbFrontMatter fm) {
        Long existing = jdbc.sql("SELECT id FROM kb_document WHERE document_code = :code")
                .param("code", fm.documentCode())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            // The document identity is stable across versions, but corrected front matter
            // must also repair the catalog title (for example KB-AIR-008's separator-line
            // title from an early build).
            jdbc.sql("UPDATE kb_document SET title = :title WHERE id = :id")
                    .param("title", fm.title())
                    .param("id", existing)
                    .update();
            return existing;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO kb_document (document_code, title) VALUES (:code, :title)")
                .param("code", fm.documentCode())
                .param("title", fm.title())
                .update(keys);
        return keys.getKey().longValue();
    }

    static String retrievalPassageText(KbChunker.ChunkDraft draft) {
        if (draft == null) {
            return "";
        }
        String section = draft.section() == null ? "" : draft.section().trim();
        if (section.isBlank() || "Introduction".equalsIgnoreCase(section)) {
            return draft.content();
        }
        return (section + "\n").repeat(SECTION_HEADING_WEIGHT) + draft.content();
    }

    /**
     * Versions are unique per (document, label). Re-uploading the same labelled version
     * gets a suffixed label rather than an error, because during a demo people re-upload
     * the same file repeatedly and failing on a uniqueness constraint helps nobody.
     */
    private Long insertVersion(Long documentId, KbFrontMatter fm, String fileType,
                               String filename, int sizeBytes, String checksum, Long adminUserId) {
        String label = fm.versionLabel();
        int suffix = 1;
        while (versionLabelExists(documentId, label)) {
            label = fm.versionLabel() + "-" + (++suffix);
        }

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                    INSERT INTO kb_document_version
                        (document_id, version_label, status, file_type, source_filename,
                         file_size_bytes, checksum_sha256, category, audience,
                         effective_from, effective_to, approved_by, classification,
                         ingestion_tags, serves_frs, uploaded_by)
                    VALUES
                        (:documentId, :label, 'DRAFT', :fileType, :filename,
                         :size, :checksum, :category, :audience,
                         :effectiveFrom, :effectiveTo, :approvedBy, :classification,
                         :tags, :frs, :uploadedBy)
                """)
                .param("documentId", documentId)
                .param("label", label)
                .param("fileType", fileType)
                .param("filename", filename)
                .param("size", sizeBytes)
                .param("checksum", checksum)
                .param("category", fm.srsCategory())
                .param("audience", String.join(",", fm.audiences()))
                .param("effectiveFrom", fm.effectiveFrom() == null ? null : java.sql.Date.valueOf(fm.effectiveFrom()))
                .param("effectiveTo", fm.effectiveTo() == null ? null : java.sql.Date.valueOf(fm.effectiveTo()))
                .param("approvedBy", fm.approvedBy())
                .param("classification", fm.classification())
                .param("tags", Json.write(fm.ingestionTags()))
                .param("frs", String.join(",", fm.servesFrs()))
                .param("uploadedBy", adminUserId, java.sql.Types.BIGINT)
                .update(keys);
        return keys.getKey().longValue();
    }

    private boolean versionLabelExists(Long documentId, String label) {
        Long count = jdbc.sql("""
                    SELECT COUNT(*) FROM kb_document_version
                    WHERE document_id = :documentId AND version_label = :label
                """)
                .param("documentId", documentId)
                .param("label", label)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    private Long insertJob(String jobUuid, Long versionId, Long adminUserId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                    INSERT INTO kb_ingestion_job (job_uuid, version_id, status, triggered_by, started_at)
                    VALUES (:uuid, :versionId, 'RUNNING', :userId, CURRENT_TIMESTAMP)
                """)
                .param("uuid", jobUuid)
                .param("versionId", versionId)
                .param("userId", adminUserId, java.sql.Types.BIGINT)
                .update(keys);
        return keys.getKey().longValue();
    }

    private void completeJob(Long jobId, String status, String failureReason,
                             int chunks, int tokens, long elapsedMs) {
        jdbc.sql("""
                    UPDATE kb_ingestion_job
                       SET status = :status,
                           failure_reason = :reason,
                           chunks_created = :chunks,
                           tokens_embedded = :tokens,
                           ingestion_time_ms = :elapsed,
                           finished_at = CURRENT_TIMESTAMP
                     WHERE id = :id
                """)
                .param("status", status)
                .param("reason", truncate(failureReason, 1000))
                .param("chunks", chunks)
                .param("tokens", tokens)
                .param("elapsed", elapsedMs)
                .param("id", jobId)
                .update();
    }

    private void insertChunks(Long versionId, KbFrontMatter fm, String fileType,
                              List<KbChunker.ChunkDraft> drafts, List<float[]> vectors) {
        String tags = Json.write(fm.ingestionTags());

        for (int i = 0; i < drafts.size(); i++) {
            KbChunker.ChunkDraft draft = drafts.get(i);
            String audience = ChunkAudienceResolver.resolve(
                    fm.audiences(), draft.section(), draft.content());
            jdbc.sql("""
                        INSERT INTO kb_chunk
                            (chunk_uuid, version_id, document_code, document_title, chunk_index,
                             section, section_path, page, file_type, category, audience,
                             effective_from, effective_to, tags, content, token_estimate, embedding)
                        VALUES
                            (:uuid, :versionId, :code, :title, :index,
                             :section, :sectionPath, :page, :fileType, :category, :audience,
                             :effectiveFrom, :effectiveTo, :tags, :content, :tokens,
                             VEC_FromText(:embedding))
                    """)
                    .param("uuid", UUID.randomUUID().toString())
                    .param("versionId", versionId)
                    .param("code", fm.documentCode())
                    .param("title", truncate(fm.title(), 255))
                    .param("index", draft.index())
                    .param("section", truncate(draft.section(), 255))
                    .param("sectionPath", truncate(draft.sectionNumber(), 255))
                    .param("page", draft.page())
                    .param("fileType", fileType)
                    .param("category", fm.srsCategory())
                    .param("audience", audience)
                    .param("effectiveFrom", fm.effectiveFrom() == null ? null : java.sql.Date.valueOf(fm.effectiveFrom()))
                    .param("effectiveTo", fm.effectiveTo() == null ? null : java.sql.Date.valueOf(fm.effectiveTo()))
                    .param("tags", tags)
                    .param("content", draft.content())
                    .param("tokens", draft.tokenEstimate())
                    .param("embedding", KbRepository.toVectorLiteral(vectors.get(i)))
                    .update();
        }
    }

    private void recordEmbeddingProvenance(
            Long versionId,
            EmbeddingGateway.EmbeddingBatch batch,
            String contentChecksum) {
        jdbc.sql("""
                    UPDATE kb_document_version
                       SET embedding_model = :model,
                           embedding_dimension = :dimension,
                           embedding_source = :source,
                           embedded_at = :embeddedAt,
                           embedding_content_checksum = :checksum
                     WHERE id = :versionId
                """)
                .param("model", batch.modelIdentifier())
                .param("dimension", batch.dimensions())
                .param("source", batch.generationSource())
                .param("embeddedAt", java.sql.Timestamp.from(batch.embeddedAt()))
                .param("checksum", contentChecksum)
                .param("versionId", versionId)
                .update();
    }

    /** Publishes the new version and supersedes any previously active one, atomically. */
    private void activateVersion(Long documentId, Long versionId) {
        jdbc.sql("""
                    UPDATE kb_document_version
                       SET status = 'SUPERSEDED'
                     WHERE document_id = :documentId AND status = 'ACTIVE' AND id <> :versionId
                """)
                .param("documentId", documentId)
                .param("versionId", versionId)
                .update();

        jdbc.sql("""
                    UPDATE kb_document_version
                       SET status = 'ACTIVE', activated_at = CURRENT_TIMESTAMP
                     WHERE id = :versionId
                """)
                .param("versionId", versionId)
                .update();
    }

    private void markVersionFailed(Long versionId) {
        jdbc.sql("UPDATE kb_document_version SET status = 'FAILED' WHERE id = :id")
                .param("id", versionId)
                .update();
    }

    // ------------------------------------------------------------------ helpers ---

    private Map<String, Object> describeMetadata(KbFrontMatter fm, String fileType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentCode", fm.documentCode());
        metadata.put("title", fm.title());
        metadata.put("version", fm.versionLabel());
        metadata.put("fileType", fileType);
        metadata.put("category", fm.srsCategory());
        metadata.put("rawCategory", fm.category());
        metadata.put("audience", fm.audiences());
        metadata.put("effectiveFrom", fm.effectiveFrom() == null ? null : fm.effectiveFrom().toString());
        metadata.put("approvedBy", fm.approvedBy());
        metadata.put("classification", fm.classification());
        metadata.put("ingestionTags", fm.ingestionTags());
        metadata.put("servesFrs", fm.servesFrs());
        metadata.put("ingestedAt", Instant.now().toString());
        return metadata;
    }

    private static String stripExtension(String filename) {
        if (filename == null) {
            return "document";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            return HexFormat.of().formatHex(String.valueOf(content.length)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }
}
