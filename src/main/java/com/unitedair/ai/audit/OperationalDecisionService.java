package com.unitedair.ai.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.Json;
import com.unitedair.ai.shared.TraceContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Normalized FR-030 audit of operational proposals and their final outcomes. */
@Service
public class OperationalDecisionService {

    private final JdbcClient jdbc;

    public OperationalDecisionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String recordProposal(
            String actionUuid,
            ActionDtos.ActionType actionType,
            CurrentUser.Authenticated actor,
            String pnr,
            String sessionUuid,
            Map<String, Object> summary) {
        OperationalDecisionDtos.DecisionType type = map(actionType);
        String uuid = UUID.randomUUID().toString();
        jdbc.sql("""
                    INSERT INTO operational_decision
                        (decision_uuid, action_uuid, decision_type, outcome,
                         actor_user_id, actor_role, pnr_hash, pnr_display,
                         source_policy_code, source_policy_section,
                         trace_id, session_uuid, detail_json)
                    VALUES (:uuid, :actionUuid, :type, 'PROPOSED',
                            :userId, :role, :pnrHash, '[AIR-PNR-REDACTED]',
                            :policyCode, :policySection,
                            :traceId, :sessionUuid, :detail)
                """)
                .param("uuid", uuid)
                .param("actionUuid", actionUuid)
                .param("type", type.name())
                .param("userId", actor == null ? null : actor.id(), Types.BIGINT)
                .param("role", actor == null ? "SYSTEM" : actor.role().name())
                .param("pnrHash", hashPnr(pnr), Types.CHAR)
                .param("policyCode", string(summary, "policyDocumentCode"), Types.VARCHAR)
                .param("policySection", string(summary, "policySection"), Types.VARCHAR)
                .param("traceId", TraceContext.traceId())
                .param("sessionUuid", sessionUuid, Types.CHAR)
                .param("detail", Json.write(redactDetail(summary, pnr)))
                .update();
        return uuid;
    }

    public void recordOutcome(
            String actionUuid,
            String outcome,
            String reason,
            CurrentUser.Authenticated actor,
            Map<String, Object> detail) {
        jdbc.sql("""
                    UPDATE operational_decision
                       SET outcome = :outcome,
                           reason = :reason,
                           actor_user_id = COALESCE(:userId, actor_user_id),
                           actor_role = COALESCE(:role, actor_role),
                           detail_json = :detail,
                           trace_id = COALESCE(:traceId, trace_id),
                           decided_at = CURRENT_TIMESTAMP(6)
                     WHERE action_uuid = :actionUuid
                       AND outcome = 'PROPOSED'
                """)
                .param("outcome", normalizeOutcome(outcome))
                .param("reason", reason, Types.VARCHAR)
                .param("userId", actor == null ? null : actor.id(), Types.BIGINT)
                .param("role", actor == null ? null : actor.role().name(), Types.VARCHAR)
                .param("detail", Json.write(detail == null ? Map.of() : detail))
                .param("traceId", TraceContext.traceId())
                .param("actionUuid", actionUuid)
                .update();
    }

    public List<OperationalDecisionDtos.DecisionView> search(
            OperationalDecisionDtos.DecisionQuery query) {
        OperationalDecisionDtos.DecisionQuery safe = query == null
                ? new OperationalDecisionDtos.DecisionQuery(
                        Set.of(), null, null, null, null, null)
                : query;
        if (safe.from() != null && safe.to() != null && safe.from().isAfter(safe.to())) {
            throw new ApiExceptions.BadRequest("from must be on or before to.");
        }
        Map<String, Object> params = new HashMap<>();
        StringJoiner where = new StringJoiner("\n AND ", "WHERE ", "");
        if (safe.types() != null && !safe.types().isEmpty()) {
            StringJoiner values = new StringJoiner(",", "decision_type IN (", ")");
            int index = 0;
            for (var type : safe.types()) {
                String key = "type" + index++;
                values.add(":" + key);
                params.put(key, type.name());
            }
            where.add(values.toString());
        }
        if (safe.pnr() != null && !safe.pnr().isBlank()) {
            where.add("pnr_hash = :pnrHash");
            params.put("pnrHash", hashPnr(safe.pnr()));
        }
        if (safe.decidedBy() != null) {
            where.add("actor_user_id = :actor");
            params.put("actor", safe.decidedBy());
        }
        if (safe.from() != null) {
            where.add("created_at >= :from");
            params.put("from", java.sql.Timestamp.from(safe.from()));
        }
        if (safe.to() != null) {
            where.add("created_at <= :to");
            params.put("to", java.sql.Timestamp.from(safe.to()));
        }
        if (safe.outcome() != null && !safe.outcome().isBlank()) {
            where.add("outcome = :outcome");
            params.put("outcome", normalizeOutcome(safe.outcome()));
        }
        String clause = params.isEmpty() ? "" : where.toString();
        var statement = jdbc.sql("""
                    SELECT decision_uuid, action_uuid, decision_type, outcome,
                           actor_user_id, actor_role, pnr_display, reason,
                           source_policy_code, source_policy_section, trace_id,
                           session_uuid, detail_json, created_at, decided_at
                      FROM operational_decision
                     %s
                     ORDER BY created_at DESC
                     LIMIT 200
                """.formatted(clause));
        for (var parameter : params.entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query(OperationalDecisionService::mapView).list();
    }

    public OperationalDecisionDtos.DecisionView get(String uuid) {
        return jdbc.sql("""
                    SELECT decision_uuid, action_uuid, decision_type, outcome,
                           actor_user_id, actor_role, pnr_display, reason,
                           source_policy_code, source_policy_section, trace_id,
                           session_uuid, detail_json, created_at, decided_at
                      FROM operational_decision
                     WHERE decision_uuid = :uuid
                """)
                .param("uuid", uuid)
                .query(OperationalDecisionService::mapView)
                .optional()
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "No operational decision with that reference."));
    }

    private static OperationalDecisionDtos.DecisionView mapView(
            java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new OperationalDecisionDtos.DecisionView(
                rs.getString("decision_uuid"),
                rs.getString("action_uuid"),
                OperationalDecisionDtos.DecisionType.valueOf(
                        rs.getString("decision_type")),
                rs.getString("outcome"),
                rs.getObject("actor_user_id", Long.class),
                rs.getString("actor_role"),
                rs.getString("pnr_display"),
                rs.getString("reason"),
                rs.getString("source_policy_code"),
                rs.getString("source_policy_section"),
                rs.getString("trace_id"),
                rs.getString("session_uuid"),
                Json.readMap(rs.getString("detail_json")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("decided_at") == null
                        ? null : rs.getTimestamp("decided_at").toInstant());
    }

    private static OperationalDecisionDtos.DecisionType map(
            ActionDtos.ActionType type) {
        return switch (type) {
            case CANCEL_BOOKING, REFUND_REQUEST ->
                    OperationalDecisionDtos.DecisionType.REFUND_APPROVAL;
            case RESCHEDULE_BOOKING, SEAT_CHANGE ->
                    OperationalDecisionDtos.DecisionType.UPGRADE_AUTHORIZATION;
            case CHECK_IN ->
                    OperationalDecisionDtos.DecisionType.BOARDING_OVERRIDE;
        };
    }

    private static String normalizeOutcome(String value) {
        String normalized = value == null
                ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!Set.of(
                "PROPOSED", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED",
                "FAILED").contains(normalized)) {
            throw new ApiExceptions.BadRequest("Unsupported decision outcome.");
        }
        return normalized;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static Map<String, Object> redactDetail(
            Map<String, Object> detail,
            String pnr) {
        if (detail == null || detail.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        for (var entry : detail.entrySet()) {
            if ("passenger".equalsIgnoreCase(entry.getKey())) {
                safe.put(entry.getKey(), "[PERSON-REDACTED]");
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String text && pnr != null && !pnr.isBlank()) {
                safe.put(entry.getKey(), text.replace(
                        pnr, "[AIR-PNR-REDACTED]"));
            } else {
                safe.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(safe);
    }

    static String hashPnr(String pnr) {
        if (pnr == null || pnr.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(pnr.trim().toUpperCase(Locale.ROOT)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
