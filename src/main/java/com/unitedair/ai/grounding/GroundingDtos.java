package com.unitedair.ai.grounding;

import java.time.Instant;
import java.util.List;

import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.commerce.CommerceDtos;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.tools.OperationalFailure;
/** Citation and answer shapes shared by the grounding and orchestration layers. */
public final class GroundingDtos {

    private GroundingDtos() { }

    /**
     * A citation as SRS 4.1.2 defines it: the source document, the section and page used,
     * and - for tool-grounded claims - the tool name and the time the live data was read.
     *
     * @param handle the marker the model was told to cite, {@code E1}, {@code E2}, ...
     */
    public record Citation(
            String handle,
            String documentCode,
            String documentTitle,
            String section,
            Integer page,
            String category,
            Double relevance,
            String excerpt,
            String toolName,
            String toolOperation,
            String provider,
            Boolean providerLive,
            Instant retrievedAt) {

        public Citation(
                String handle,
                String documentCode,
                String documentTitle,
                String section,
                Integer page,
                String category,
                Double relevance,
                String excerpt,
                String toolName,
                Instant retrievedAt) {
            this(
                    handle, documentCode, documentTitle, section, page,
                    category, relevance, excerpt, toolName, null, null, null, retrievedAt);
        }

        public static Citation fromKb(String handle, String documentCode, String documentTitle,
                                      String section, Integer page, String category,
                                      Double relevance, String excerpt) {
            return new Citation(handle, documentCode, documentTitle, section, page,
                    category, relevance, excerpt, null, null, null, null, null);
        }

        public static Citation fromTool(String handle, String toolName, String summary, Instant at) {
            return fromTool(handle, toolName, "READ", "SIMULATOR", false, summary, at);
        }

        public static Citation fromTool(
                String handle,
                String toolName,
                String operation,
                String provider,
                String summary,
                Instant at) {
            return fromTool(
                    handle,
                    toolName,
                    operation,
                    provider,
                    provider != null && (provider.endsWith("_SANDBOX")
                            || provider.endsWith("_LIVE")),
                    summary,
                    at);
        }

        public static Citation fromTool(
                String handle,
                String toolName,
                String operation,
                String provider,
                boolean providerLive,
                String summary,
                Instant at) {
            return new Citation(handle, toolName, "Live tool result", null, null,
                    "tool-result", null, summary, toolName, operation, provider,
                    providerLive, at);
        }

        public boolean isToolCitation() {
            return toolName != null;
        }
    }

    /** Outcome classification recorded on {@code answer_record.status}. */
    public enum AnswerStatus {
        CONVERSATIONAL,
        CLARIFICATION,
        OUT_OF_SCOPE,
        GROUNDED,
        TOOL_GROUNDED,
        EMPTY_CONTEXT,
        ESCALATED,
        ERROR
    }

    /** The finished answer handed back to the transport layer. */
    public record GroundedAnswer(
            String answer,
            AnswerStatus status,
            boolean escalated,
            List<Citation> citations,
            List<String> followups,
            List<String> toolsUsed,
            Double confidence,
            Double citationCoverage,
            int repairAttempts,
            String intent,
            String lane,
            String traceId,
            String sessionUuid,
            ChatDtos.GenerationSource generationSource,
            String degradedReason,
            OperationalFailure operationalFailure,
            ActionDtos.ActionView proposedAction,
            CommerceDtos.CommercePayload commerce) {

        public GroundedAnswer(
                String answer,
                AnswerStatus status,
                boolean escalated,
                List<Citation> citations,
                List<String> followups,
                List<String> toolsUsed,
                Double confidence,
                Double citationCoverage,
                int repairAttempts,
                String intent,
                String lane,
                String traceId,
                String sessionUuid,
                ChatDtos.GenerationSource generationSource,
                String degradedReason,
                OperationalFailure operationalFailure,
                ActionDtos.ActionView proposedAction) {
            this(
                    answer, status, escalated, citations, followups, toolsUsed,
                    confidence, citationCoverage, repairAttempts, intent, lane,
                    traceId, sessionUuid, generationSource, degradedReason,
                    operationalFailure, proposedAction, null);
        }

        public GroundedAnswer(
                String answer,
                AnswerStatus status,
                boolean escalated,
                List<Citation> citations,
                List<String> followups,
                List<String> toolsUsed,
                Double confidence,
                Double citationCoverage,
                int repairAttempts,
                String intent,
                String lane,
                String traceId,
                String sessionUuid,
                ChatDtos.GenerationSource generationSource,
                String degradedReason,
                ActionDtos.ActionView proposedAction) {
            this(
                    answer, status, escalated, citations, followups, toolsUsed,
                    confidence, citationCoverage, repairAttempts, intent, lane,
                    traceId, sessionUuid, generationSource, degradedReason, null,
                    proposedAction, null);
        }

        public GroundedAnswer(
                String answer,
                AnswerStatus status,
                boolean escalated,
                List<Citation> citations,
                List<String> followups,
                List<String> toolsUsed,
                Double confidence,
                Double citationCoverage,
                int repairAttempts,
                String intent,
                String lane,
                String traceId,
                String sessionUuid,
                String degradedReason,
                ActionDtos.ActionView proposedAction) {
            this(
                    answer, status, escalated, citations, followups, toolsUsed,
                    confidence, citationCoverage, repairAttempts, intent, lane,
                    traceId, sessionUuid,
                    ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                    degradedReason, null, proposedAction, null);
        }
    }
}
