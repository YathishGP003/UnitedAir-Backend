package com.unitedair.ai.tools;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.Json;
import com.unitedair.ai.shared.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Wraps every tool call so it is logged against the session UUID, as SRS 4.1.4 requires.
 *
 * <p>The row is written <b>before</b> the call is dispatched and updated when it settles. A
 * call that hangs or crashes the process therefore still leaves a {@code PENDING} row, which
 * is the difference between "we know a tool was attempted" and "the trail simply stops".
 */
@Component
public class ToolInvocationLogger {

    private static final Logger log = LoggerFactory.getLogger(ToolInvocationLogger.class);

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;

    @Autowired
    public ToolInvocationLogger(JdbcClient jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    ToolInvocationLogger(JdbcClient jdbc) {
        this(jdbc, new CurrentUser());
    }

    /**
     * Executes {@code action}, recording the attempt and its outcome.
     *
     * @param request already-redacted arguments, safe to persist
     * @param dispatchPattern which agentic pattern dispatched this call, for the trace view
     */
    public ToolDtos.ToolOutcome invoke(String toolName,
                                       String actorRole,
                                       String dispatchPattern,
                                       Map<String, Object> request,
                                       Supplier<ToolResult> action) {
        Object operation = request == null ? null : request.get("operation");
        return invoke(
                toolName,
                operation == null ? "UNSPECIFIED" : operation.toString(),
                actorRole,
                dispatchPattern,
                request,
                action);
    }

    public ToolDtos.ToolOutcome invoke(String toolName,
                                       String operation,
                                       String actorRole,
                                       String dispatchPattern,
                                       Map<String, Object> request,
                                       Supplier<ToolResult> action) {

        String sessionUuid = resolveSession(actorRole);
        String traceId = TraceContext.traceId();
        Instant invokedAt = Instant.now();
        long started = System.nanoTime();
        String routeStage = "MODEL".equalsIgnoreCase(dispatchPattern)
                ? "MODEL_PROPOSED" : "DETERMINISTIC_ROUTED";
        recordLifecycle(
                sessionUuid, traceId, toolName, operation, actorRole,
                routeStage, request, null);
        recordLifecycle(
                sessionUuid, traceId, toolName, operation, actorRole,
                "VALIDATED", request, null);

        Long invocationId = sessionUuid == null ? null
                : begin(sessionUuid, traceId, toolName, actorRole, dispatchPattern, request);

        try {
            ToolResult result = action.get();
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            settle(invocationId, "SUCCESS", result.data(), null, durationMs);
            recordLifecycle(
                    sessionUuid, traceId, toolName, operation, actorRole,
                    "EXECUTED", request, result.summary());
            return ToolDtos.ToolOutcome.ok(toolName, result.data(), result.summary(),
                    durationMs, invokedAt, request);

        } catch (OperationalFailureException e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            OperationalFailure failure = e.failure();
            settle(invocationId, "FAILED", failure, failure.userMessage(), durationMs);
            recordLifecycle(
                    sessionUuid, traceId, toolName, operation, actorRole,
                    "REJECTED", request, failure.userMessage());
            log.warn("Tool {} failed after {} ms [{}]: {}",
                    toolName, durationMs, failure.kind(), failure.userMessage());
            return ToolDtos.ToolOutcome.failed(
                    toolName, failure, durationMs, invokedAt, request);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            OperationalFailure failure = typedFailure(
                    toolName, operation, e, message, request);
            settle(invocationId, "FAILED", failure, message, durationMs);
            recordLifecycle(
                    sessionUuid, traceId, toolName, operation, actorRole,
                    "REJECTED", request, message);
            log.warn("Tool {} failed after {} ms [{}]: {}",
                    toolName, durationMs, failure.kind(), message);
            return ToolDtos.ToolOutcome.failed(
                    toolName, failure, durationMs, invokedAt, request);
        }
    }

    private static OperationalFailure typedFailure(
            String toolName,
            String operation,
            Exception exception,
            String message,
            Map<String, Object> request) {
        OperationalFailureKind kind;
        if (exception instanceof ApiExceptions.NotFound) {
            kind = OperationalFailureKind.NOT_FOUND;
        } else if (exception instanceof ApiExceptions.BadRequest
                || exception instanceof ApiExceptions.Conflict
                || exception instanceof IllegalArgumentException) {
            kind = OperationalFailureKind.VALIDATION;
        } else if (exception instanceof ApiExceptions.Unauthorized
                || exception instanceof ApiExceptions.Forbidden
                || exception instanceof SecurityException) {
            kind = OperationalFailureKind.UNAUTHORIZED;
        } else {
            kind = OperationalFailureKind.UNAVAILABLE;
        }
        Map<String, Object> safeDetails = request == null
                ? Map.of()
                : request.entrySet().stream()
                        .filter(entry -> !entry.getKey().toLowerCase().contains("pnr"))
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue() == null ? "" : entry.getValue()));
        return new OperationalFailure(
                toolName, operation, kind, message, safeDetails);
    }

    public void recordModelProposal(String toolFamily,
                                    String operation,
                                    String actorRole,
                                    Map<String, Object> arguments) {
        recordLifecycle(
                resolveSession(actorRole),
                TraceContext.traceId(),
                toolFamily,
                operation,
                actorRole,
                "MODEL_PROPOSED",
                arguments,
                null);
    }

    public void recordRejection(String toolFamily,
                                String operation,
                                String actorRole,
                                Map<String, Object> arguments,
                                String detail) {
        recordLifecycle(
                resolveSession(actorRole),
                TraceContext.traceId(),
                toolFamily,
                operation,
                actorRole,
                "REJECTED",
                arguments,
                detail);
    }

    public void recordValidation(String toolFamily,
                                 String operation,
                                 String actorRole,
                                 Map<String, Object> arguments) {
        recordLifecycle(
                resolveSession(actorRole),
                TraceContext.traceId(),
                toolFamily,
                operation,
                actorRole,
                "VALIDATED",
                arguments,
                null);
    }

    /**
     * Structured UI tools can be invoked outside a chat conversation. They still need a
     * durable session spine for SRS 4.1.4, so each authenticated user gets one hidden,
     * deterministic audit-only session. Chat-driven calls retain their real conversation
     * session from {@link TraceContext}.
     */
    private String resolveSession(String actorRole) {
        String active = TraceContext.sessionUuid();
        if (active != null && !active.isBlank()) {
            return active;
        }

        CurrentUser.Authenticated user = currentUser.find().orElse(null);
        if (user == null || user.id() == null) {
            log.debug("Tool call has no authenticated user or conversation session; "
                    + "skipping persistent invocation trace.");
            return null;
        }

        String sessionUuid = UUID.nameUUIDFromBytes(
                ("unitedair:direct-tools:" + user.id())
                        .getBytes(StandardCharsets.UTF_8)).toString();
        try {
            jdbc.sql("""
                        INSERT IGNORE INTO chat_session
                            (session_uuid, user_id, actor_role, title, expired)
                        VALUES (:uuid, :userId, :role, 'Structured tool activity', TRUE)
                    """)
                    .param("uuid", sessionUuid)
                    .param("userId", user.id())
                    .param("role", user.role().name())
                    .update();
            return sessionUuid;
        } catch (Exception e) {
            log.warn("Could not establish direct-tool audit session for {}: {}",
                    actorRole, e.toString());
            return null;
        }
    }

    private Long begin(String sessionUuid, String traceId, String toolName,
                       String actorRole, String dispatchPattern, Map<String, Object> request) {
        try {
            var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.sql("""
                        INSERT INTO tool_invocation
                            (session_uuid, trace_id, tool_name, actor_role, dispatch_pattern,
                             request_json, status)
                        VALUES (:s, :t, :tool, :role, :pattern, :req, 'PENDING')
                    """)
                    .param("s", sessionUuid)
                    .param("t", traceId)
                    .param("tool", toolName)
                    .param("role", actorRole)
                    .param("pattern", dispatchPattern)
                    .param("req", Json.write(request))
                    .update(keys);
            Number key = keys.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception e) {
            // Losing the trail must not stop the passenger being served.
            log.warn("Could not open tool_invocation row for {}: {}", toolName, e.toString());
            return null;
        }
    }

    private void recordLifecycle(String sessionUuid,
                                 String traceId,
                                 String toolFamily,
                                 String operation,
                                 String actorRole,
                                 String stage,
                                 Map<String, Object> arguments,
                                 String detail) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return;
        }
        try {
            jdbc.sql("""
                        INSERT INTO tool_call_lifecycle
                            (session_uuid, trace_id, tool_family, operation, actor_role,
                             lifecycle_stage, arguments_json, detail)
                        VALUES
                            (:session, :trace, :family, :operation, :role,
                             :stage, :arguments, :detail)
                    """)
                    .param("session", sessionUuid)
                    .param("trace", traceId)
                    .param("family", toolFamily)
                    .param("operation", operation == null ? "UNSPECIFIED" : operation)
                    .param("role", actorRole)
                    .param("stage", stage)
                    .param("arguments", arguments == null ? null : Json.write(arguments))
                    .param("detail", detail == null ? null
                            : detail.substring(0, Math.min(1000, detail.length())))
                    .update();
        } catch (Exception e) {
            log.warn("Could not persist {} lifecycle for {}.{}: {}",
                    stage, toolFamily, operation, e.toString());
        }
    }

    private void settle(Long invocationId, String status, Object result,
                        String errorMessage, long durationMs) {
        if (invocationId == null) {
            return;
        }
        try {
            jdbc.sql("""
                        UPDATE tool_invocation
                           SET status = :status,
                               result_json = :result,
                               error_message = :error,
                               duration_ms = :duration,
                               completed_at = CURRENT_TIMESTAMP
                         WHERE id = :id
                    """)
                    .param("status", status)
                    .param("result", result == null ? null : Json.write(result))
                    .param("error", errorMessage == null ? null
                            : errorMessage.substring(0, Math.min(1000, errorMessage.length())))
                    .param("duration", durationMs)
                    .param("id", invocationId)
                    .update();
        } catch (Exception e) {
            log.warn("Could not settle tool_invocation {}: {}", invocationId, e.toString());
        }
    }

    /** What a tool hands back: the payload to persist plus a one-line human summary. */
    public record ToolResult(Object data, String summary) { }
}
