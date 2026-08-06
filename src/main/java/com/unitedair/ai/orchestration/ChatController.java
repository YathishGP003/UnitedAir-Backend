package com.unitedair.ai.orchestration;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.unitedair.ai.conversation.ConversationDtos;
import com.unitedair.ai.conversation.SessionService;
import com.unitedair.ai.grounding.GroundingDtos;
import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.AiMode;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.shared.TraceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The chat endpoints of SRS 5.
 *
 * <h2>What "streaming" means here</h2>
 * The obvious implementation streams model tokens straight to the browser. We deliberately
 * do not, because an answer is only allowed to reach a user after the Evaluator-Optimizer
 * has passed it, and tokens already on screen cannot be recalled if validation then fails.
 *
 * <p>Instead the stream carries the pipeline itself: redaction, routing, tool calls and
 * retrieved evidence are pushed as they happen, so the interface fills with real
 * information within a few hundred milliseconds; then the validated answer is streamed in
 * chunks. The user sees continuous progress, and no ungrounded text is ever displayed.
 */
@RestController
@RequestMapping("/ai/airline/chat")
@Tag(name = "Chat", description = "Agentic RAG conversation endpoints")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** Characters per streamed chunk. Small enough to look live, large enough to be cheap. */
    private static final int STREAM_CHUNK = 18;
    /** Brief pacing gives the browser a paint opportunity between validated chunks. */
    private static final long STREAM_PACE_MS = 24;
    private static final long STREAM_TIMEOUT_MS = 180_000;

    private final AgenticOrchestrator orchestrator;
    private final SessionService sessions;
    private final CurrentUser currentUser;
    private final AiMode aiMode;

    /** Virtual threads: an SSE request spends nearly all of its life waiting. */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(AgenticOrchestrator orchestrator,
                          SessionService sessions,
                          CurrentUser currentUser,
                          AiMode aiMode) {
        this.orchestrator = orchestrator;
        this.sessions = sessions;
        this.currentUser = currentUser;
        this.aiMode = aiMode;
    }

    /** SRS 5: synchronous endpoint returning the complete grounded JSON response. */
    @PostMapping("/sync")
    @Operation(summary = "Ask a question and receive the complete grounded answer")
    public OrchestrationDtos.ChatResponse sync(@Valid @RequestBody OrchestrationDtos.ChatRequest request) {
        long started = System.nanoTime();
        CurrentUser.Authenticated user = currentUser.require();
        String sessionUuid = resolveSession(request, user);
        TraceContext.setSessionUuid(sessionUuid);
        Role actorRole = sessions.actorRole(sessionUuid);

        GroundingDtos.GroundedAnswer answer = orchestrator.answer(
                request.message(), sessionUuid, actorRole, user.id(),
                Boolean.TRUE.equals(request.deepSearch()), null);

        return toResponse(answer, (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * SRS 5: asynchronous SSE endpoint. {@code /stream} is accepted as an alias because the
     * reference frontend used that path.
     */
    @PostMapping(path = {"/async", "/stream"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Ask a question and receive pipeline progress plus the answer over SSE")
    public SseEmitter stream(@Valid @RequestBody OrchestrationDtos.ChatRequest request) {
        CurrentUser.Authenticated user = currentUser.require();
        String sessionUuid = resolveSession(request, user);
        Role actorRole = sessions.actorRole(sessionUuid);

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        String traceId = TraceContext.traceId();

        streamExecutor.execute(() -> {
            long started = System.nanoTime();
            // The worker thread needs its own copy of the trace context.
            TraceContext.setTraceId(traceId);
            TraceContext.setSessionUuid(sessionUuid);

            try {
                send(emitter, "start", Map.of(
                        "sessionId", sessionUuid, "traceId", traceId, "aiMode", aiMode.name()));

                GroundingDtos.GroundedAnswer answer = orchestrator.answer(
                        request.message(), sessionUuid, actorRole, user.id(),
                        Boolean.TRUE.equals(request.deepSearch()),
                        event -> {
                            // 'answer' is streamed in chunks below rather than sent whole.
                            if (!"answer".equals(event.type())) {
                                send(emitter, event.type(), event.payload());
                            }
                        });

                streamText(emitter, displayAnswer(answer.answer()));
                send(emitter, "complete",
                        toResponse(answer, (System.nanoTime() - started) / 1_000_000));
                emitter.complete();

            } catch (Exception e) {
                log.error("Streaming turn failed for trace {}", traceId, e);
                send(emitter, "error", Map.of(
                        "message", "Something went wrong answering that. Please try again.",
                        "traceId", traceId));
                emitter.complete();
            } finally {
                TraceContext.clear();
            }
        });

        return emitter;
    }

    // ------------------------------------------------------------------ helpers ---

    /** Uses the supplied session, or opens one so a first message never fails. */
    private String resolveSession(OrchestrationDtos.ChatRequest request, CurrentUser.Authenticated user) {
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            return sessions.require(request.sessionId(), user).sessionUuid();
        }
        ConversationDtos.SessionView created = sessions.create(user, null);
        return created.sessionUuid();
    }

    private void streamText(SseEmitter emitter, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i < text.length(); i += STREAM_CHUNK) {
            String chunk = text.substring(i, Math.min(text.length(), i + STREAM_CHUNK));
            send(emitter, "token", Map.of("text", chunk));
            if (i + STREAM_CHUNK < text.length()) {
                try {
                    Thread.sleep(STREAM_PACE_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void send(SseEmitter emitter, String type, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(type).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // The browser navigated away or the connection dropped. Nothing to recover.
            log.debug("SSE send of '{}' failed: {}", type, e.toString());
        }
    }

    private OrchestrationDtos.ChatResponse toResponse(GroundingDtos.GroundedAnswer answer, long durationMs) {
        List<OrchestrationDtos.CitationView> citations = answer.citations().stream()
                .map(c -> new OrchestrationDtos.CitationView(
                        c.handle(), c.documentCode(), c.documentTitle(), c.section(), c.page(),
                        c.category(), c.relevance(), c.excerpt(), c.toolName(),
                        c.toolOperation(), c.provider(), c.providerLive(),
                        c.retrievedAt() == null ? null : c.retrievedAt().toString()))
                .toList();
        List<OrchestrationDtos.ToolCallView> toolCalls = answer.toolsUsed().stream()
                .map(toolName -> {
                    boolean failed = answer.operationalFailure() != null
                            && answer.operationalFailure().toolFamily().equals(toolName);
                    return new OrchestrationDtos.ToolCallView(
                            toolName,
                            !failed,
                            failed
                                    ? answer.operationalFailure().userMessage()
                                    : "Verified structured result used to ground this answer.",
                            failed ? answer.operationalFailure().userMessage() : null,
                            0,
                            Map.of());
                })
                .toList();
        OrchestrationDtos.OperationalFailureView operationalFailure =
                answer.operationalFailure() == null
                        ? null
                        : new OrchestrationDtos.OperationalFailureView(
                                answer.operationalFailure().toolFamily(),
                                answer.operationalFailure().operation(),
                                answer.operationalFailure().kind().name(),
                                answer.operationalFailure().userMessage(),
                                answer.operationalFailure().details());

        return new OrchestrationDtos.ChatResponse(
                displayAnswer(answer.answer()),
                answer.status().name(),
                answer.escalated(),
                citations,
                answer.followups(),
                toolCalls,
                answer.confidence(),
                answer.citationCoverage(),
                answer.repairAttempts(),
                answer.intent(),
                answer.lane(),
                answer.sessionUuid(),
                answer.traceId(),
                aiMode.name(),
                answer.generationSource().name(),
                answer.degradedReason(),
                operationalFailure,
                answer.proposedAction(),
                answer.commerce(),
                durationMs);
    }

    private static String displayAnswer(String answer) {
        return new com.unitedair.ai.privacy.PiiDisplayFormatter().display(
                answer,
                com.unitedair.ai.privacy.PiiDisplayFormatter.DisplayContext.ASSISTANT);
    }
}
