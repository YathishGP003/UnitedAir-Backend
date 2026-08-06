package com.unitedair.ai.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.shared.TraceContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SRS 2.5 EscalationTool - routes what the assistant must not decide to a human.
 *
 * <p>Serves FR-015 and the escalation requirement of SRS 4.2.2.
 *
 * <p>Two queues exist, and which one a case lands in is a compliance decision rather than a
 * stylistic one. Regulatory matters - denied boarding compensation, DGCA consumer
 * protection, a refund the airline has refused - are routed to the DGCA Grievance Officer.
 * Everything else goes to the UnitedAir Customer Support Manager. The mapping lives in
 * {@link #targetQueueFor} so it can be read, tested and audited in one place.
 *
 * <p>Escalation is triggered by the pipeline (confidence below 0.40, empty context, or a
 * failed validation) as well as by explicit user request. It is never left to the model to
 * decide whether escalation is warranted.
 */
@Component
public class EscalationTool {

    public static final String NAME = "EscalationTool";

    public static final String QUEUE_SUPPORT = "CUSTOMER_SUPPORT_MANAGER";
    public static final String QUEUE_DGCA = "DGCA_GRIEVANCE_OFFICER";

    /** Reasons that carry a regulatory dimension and belong with the grievance officer. */
    private static final List<String> DGCA_REASONS =
            List.of("REFUND_DENIAL", "FRAUD_ALLEGATION", "DENIED_BOARDING", "REGULATORY");

    private final JdbcClient jdbc;
    private final ToolInvocationLogger logger;
    private final CurrentUser currentUser;

    public EscalationTool(JdbcClient jdbc, ToolInvocationLogger logger, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.logger = logger;
        this.currentUser = currentUser;
    }

    @Tool(name = NAME, description = """
            Use for unresolved disputes, refund denial, fraud, complaints, safety concerns,
            missing mandatory approved evidence, failed grounded validation, or confidence
            below 0.38. Never fabricate an answer instead of escalating.
            """)
    public ToolDtos.GovernedToolResult invoke(ToolDtos.EscalationToolRequest request) {
        if (request == null
                || request.operation() != ToolDtos.EscalationOperation.CREATE_ESCALATION) {
            throw new com.unitedair.ai.shared.ApiExceptions.BadRequest(
                    "CREATE_ESCALATION is the only escalation operation.");
        }
        Map<String, Object> arguments = request.arguments();
        String summary = string(arguments, "summary");
        if (summary == null) {
            throw new com.unitedair.ai.shared.ApiExceptions.BadRequest(
                    "A redacted escalation summary is required.");
        }
        String role = currentUser.require().role().name();
        Outcome outcome = raise(
                new ToolDtos.EscalationRequest(
                        string(arguments, "reason"),
                        summary,
                        string(arguments, "pnr"),
                        decimal(arguments, "confidence"),
                        string(arguments, "priority")),
                role);
        return ToolDtos.GovernedToolResult.from(
                NAME, request.operation().name(), outcome.envelope());
    }

    /**
     * Direct entry point used by the pipeline.
     *
     * @param request {@code summary} must already be redacted; it is persisted verbatim
     */
    public Outcome raise(ToolDtos.EscalationRequest request, String actorRole) {
        String reason = normaliseReason(request.reason());
        String queue = targetQueueFor(reason);
        String priority = request.priority() != null
                ? request.priority()
                : derivePriority(reason, request.confidence());

        Map<String, Object> logged = new LinkedHashMap<>();
        logged.put("operation", "CREATE_ESCALATION");
        logged.put("reason", reason);
        logged.put("targetQueue", queue);
        logged.put("priority", priority);
        logged.put("confidence", request.confidence());

        ToolDtos.ToolOutcome outcome = logger.invoke(NAME, actorRole, "ROUTING", logged, () -> {
            String caseUuid = UUID.randomUUID().toString();
            boolean systemRaised = !"USER_REQUESTED".equals(reason);

            jdbc.sql("""
                        INSERT INTO escalation_case
                            (case_uuid, session_uuid, trace_id, user_id, reason, raised_by_system,
                             target_queue, priority, summary_redacted, confidence, subject_pnr, status)
                        VALUES
                            (:caseUuid, :sessionUuid, :traceId, :userId, :reason, :systemRaised,
                             :queue, :priority, :summary, :confidence, :pnr, 'OPEN')
                    """)
                    .param("caseUuid", caseUuid)
                    .param("sessionUuid", TraceContext.sessionUuid())
                    .param("traceId", TraceContext.traceId())
                    .param("userId", currentUser.find().map(CurrentUser.Authenticated::id).orElse(null),
                            java.sql.Types.BIGINT)
                    .param("reason", reason)
                    .param("systemRaised", systemRaised)
                    .param("queue", queue)
                    .param("priority", priority)
                    .param("summary", request.summary())
                    .param("confidence", request.confidence())
                    .param("pnr", request.pnr())
                    .update();

            ToolDtos.EscalationResult result = new ToolDtos.EscalationResult(
                    caseUuid, reason, queue, priority, "OPEN",
                    messageFor(queue, caseUuid), Instant.now());

            return new ToolInvocationLogger.ToolResult(result,
                    "Escalated to " + humanQueueName(queue) + " as case " + shortRef(caseUuid) + ".");
        });

        ToolDtos.EscalationResult data =
                outcome.data() instanceof ToolDtos.EscalationResult result ? result : null;
        return new Outcome(outcome, data);
    }

    // ------------------------------------------------------------------ routing ---

    static String targetQueueFor(String reason) {
        return DGCA_REASONS.contains(reason) ? QUEUE_DGCA : QUEUE_SUPPORT;
    }

    private static String derivePriority(String reason, Double confidence) {
        if ("FRAUD_ALLEGATION".equals(reason)) {
            return "URGENT";
        }
        if ("REFUND_DENIAL".equals(reason) || "BOOKING_DISPUTE".equals(reason)) {
            return "HIGH";
        }
        if (confidence != null && confidence < 0.2) {
            return "HIGH";
        }
        return "NORMAL";
    }

    private static String normaliseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "LOW_CONFIDENCE";
        }
        return reason.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static String humanQueueName(String queue) {
        return QUEUE_DGCA.equals(queue)
                ? "the DGCA Grievance Officer"
                : "your UnitedAir Customer Support Manager";
    }

    private static String messageFor(String queue, String caseUuid) {
        return "I have referred this to " + humanQueueName(queue)
                + ". Your reference is " + shortRef(caseUuid)
                + ". They will follow up on this case directly.";
    }

    /** Short, quotable reference for a human handler; the full UUID stays internal. */
    static String shortRef(String caseUuid) {
        return "ESC-" + caseUuid.substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null || value.toString().isBlank()
                ? null : value.toString().trim();
    }

    private static Double decimal(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return value instanceof Number number
                    ? number.doubleValue() : Double.parseDouble(value.toString());
        } catch (NumberFormatException invalid) {
            throw new com.unitedair.ai.shared.ApiExceptions.BadRequest(
                    key + " must be a decimal number.");
        }
    }

    public record Outcome(ToolDtos.ToolOutcome envelope, ToolDtos.EscalationResult data) { }
}
