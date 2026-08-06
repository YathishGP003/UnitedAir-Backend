package com.unitedair.ai.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.unitedair.ai.commerce.CommerceDtos;
import com.unitedair.ai.grounding.GroundingDtos;

/** Write-side rows and read-side projections for the audit trail. */
public final class AuditDtos {

    private AuditDtos() { }

    // ------------------------------------------------------------ write side ---

    public record RetrievalRunRecord(
            String sessionUuid,
            String traceId,
            String queryRedacted,
            String queryRewritten,
            String lane,
            int attempt,
            Map<String, Object> filters,
            int vectorHits,
            int lexicalHits,
            int fusedHits,
            int keptHits,
            Double topSimilarity,
            Double confidence,
            Long durationMs) { }

    public record EvidenceRow(
            Long chunkId,
            String documentCode,
            String documentTitle,
            String section,
            Integer page,
            String handle,
            Double vectorScore,
            Double lexicalScore,
            Double fusedScore,
            int rank,
            boolean usedInAnswer,
            String excerpt) { }

    public record AnswerRecordRow(
            String sessionUuid,
            String traceId,
            Long retrievalRunId,
            String status,
            String intent,
            String actorRole,
            String answerRedacted,
            Object citations,
            List<String> followups,
            List<String> toolsUsed,
            Double confidence,
            Double citationCoverage,
            int repairAttempts,
            boolean escalated,
            String modelName,
            String aiMode,
            String degradedReason,
            Integer promptTokens,
            Integer completionTokens,
            Long durationMs) { }

    public record ValidationRow(
            String sessionUuid,
            String traceId,
            Long answerRecordId,
            int attemptNo,
            String verdict,
            String failedGates,
            Double citationCoverage,
            Double confidence,
            Map<String, Object> detail,
            boolean triggeredRepair,
            Long escalationCaseId) { }

    // ------------------------------------------------------------- read side ---

    /**
     * Everything recorded for one session, as returned by {@code GET /audit/{sessionId}}.
     * SRS 5 requires this to expose the query, the retrieved chunks, the tool calls, the
     * citations and the escalation flag.
     */
    public record SessionTrail(
            String sessionUuid,
            String actorRole,
            Instant createdAt,
            Instant lastActivityAt,
            boolean expired,
            List<TurnTrail> turns,
            List<EventView> events,
            List<EscalationView> escalations) { }

    /** One question-and-answer cycle, keyed by trace id. */
    public record TurnTrail(
            String traceId,
            Instant at,
            String queryRedacted,
            String queryRewritten,
            String intent,
            String lane,
            int attempt,
            String status,
            boolean escalated,
            Double confidence,
            Double citationCoverage,
            int repairAttempts,
            String answerRedacted,
            List<GroundingDtos.Citation> citations,
            String modelName,
            String aiMode,
            String degradedReason,
            CommerceDtos.CommercePayload commerce,
            Long durationMs,
            List<EvidenceView> evidence,
            List<ToolCallView> toolCalls,
            List<ValidationView> validations,
            List<String> followups) { }

    public record EvidenceView(
            String handle,
            String documentCode,
            String documentTitle,
            String section,
            Integer page,
            Double vectorScore,
            Double lexicalScore,
            Double fusedScore,
            int rank,
            boolean usedInAnswer,
            String excerpt) { }

    public record ToolCallView(
            String toolName,
            String status,
            String dispatchPattern,
            int attemptNo,
            Long durationMs,
            Instant invokedAt,
            String errorMessage,
            Map<String, Object> request,
            Map<String, Object> result) { }

    public record ValidationView(
            int attemptNo,
            String verdict,
            String failedGates,
            Double citationCoverage,
            Double confidence,
            boolean triggeredRepair) { }

    public record EventView(
            String eventType,
            String actorRole,
            Instant at,
            Map<String, Object> payload) { }

    public record EscalationView(
            String caseUuid,
            String reason,
            String targetQueue,
            String priority,
            String status,
            boolean raisedBySystem,
            String failedGates,
            Double confidence,
            String summaryRedacted,
            Instant createdAt) { }
}
