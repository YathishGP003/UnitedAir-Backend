package com.unitedair.ai.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.unitedair.ai.commerce.CommerceDtos;
import com.unitedair.ai.grounding.GroundingDtos;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reconstructs a full session trail for {@code GET /audit/{sessionId}} (SRS 5).
 *
 * <p>The trail is assembled per trace: one entry per question asked, carrying the query, the
 * retrieval run and its evidence with scores, the tool calls, the validation verdicts and
 * the answer. That is the shape someone actually needs when asking "why did it say that?".
 */
@Repository
public class AuditTrailRepository {

    private final JdbcClient jdbc;

    public AuditTrailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AuditDtos.SessionTrail trailFor(String sessionUuid) {
        List<Map<String, Object>> sessionRows = jdbc.sql("""
                    SELECT actor_role, created_at, last_activity_at, expired
                    FROM chat_session WHERE session_uuid = :s
                """)
                .param("s", sessionUuid)
                .query()
                .listOfRows();
        if (sessionRows.isEmpty()) {
            throw new ApiExceptions.NotFound("No such session.");
        }
        Map<String, Object> session = sessionRows.get(0);

        List<AuditDtos.TurnTrail> turns = buildTurns(sessionUuid);

        List<AuditDtos.EventView> events = jdbc.sql("""
                    SELECT event_type, actor_role, created_at, payload
                    FROM audit_event WHERE session_uuid = :s
                    ORDER BY id
                """)
                .param("s", sessionUuid)
                .query((rs, n) -> new AuditDtos.EventView(
                        rs.getString("event_type"),
                        rs.getString("actor_role"),
                        rs.getTimestamp("created_at").toInstant(),
                        Json.readMap(rs.getString("payload"))))
                .list();

        List<AuditDtos.EscalationView> escalations = jdbc.sql("""
                    SELECT case_uuid, reason, target_queue, priority, status, raised_by_system,
                           failed_gates, confidence, summary_redacted, created_at
                    FROM escalation_case WHERE session_uuid = :s
                    ORDER BY id DESC
                """)
                .param("s", sessionUuid)
                .query(AuditTrailRepository::mapEscalation)
                .list();

        return new AuditDtos.SessionTrail(
                sessionUuid,
                (String) session.get("actor_role"),
                toInstant(session.get("created_at")),
                toInstant(session.get("last_activity_at")),
                Boolean.TRUE.equals(session.get("expired")) || Integer.valueOf(1).equals(session.get("expired")),
                turns, events, escalations);
    }

    private List<AuditDtos.TurnTrail> buildTurns(String sessionUuid) {
        // One turn per answer. Answers are the anchor because every turn produces exactly
        // one, whereas retrieval runs can be one or two depending on whether a repair ran.
        List<Map<String, Object>> answers = jdbc.sql("""
                    SELECT a.trace_id, a.status, a.intent, a.escalated, a.confidence,
                           a.citation_coverage, a.repair_attempts, a.answer_redacted,
                           a.citations_json, a.followups_json, a.model_name, a.ai_mode,
                           a.degraded_reason, a.commerce_json,
                           a.duration_ms, a.created_at, a.retrieval_run_id
                    FROM answer_record a
                    WHERE a.session_uuid = :s
                    ORDER BY a.id
                """).param("s", sessionUuid).query().listOfRows();

        List<AuditDtos.TurnTrail> turns = new ArrayList<>();

        for (Map<String, Object> answer : answers) {
            String traceId = (String) answer.get("trace_id");

            List<Map<String, Object>> runRows = jdbc.sql("""
                        SELECT id, query_redacted, query_rewritten, lane, attempt
                        FROM retrieval_run
                        WHERE trace_id = :t
                        ORDER BY id DESC LIMIT 1
                    """).param("t", traceId).query().listOfRows();
            // A tool-only or escalated turn has no retrieval run; an empty map keeps the
            // rest of the trail readable rather than dropping the turn entirely.
            Map<String, Object> run = runRows.isEmpty() ? new LinkedHashMap<>() : runRows.get(0);
            String queryRedacted = (String) run.get("query_redacted");
            if (queryRedacted == null) {
                List<Map<String, Object>> userMessages = jdbc.sql("""
                            SELECT m.content_redacted
                            FROM chat_message m
                            JOIN chat_session s ON s.id = m.session_id
                            WHERE s.session_uuid = :s AND m.trace_id = :t AND m.role = 'USER'
                            ORDER BY m.seq LIMIT 1
                        """)
                        .param("s", sessionUuid)
                        .param("t", traceId)
                        .query().listOfRows();
                if (!userMessages.isEmpty()) {
                    queryRedacted = (String) userMessages.get(0).get("content_redacted");
                }
            }

            List<AuditDtos.EvidenceView> evidence = run.get("id") == null ? List.of()
                    : jdbc.sql("""
                            SELECT evidence_handle, document_code, document_title, section, page,
                                   vector_score, lexical_score, fused_score, rank_position,
                                   used_in_answer, excerpt
                            FROM evidence_record WHERE retrieval_run_id = :r
                            ORDER BY rank_position
                        """)
                    .param("r", run.get("id"))
                    .query((rs, n) -> new AuditDtos.EvidenceView(
                            rs.getString("evidence_handle"),
                            rs.getString("document_code"),
                            rs.getString("document_title"),
                            rs.getString("section"),
                            (Integer) rs.getObject("page"),
                            toDouble(rs.getObject("vector_score")),
                            toDouble(rs.getObject("lexical_score")),
                            toDouble(rs.getObject("fused_score")),
                            rs.getInt("rank_position"),
                            rs.getBoolean("used_in_answer"),
                            rs.getString("excerpt")))
                    .list();

            List<AuditDtos.ToolCallView> toolCalls = jdbc.sql("""
                        SELECT tool_name, status, dispatch_pattern, attempt_no, duration_ms,
                               invoked_at, error_message, request_json, result_json
                        FROM tool_invocation WHERE trace_id = :t
                        ORDER BY id
                    """)
                    .param("t", traceId)
                    .query((rs, n) -> new AuditDtos.ToolCallView(
                            rs.getString("tool_name"),
                            rs.getString("status"),
                            rs.getString("dispatch_pattern"),
                            rs.getInt("attempt_no"),
                            (Long) rs.getObject("duration_ms"),
                            rs.getTimestamp("invoked_at").toInstant(),
                            rs.getString("error_message"),
                            Json.readMap(rs.getString("request_json")),
                            Json.readMap(rs.getString("result_json"))))
                    .list();

            List<AuditDtos.ValidationView> validations = jdbc.sql("""
                        SELECT attempt_no, verdict, failed_gates, citation_coverage,
                               confidence, triggered_repair
                        FROM answer_validation WHERE trace_id = :t
                        ORDER BY attempt_no
                    """)
                    .param("t", traceId)
                    .query((rs, n) -> new AuditDtos.ValidationView(
                            rs.getInt("attempt_no"),
                            rs.getString("verdict"),
                            rs.getString("failed_gates"),
                            toDouble(rs.getObject("citation_coverage")),
                            toDouble(rs.getObject("confidence")),
                            rs.getBoolean("triggered_repair")))
                    .list();

            turns.add(new AuditDtos.TurnTrail(
                    traceId,
                    toInstant(answer.get("created_at")),
                    queryRedacted,
                    (String) run.get("query_rewritten"),
                    (String) answer.get("intent"),
                    (String) run.get("lane"),
                    run.get("attempt") == null ? 1 : ((Number) run.get("attempt")).intValue(),
                    (String) answer.get("status"),
                    isTrue(answer.get("escalated")),
                    toDouble(answer.get("confidence")),
                    toDouble(answer.get("citation_coverage")),
                    answer.get("repair_attempts") == null ? 0
                            : ((Number) answer.get("repair_attempts")).intValue(),
                    (String) answer.get("answer_redacted"),
                    readCitations((String) answer.get("citations_json")),
                    (String) answer.get("model_name"),
                    (String) answer.get("ai_mode"),
                    (String) answer.get("degraded_reason"),
                    Json.read((String) answer.get("commerce_json"),
                            CommerceDtos.CommercePayload.class),
                    answer.get("duration_ms") == null ? null
                            : ((Number) answer.get("duration_ms")).longValue(),
                    evidence, toolCalls, validations,
                    Json.readStringList((String) answer.get("followups_json"))));
        }

        return turns;
    }

    /** Open escalation cases across all sessions, for the Admin console. */
    public List<AuditDtos.EscalationView> openEscalations(int limit) {
        return jdbc.sql("""
                    SELECT case_uuid, reason, target_queue, priority, status, raised_by_system,
                           failed_gates, confidence, summary_redacted, created_at
                    FROM escalation_case
                    ORDER BY (status = 'OPEN') DESC, id DESC
                    LIMIT :limit
                """)
                .param("limit", limit)
                .query(AuditTrailRepository::mapEscalation)
                .list();
    }

    public void resolveEscalation(String caseUuid, String note) {
        int updated = jdbc.sql("""
                    UPDATE escalation_case
                       SET status = 'RESOLVED', resolution_note = :note, resolved_at = CURRENT_TIMESTAMP
                     WHERE case_uuid = :uuid AND status <> 'RESOLVED'
                """)
                .param("note", note)
                .param("uuid", caseUuid)
                .update();
        if (updated == 0) {
            throw new ApiExceptions.NotFound("No open escalation case with that reference.");
        }
    }

    private static AuditDtos.EscalationView mapEscalation(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new AuditDtos.EscalationView(
                rs.getString("case_uuid"),
                rs.getString("reason"),
                rs.getString("target_queue"),
                rs.getString("priority"),
                rs.getString("status"),
                rs.getBoolean("raised_by_system"),
                rs.getString("failed_gates"),
                toDouble(rs.getObject("confidence")),
                rs.getString("summary_redacted"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Instant toInstant(Object value) {
        return value instanceof Timestamp ts ? ts.toInstant() : null;
    }

    /**
     * MariaDB returns {@code DECIMAL} columns as {@link java.math.BigDecimal}, so casting
     * them straight to {@code Double} throws. Every score and confidence column in the
     * schema is DECIMAL, which is why this goes through {@link Number} rather than a cast.
     */
    private static Double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || Integer.valueOf(1).equals(value)
                || (value instanceof Number n && n.intValue() == 1);
    }

    private static List<GroundingDtos.Citation> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Json.mapper().readValue(
                    json, new TypeReference<List<GroundingDtos.Citation>>() { });
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
