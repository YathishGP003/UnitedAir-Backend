package com.unitedair.ai.knowledge;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request and response shapes for the Knowledge Base API. */
public final class KbDtos {

    private KbDtos() { }

    /**
     * SRS 4.1.5 response body for a successful ingestion: documentId, chunks_created,
     * ingestion_time_ms and metadata, returned with HTTP 201.
     */
    public record IngestionResult(
            Long documentId,
            Long versionId,
            String jobUuid,
            String documentCode,
            String title,
            int chunksCreated,
            long ingestionTimeMs,
            Map<String, Object> metadata) { }

    public record DocumentSummary(
            Long documentId,
            String documentCode,
            String title,
            Long activeVersionId,
            String activeVersionLabel,
            String status,
            String fileType,
            String category,
            String audience,
            LocalDate effectiveFrom,
            String approvedBy,
            String servesFrs,
            int chunkCount,
            int versionCount,
            Instant createdAt) { }

    public record VersionSummary(
            Long versionId,
            String versionLabel,
            String status,
            String fileType,
            String sourceFilename,
            String category,
            String audience,
            LocalDate effectiveFrom,
            int chunkCount,
            Instant createdAt,
            Instant activatedAt) { }

    public record IngestionJobView(
            String jobUuid,
            Long versionId,
            String documentCode,
            String sourceFilename,
            String status,
            String failureReason,
            int chunksCreated,
            int tokensEmbedded,
            Long ingestionTimeMs,
            String triggeredByEmail,
            Instant startedAt,
            Instant finishedAt) { }

    /** Aggregate shown on the Admin console. */
    public record KbStatistics(
            int documentCount,
            int activeVersionCount,
            long chunkCount,
            int jobsSucceeded,
            int jobsFailed,
            List<String> categories,
            List<String> audiences) { }

    /** Direct KB search, used by the Admin console to sanity-check retrieval. */
    public record SearchRequest(
            String query,
            List<String> categories,
            List<String> fileTypes,
            List<String> documentCodes,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Map<String, String> tags,
            Integer topK) { }

    public record SearchHit(
            String documentCode,
            String documentTitle,
            String section,
            int page,
            String category,
            String audience,
            Double vectorScore,
            Double lexicalScore,
            double relevance,
            int rank,
            String excerpt) { }

    /** Read-only corpus and retrieval diagnostics for the Admin quality console. */
    public record KbQualityReport(
            int activeDocuments,
            int activeChunks,
            int expectedVectorDimensions,
            Map<String, Long> issueCounts,
            List<KbQualityIssue> issues,
            List<KbRetrievalProbe> probes,
            List<EmbeddingProvenanceView> embeddingProvenance,
            Instant inspectedAt) { }

    public record EmbeddingProvenanceView(
            String documentCode,
            Long versionId,
            String modelIdentifier,
            Integer dimensions,
            String generationSource,
            Instant embeddedAt,
            String contentChecksum) { }

    public record KbQualityIssue(
            String type,
            Long chunkId,
            Long versionId,
            String documentCode,
            String detail) { }

    public record KbRetrievalProbe(
            String name,
            String role,
            String query,
            double topRelevance,
            List<String> selectedDocumentCodes,
            boolean audienceViolation,
            int evidenceCount) { }
}
