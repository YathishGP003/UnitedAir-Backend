package com.unitedair.ai.orchestration;

import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.actions.ActionService;
import com.unitedair.ai.audit.AuditDtos;
import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.commerce.BookingConversationCoordinator;
import com.unitedair.ai.commerce.CommerceDtos;
import com.unitedair.ai.conversation.ChatMemoryStore;
import com.unitedair.ai.conversation.SessionService;
import com.unitedair.ai.grounding.GroundingDtos;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.AiMode;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.privacy.PiiRedactor;
import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Persists and presents structured commerce/action turns outside HTTP transport code. */
@Service
public class StructuredTurnDeliveryService {

    private final SessionService sessions;
    private final ChatMemoryStore memory;
    private final PiiRedactor redactor;
    private final AuditService audit;
    private final ActionService actions;
    private final AiMode aiMode;

    public StructuredTurnDeliveryService(
            SessionService sessions,
            ChatMemoryStore memory,
            PiiRedactor redactor,
            AuditService audit,
            ActionService actions,
            AiMode aiMode) {
        this.sessions = sessions;
        this.memory = memory;
        this.redactor = redactor;
        this.audit = audit;
        this.actions = actions;
        this.aiMode = aiMode;
    }

    public GroundingDtos.GroundedAnswer deliverCommerce(
            BookingConversationCoordinator.CommerceTurn turn,
            String redactedQuery,
            String sessionUuid,
            Role actorRole,
            Long userId,
            String traceId,
            long started,
            Consumer<OrchestrationDtos.StreamEvent> progress) {
        List<GroundingDtos.Citation> citations = commerceCitations(turn);
        String handles = citations.stream()
                .map(citation -> "[" + citation.handle() + "]")
                .collect(Collectors.joining(" "));
        String baseAnswer = redactor.redact(turn.answer()).redacted();
        String answer = baseAnswer.replaceFirst("[.?!]\\s*$", "")
                + (handles.isBlank() ? "" : " " + handles)
                + ".";
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        Long answerId = audit.saveCommerceAnswer(
                sessionUuid,
                traceId,
                actorRole.name(),
                answer,
                turn.payload(),
                turn.toolsUsed(),
                citations,
                turn.followups(),
                aiMode.name(),
                durationMs);
        sessions.appendMessage(
                sessionUuid, "USER", redactedQuery, null, traceId);
        sessions.appendMessage(
                sessionUuid, "ASSISTANT", answer, answerId, traceId);
        memory.append(sessionUuid, "USER", redactedQuery, null);
        memory.append(sessionUuid, "ASSISTANT", answer, null);
        sessions.touch(sessionUuid);
        emit(progress, "answer", answer);

        return new GroundingDtos.GroundedAnswer(
                answer,
                GroundingDtos.AnswerStatus.TOOL_GROUNDED,
                false,
                citations,
                turn.followups(),
                turn.toolsUsed(),
                1.0,
                1.0,
                0,
                OrchestrationDtos.Intent.BOOK_FLIGHT.name(),
                "FAST",
                traceId,
                sessionUuid,
                ChatDtos.GenerationSource.STRUCTURED_TOOL,
                null,
                null,
                null,
                turn.payload());
    }

    public GroundingDtos.GroundedAnswer settlePendingAction(
            ValidatedRoute.ActionDecision decision,
            String sessionUuid,
            Role actorRole,
            Long userId,
            String redactedQuery,
            String traceId,
            long started,
            Consumer<OrchestrationDtos.StreamEvent> progress) {
        if (actorRole != Role.PASSENGER) {
            throw new ApiExceptions.Forbidden(
                    "Only a Passenger may settle a passenger booking action.");
        }
        if (decision != ValidatedRoute.ActionDecision.CONFIRM
                && decision != ValidatedRoute.ActionDecision.REJECT) {
            throw new ApiExceptions.BadRequest(
                    "A pending action requires CONFIRM or REJECT.");
        }
        List<ActionDtos.ActionView> pending = actions.pendingFor(sessionUuid);
        if (pending.size() != 1) {
            throw new ApiExceptions.Conflict(
                    "There is no single pending action to settle in this session.");
        }
        ActionDtos.ActionView settled =
                decision == ValidatedRoute.ActionDecision.CONFIRM
                        ? actions.confirm(pending.getFirst().actionUuid())
                        : actions.cancel(pending.getFirst().actionUuid());
        String answer = decision == ValidatedRoute.ActionDecision.CONFIRM
                ? actionResultMessage(settled)
                : "Your booking has not been changed. "
                    + "The pending request was dismissed.";
        answer = redactor.redact(answer).redacted();
        List<GroundingDtos.Citation> citations = actionCitations(settled);
        List<String> followups = List.of(
                "Would you like to track the refund status?",
                "Would you like the cancellation confirmation document?");
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        Long answerId = audit.saveAnswerRecord(
                new AuditDtos.AnswerRecordRow(
                        sessionUuid,
                        traceId,
                        null,
                        GroundingDtos.AnswerStatus.TOOL_GROUNDED.name(),
                        OrchestrationDtos.Intent.TOOL_CALL.name(),
                        actorRole.name(),
                        answer,
                        citations,
                        followups,
                        List.of("BookingManagementTool"),
                        1.0,
                        1.0,
                        0,
                        false,
                        "semantic-action-decision",
                        aiMode.name(),
                        null,
                        0,
                        0,
                        durationMs));
        sessions.appendMessage(
                sessionUuid, "USER", redactedQuery, null, traceId);
        sessions.appendMessage(
                sessionUuid, "ASSISTANT", answer, answerId, traceId);
        memory.append(sessionUuid, "USER", redactedQuery, null);
        memory.append(sessionUuid, "ASSISTANT", answer, null);
        sessions.touch(sessionUuid);
        emit(progress, "answer", answer);

        return new GroundingDtos.GroundedAnswer(
                answer,
                GroundingDtos.AnswerStatus.TOOL_GROUNDED,
                false,
                citations,
                followups,
                List.of("BookingManagementTool"),
                1.0,
                1.0,
                0,
                OrchestrationDtos.Intent.TOOL_CALL.name(),
                "FAST",
                traceId,
                sessionUuid,
                ChatDtos.GenerationSource.STRUCTURED_TOOL,
                null,
                null,
                settled,
                null);
    }

    private static List<GroundingDtos.Citation> commerceCitations(
            BookingConversationCoordinator.CommerceTurn turn) {
        List<GroundingDtos.Citation> citations = new ArrayList<>();
        int evidence = 0;
        for (String code : turn.policyDocumentCodes()) {
            citations.add(policyCitation("E" + (++evidence), code));
        }
        int tools = 0;
        for (String tool : turn.toolsUsed()) {
            citations.add(GroundingDtos.Citation.fromTool(
                    "T" + (++tools),
                    tool,
                    operation(turn.payload()),
                    "UNITEDAIR_SIMULATOR",
                    "Verified commerce workflow data used.",
                    Instant.now()));
        }
        return List.copyOf(citations);
    }

    private static GroundingDtos.Citation policyCitation(
            String handle,
            String code) {
        return switch (code) {
            case "KB-AIR-002" -> GroundingDtos.Citation.fromKb(
                    handle, code, "Check-In, Boarding and Travel Documents",
                    "Check-in and boarding requirements", 1,
                    "policy-manual", 1.0,
                    "Governing check-in workflow policy.");
            case "KB-AIR-003" -> GroundingDtos.Citation.fromKb(
                    handle, code, "Baggage Policy and Handling",
                    "Baggage allowances", 1,
                    "policy-manual", 1.0,
                    "Governing baggage allowance policy.");
            case "KB-AIR-004" -> GroundingDtos.Citation.fromKb(
                    handle, code, "Cancellation, Refund and Rescheduling",
                    "Cancellation fee matrix and refund process", 1,
                    "fare-rule", 1.0,
                    "Governing cancellation and refund policy.");
            case "KB-AIR-005" -> GroundingDtos.Citation.fromKb(
                    handle, code, "Seat Selection, Fare Classes and Pricing",
                    "Seat selection and fare conditions", 1,
                    "fare-rule", 1.0,
                    "Governing seat and fare policy.");
            default -> GroundingDtos.Citation.fromKb(
                    handle, "KB-AIR-001", "Flight Booking and Search",
                    "Flight search and booking workflow", 1,
                    "policy-manual", 1.0,
                    "Governing flight search and booking policy.");
        };
    }

    private static String operation(CommerceDtos.CommercePayload payload) {
        return payload == null ? "BOOKING_WORKFLOW" : payload.type().name();
    }

    private static List<GroundingDtos.Citation> actionCitations(
            ActionDtos.ActionView action) {
        List<Map<String, String>> source = action.citations() == null
                ? List.of() : action.citations();
        ArrayList<GroundingDtos.Citation> citations = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            Map<String, String> citation = source.get(index);
            citations.add(GroundingDtos.Citation.fromKb(
                    "E" + (index + 1),
                    citation.getOrDefault("documentCode", "KB-AIR-004"),
                    "Cancellation, Refund and Rescheduling",
                    citation.getOrDefault(
                            "section",
                            "Cancellation fee matrix and refund process"),
                    1,
                    "fare-rule",
                    1.0,
                    "Governing cancellation and refund policy."));
        }
        return List.copyOf(citations);
    }

    private static String actionResultMessage(ActionDtos.ActionView action) {
        Object message = action.result() == null
                ? null : action.result().get("message");
        return message == null || String.valueOf(message).isBlank()
                ? "Your booking has been cancelled. "
                    + "Refund tracking is now available."
                : String.valueOf(message);
    }

    private static void emit(
            Consumer<OrchestrationDtos.StreamEvent> progress,
            String type,
            String text) {
        if (progress != null) {
            progress.accept(new OrchestrationDtos.StreamEvent(
                    type, java.util.Map.of("text", text)));
        }
    }
}
