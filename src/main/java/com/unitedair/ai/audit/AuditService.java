package com.unitedair.ai.audit;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.unitedair.ai.commerce.CommerceDtos;
import com.unitedair.ai.shared.Json;
import com.unitedair.ai.shared.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * Writes the immutable trail that {@code GET /audit/{sessionId}} reads back.
 *
 * <h2>Auditing must not be able to break answering</h2>
 * Every write here is wrapped so that a failure is logged and swallowed. A malformed JSON
 * payload or a transient database hiccup should cost us an audit row, not a passenger's
 * answer. The one exception is {@link #saveRetrievalRun}, whose generated id the pipeline
 * needs in order to attach evidence; that returns {@code null} on failure and callers treat
 * a null run id as "evidence not linkable" rather than as a fatal error.
 *
 * <p>Nothing written here contains raw user text. Callers pass already-redacted strings
 * (SRS 4.1.6); {@code AnswerAssembler} is the only producer of these values and it redacts
 * before it persists.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- events ---

    /** Records an event against the ambient trace and session. */
    public void record(String eventType, String actorRole, Long userId, Map<String, Object> payload) {
        recordFor(TraceContext.sessionUuid(), TraceContext.traceId(), eventType, actorRole, userId, payload);
    }

    public void recordFor(String sessionUuid,
                          String traceId,
                          String eventType,
                          String actorRole,
                          Long userId,
                          Map<String, Object> payload) {
        try {
            jdbc.sql("""
                        INSERT INTO audit_event (session_uuid, trace_id, event_type, actor_role, user_id, payload)
                        VALUES (:sessionUuid, :traceId, :eventType, :actorRole, :userId, :payload)
                    """)
                    .param("sessionUuid", sessionUuid)
                    .param("traceId", traceId)
                    .param("eventType", eventType)
                    .param("actorRole", actorRole)
                    .param("userId", userId, Types.BIGINT)
                    .param("payload", Json.write(payload == null ? Map.of() : payload))
                    .update();
        } catch (Exception e) {
            log.warn("Audit event {} could not be recorded: {}", eventType, e.toString());
        }
    }

    /**
     * Records the semantic route without retaining conversation text or model output.
     */
    public void recordSemanticRoute(
            String actorRole,
            Long userId,
            String routeSource,
            String scope,
            boolean historyUsed,
            String contextReason,
            double confidence,
            String degradationReason) {
        String event = degradationReason == null
                ? "SEMANTIC_ROUTE_DECIDED" : "SEMANTIC_ROUTE_DEGRADED";
        record(event, actorRole, userId, semanticRoutePayload(
                routeSource, scope, historyUsed, contextReason,
                confidence, degradationReason));
    }

    public void recordOperationalQuery(
            String eventType,
            String actorRole,
            Long userId,
            String dataset,
            String queryFingerprint,
            long durationMs,
            int rowCount,
            boolean truncated,
            String degradationReason) {
        record(eventType, actorRole, userId, operationalQueryPayload(
                dataset, queryFingerprint, durationMs, rowCount,
                truncated, degradationReason));
    }

    /**
     * Builds the allowlisted semantic-route audit payload. Raw queries, histories,
     * provider responses and trusted identifiers deliberately have no input slot.
     */
    public static Map<String, Object> semanticRoutePayload(
            String routeSource,
            String scope,
            boolean historyUsed,
            String contextReason,
            double confidence,
            String degradationReason) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeSource", safeCode(routeSource, "UNKNOWN"));
        payload.put("scope", safeCode(scope, "UNKNOWN"));
        payload.put("historyUsed", historyUsed);
        payload.put("contextReason", safeCode(contextReason, "UNSPECIFIED"));
        payload.put("semanticConfidence", confidence);
        if (degradationReason != null && !degradationReason.isBlank()) {
            payload.put("degradationReason",
                    safeCode(degradationReason, "SEMANTIC_ROUTE_UNAVAILABLE"));
        }
        return Map.copyOf(payload);
    }

    /**
     * Builds an operational-query audit payload containing provenance and counters
     * only. SQL, bound values and result rows cannot be passed to this API.
     */
    public static Map<String, Object> operationalQueryPayload(
            String dataset,
            String queryFingerprint,
            long durationMs,
            int rowCount,
            boolean truncated,
            String degradationReason) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataset", safeCode(dataset, "UNKNOWN"));
        payload.put("queryFingerprint",
                queryFingerprint == null ? "" : queryFingerprint);
        payload.put("durationMs", Math.max(0L, durationMs));
        payload.put("rowCount", Math.max(0, rowCount));
        payload.put("truncated", truncated);
        if (degradationReason != null && !degradationReason.isBlank()) {
            payload.put("degradationReason",
                    safeCode(degradationReason, "OPERATIONAL_QUERY_UNAVAILABLE"));
        }
        return Map.copyOf(payload);
    }

    private static String safeCode(String value, String fallback) {
        if (value == null || value.isBlank()
                || !value.matches("[A-Za-z0-9_\\-]{1,80}")) {
            return fallback;
        }
        return value;
    }

    // ---------------------------------------------------- retrieval + evidence ---

    /** @return the generated retrieval_run id, or null if the row could not be written */
    public Long saveRetrievalRun(AuditDtos.RetrievalRunRecord run) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("""
                        INSERT INTO retrieval_run
                            (session_uuid, trace_id, query_redacted, query_rewritten, lane, attempt,
                             filters_json, vector_hits, lexical_hits, fused_hits, kept_hits,
                             top_similarity, confidence, duration_ms)
                        VALUES
                            (:sessionUuid, :traceId, :query, :rewritten, :lane, :attempt,
                             :filters, :vectorHits, :lexicalHits, :fusedHits, :keptHits,
                             :topSimilarity, :confidence, :durationMs)
                    """)
                    .param("sessionUuid", run.sessionUuid())
                    .param("traceId", run.traceId())
                    .param("query", run.queryRedacted())
                    .param("rewritten", run.queryRewritten())
                    .param("lane", run.lane())
                    .param("attempt", run.attempt())
                    .param("filters", Json.write(run.filters()))
                    .param("vectorHits", run.vectorHits())
                    .param("lexicalHits", run.lexicalHits())
                    .param("fusedHits", run.fusedHits())
                    .param("keptHits", run.keptHits())
                    .param("topSimilarity", run.topSimilarity())
                    .param("confidence", run.confidence())
                    .param("durationMs", run.durationMs())
                    .update(keys);
            Number key = keys.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception e) {
            log.warn("Retrieval run could not be recorded: {}", e.toString());
            return null;
        }
    }

    public void saveEvidence(Long retrievalRunId, List<AuditDtos.EvidenceRow> rows) {
        if (retrievalRunId == null || rows == null || rows.isEmpty()) {
            return;
        }
        try {
            for (AuditDtos.EvidenceRow row : rows) {
                jdbc.sql("""
                            INSERT INTO evidence_record
                                (retrieval_run_id, chunk_id, document_code, document_title, section, page,
                                 evidence_handle, vector_score, lexical_score, fused_score,
                                 rank_position, used_in_answer, excerpt)
                            VALUES
                                (:runId, :chunkId, :documentCode, :documentTitle, :section, :page,
                                 :handle, :vectorScore, :lexicalScore, :fusedScore,
                                 :rank, :used, :excerpt)
                        """)
                        .param("runId", retrievalRunId)
                        .param("chunkId", row.chunkId(), Types.BIGINT)
                        .param("documentCode", row.documentCode())
                        .param("documentTitle", row.documentTitle())
                        .param("section", row.section())
                        .param("page", row.page())
                        .param("handle", row.handle())
                        .param("vectorScore", row.vectorScore())
                        .param("lexicalScore", row.lexicalScore())
                        .param("fusedScore", row.fusedScore())
                        .param("rank", row.rank())
                        .param("used", row.usedInAnswer())
                        .param("excerpt", row.excerpt())
                        .update();
            }
        } catch (Exception e) {
            log.warn("Evidence rows could not be recorded: {}", e.toString());
        }
    }

    // -------------------------------------------------------------- answers ---

    public Long saveAnswerRecord(AuditDtos.AnswerRecordRow row) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("""
                        INSERT INTO answer_record
                            (session_uuid, trace_id, retrieval_run_id, status, intent, actor_role,
                             answer_redacted, citations_json, followups_json, tools_used_json,
                             confidence, citation_coverage, repair_attempts, escalated,
                             model_name, ai_mode, degraded_reason,
                             prompt_tokens, completion_tokens, duration_ms)
                        VALUES
                            (:sessionUuid, :traceId, :runId, :status, :intent, :actorRole,
                             :answer, :citations, :followups, :tools,
                             :confidence, :coverage, :repairs, :escalated,
                             :model, :aiMode, :degradedReason,
                             :promptTokens, :completionTokens, :durationMs)
                    """)
                    .param("sessionUuid", row.sessionUuid())
                    .param("traceId", row.traceId())
                    .param("runId", row.retrievalRunId(), Types.BIGINT)
                    .param("status", row.status())
                    .param("intent", row.intent())
                    .param("actorRole", row.actorRole())
                    .param("answer", row.answerRedacted())
                    .param("citations", Json.write(row.citations()))
                    .param("followups", Json.write(row.followups()))
                    .param("tools", Json.write(row.toolsUsed()))
                    .param("confidence", row.confidence())
                    .param("coverage", row.citationCoverage())
                    .param("repairs", row.repairAttempts())
                    .param("escalated", row.escalated())
                    .param("model", row.modelName())
                    .param("aiMode", row.aiMode())
                    .param("degradedReason", row.degradedReason())
                    .param("promptTokens", row.promptTokens(), Types.INTEGER)
                    .param("completionTokens", row.completionTokens(), Types.INTEGER)
                    .param("durationMs", row.durationMs(), Types.BIGINT)
                    .update(keys);
            Number key = keys.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception e) {
            log.warn("Answer record could not be recorded: {}", e.toString());
            return null;
        }
    }

    /**
     * Persists a verified Passenger-commerce response so reopening a conversation restores
     * the same interactive flight, checkout or cancellation card rather than text alone.
     */
    public Long saveCommerceAnswer(
            String sessionUuid,
            String traceId,
            String actorRole,
            String answerRedacted,
            CommerceDtos.CommercePayload commerce,
            List<String> toolsUsed,
            List<com.unitedair.ai.grounding.GroundingDtos.Citation> citations,
            List<String> followups,
            String aiMode,
            Long durationMs) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("""
                        INSERT INTO answer_record
                            (session_uuid, trace_id, status, intent, actor_role,
                             answer_redacted, citations_json, followups_json, tools_used_json,
                             confidence, citation_coverage, repair_attempts, escalated,
                             model_name, ai_mode, commerce_json, duration_ms)
                        VALUES
                            (:sessionUuid, :traceId, 'TOOL_GROUNDED', 'BOOK_FLIGHT', :actorRole,
                             :answer, :citations, :followups, :tools,
                             1.0, 1.0, 0, FALSE,
                             'deterministic-commerce-coordinator', :aiMode, :commerce, :durationMs)
                    """)
                    .param("sessionUuid", sessionUuid)
                    .param("traceId", traceId)
                    .param("actorRole", actorRole)
                    .param("answer", answerRedacted)
                    .param("citations", Json.write(citations))
                    .param("followups", Json.write(followups))
                    .param("tools", Json.write(toolsUsed))
                    .param("aiMode", aiMode)
                    .param("commerce", Json.write(commerce))
                    .param("durationMs", durationMs, Types.BIGINT)
                    .update(keys);
            Number key = keys.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception e) {
            log.warn("Commerce answer record could not be recorded: {}", e.toString());
            return null;
        }
    }

    public void saveValidation(AuditDtos.ValidationRow row) {
        try {
            jdbc.sql("""
                        INSERT INTO answer_validation
                            (session_uuid, trace_id, answer_record_id, attempt_no, verdict,
                             failed_gates, citation_coverage, confidence, detail_json,
                             triggered_repair, escalation_case_id)
                        VALUES
                            (:sessionUuid, :traceId, :answerId, :attempt, :verdict,
                             :gates, :coverage, :confidence, :detail,
                             :repair, :escalationId)
                    """)
                    .param("sessionUuid", row.sessionUuid())
                    .param("traceId", row.traceId())
                    .param("answerId", row.answerRecordId(), Types.BIGINT)
                    .param("attempt", row.attemptNo())
                    .param("verdict", row.verdict())
                    .param("gates", row.failedGates())
                    .param("coverage", row.citationCoverage())
                    .param("confidence", row.confidence())
                    .param("detail", Json.write(row.detail()))
                    .param("repair", row.triggeredRepair())
                    .param("escalationId", row.escalationCaseId(), Types.BIGINT)
                    .update();
        } catch (Exception e) {
            log.warn("Validation verdict could not be recorded: {}", e.toString());
        }
    }
}
