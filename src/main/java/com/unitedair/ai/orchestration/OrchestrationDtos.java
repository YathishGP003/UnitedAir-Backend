package com.unitedair.ai.orchestration;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.commerce.CommerceDtos;
/** Types used across the orchestration layer. */
public final class OrchestrationDtos {

    private OrchestrationDtos() { }

    /**
     * Where a turn is routed. The hosted semantic planner proposes normal routes inside
     * this allowlist; deterministic code validates tools, parameters, authorization,
     * confirmation requirements and mandatory escalation boundaries.
     */
    public enum Intent {
        /** Friendly greeting, thanks, farewell or assistant capability question. */
        SMALL_TALK,
        /** Passenger is actively creating a new flight booking. */
        BOOK_FLIGHT,
        /** Answer purely from Knowledge Base policy. */
        KB_LOOKUP,
        /** Answer from live tool data alone (flight status, seat map). */
        TOOL_CALL,
        /** Live data plus the policy that governs it - the common airline case. */
        TOOL_PLUS_KB,
        /** A supported request that is missing required tool parameters. */
        CLARIFICATION,
        /** A request outside the UnitedAir travel-assistant scope. */
        OUT_OF_SCOPE,
        /** Hand to a human without attempting an answer. */
        ESCALATION
    }

    /** Which tool the orchestrator will drive, if any. */
    public enum ToolTarget {
        FLIGHT_SEARCH,
        BOOKING_CREATE,
        BOOKING_LOOKUP,
        REFUND_QUOTE,
        REFUND_CASES,
        REFUND_STATUS,
        ESCALATION_QUEUE,
        FLIGHT_STATUS,
        CHECK_IN,
        SEAT_MAP,
        MEAL_AVAILABILITY,
        EXCESS_BAGGAGE_QUOTE,
        OPERATIONAL_DECISIONS,
        OPERATIONAL_DATA_QUERY,
        DISRUPTION_RECOVERY,
        NONE
    }

    /**
     * The classifier's decision plus everything it managed to extract from the question.
     *
     * @param confidence how sure the classifier is, which is separate from retrieval
     *                   confidence and is only used to decide whether to consult the model
     */
    public record Classification(
            Intent intent,
            ToolTarget tool,
            double confidence,
            String rationale,
            String origin,
            String destination,
            LocalDate travelDate,
            String cabin,
            String pnr,
            String flightNo,
            String escalationReason,
            List<String> categoryHints,
            List<String> missingParameters,
            Set<String> documentCodeHints,
            String mealCode,
            Integer excessBaggageKg,
            String routeType) {

        /** Compatibility constructor for callers that do not extract service-specific slots. */
        public Classification(
                Intent intent,
                ToolTarget tool,
                double confidence,
                String rationale,
                String origin,
                String destination,
                LocalDate travelDate,
                String cabin,
                String pnr,
                String flightNo,
                String escalationReason,
                List<String> categoryHints,
                List<String> missingParameters,
                Set<String> documentCodeHints) {
            this(intent, tool, confidence, rationale, origin, destination, travelDate,
                    cabin, pnr, flightNo, escalationReason, categoryHints,
                    missingParameters, documentCodeHints, null, null, null);
        }

        public boolean needsTool() {
            return tool != ToolTarget.NONE;
        }

        public boolean needsKb() {
            return intent == Intent.KB_LOOKUP || intent == Intent.TOOL_PLUS_KB;
        }
    }

    /** Inbound chat request. */
    public record ChatRequest(
            @NotBlank @Size(max = 4000) String message,
            String sessionId,
            Boolean deepSearch) { }

    /** Outbound answer, mirroring {@code GroundingDtos.GroundedAnswer} over the wire. */
    public record ChatResponse(
            String answer,
            String status,
            boolean escalated,
            List<CitationView> citations,
            List<String> followups,
            List<ToolCallView> toolCalls,
            Double confidence,
            Double citationCoverage,
            int repairAttempts,
            String intent,
            String lane,
            String sessionId,
            String traceId,
            String aiMode,
            String generationSource,
            String degradedReason,
            OperationalFailureView operationalFailure,
            ActionDtos.ActionView proposedAction,
            CommerceDtos.CommercePayload commerce,
            long durationMs) { }

    public record OperationalFailureView(
            String toolFamily,
            String operation,
            String code,
            String message,
            Map<String, Object> details) { }

    public record CitationView(
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
            String retrievedAt) { }

    public record ToolCallView(
            String toolName,
            boolean success,
            String summary,
            String error,
            long durationMs,
            Map<String, Object> request) { }

    /** One server-sent event on the streaming endpoint. */
    public record StreamEvent(
            String type,
            Object payload) { }
}
