package com.unitedair.ai.orchestration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.unitedair.ai.audit.AuditDtos;
import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.actions.ActionService;
import com.unitedair.ai.commerce.BookingConversationCoordinator;
import com.unitedair.ai.conversation.ChatMemoryStore;
import com.unitedair.ai.conversation.SessionBookingContext;
import com.unitedair.ai.conversation.SessionService;
import com.unitedair.ai.grounding.CitationBuilder;
import com.unitedair.ai.grounding.CitationAttacher;
import com.unitedair.ai.grounding.EmptyContextPolicy;
import com.unitedair.ai.grounding.GroundingDtos;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.HybridRetriever;
import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.llm.AiMode;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.llm.ChatGateway;
import com.unitedair.ai.privacy.PiiRedactor;
import com.unitedair.ai.privacy.PnrContextRedactor;
import com.unitedair.ai.privacy.PiiType;
import com.unitedair.ai.privacy.RedactionResult;
import com.unitedair.ai.shared.TraceContext;
import com.unitedair.ai.shared.UnitedAirProperties;
import com.unitedair.ai.tools.OperationalFailure;
import com.unitedair.ai.tools.ToolDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The pipeline described in SRS 2.1, and the place where all five agentic patterns of
 * SRS 2.2 meet.
 *
 * <pre>
 *   redact -> remember -> rewrite -> route -+-> tools  -\
 *                                           +-> retrieve -> gate -> generate
 *                                                                    -> evaluate -+-> deliver
 *                                                                                 +-> repair
 *                                                                                 +-> escalate
 * </pre>
 *
 * <p><b>Chain</b> is the fixed order of those stages. <b>Routing</b> is the classifier.
 * <b>Orchestrator-Worker</b> is this class delegating to tools and to the retriever.
 * <b>Parallelization</b> is the tool call and the KB retrieval running together - and,
 * inside retrieval, the two lanes running together. <b>Evaluator-Optimizer</b> is the
 * validate-repair-escalate loop at the end.
 *
 * <p>Control flow is code, not model output. The model writes prose; it does not decide
 * whether to escalate, whether evidence was sufficient, or whether a passenger may see a
 * staff-only document.
 */
@Service
public class AgenticOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgenticOrchestrator.class);

    private static final String FOLLOWUPS_MARKER = "FOLLOWUPS:";

    private final PiiRedactor redactor;
    private final PnrContextRedactor pnrContextRedactor;
    private final ChatMemoryStore memory;
    private final SessionBookingContext bookingContext;
    private final SessionService sessions;
    private final TrustedConversationStateResolver trustedStateResolver;
    private final QueryTransformer queryTransformer;
    private final ContextRelevancePolicy contextRelevancePolicy;
    private final RetrievalQueryExpander queryExpander;
    private final IntentClassifier classifier;
    private final SemanticRoutingPolicy semanticRoutingPolicy;
    private final AdaptiveRoutePlanner routePlanner;
    private final ConversationResponder conversationResponder;
    private final RetrievalFilterFactory retrievalFilterFactory;
    private final ToolOrchestrator toolOrchestrator;
    private final HybridRetriever retriever;
    private final PromptAssembler promptAssembler;
    private final ChatGateway chatGateway;
    private final AnswerEvaluator evaluator;
    private final AnswerPresentationService presentation;
    private final ToolAnswerComposer toolAnswerComposer;
    private final FollowUpService followUpService;
    private final CitationBuilder citationBuilder;
    private final CitationAttacher citationAttacher;
    private final EmptyContextPolicy emptyContextPolicy;
    private final QueryEvidenceRelevance queryEvidenceRelevance;
    private final AuditService audit;
    private final ActionService actions;
    private final BookingConversationCoordinator bookingConversation;
    private final StructuredTurnDeliveryService structuredTurns;
    private final UnitedAirProperties properties;
    private final AiMode aiMode;

    public AgenticOrchestrator(PiiRedactor redactor,
                               PnrContextRedactor pnrContextRedactor,
                               ChatMemoryStore memory,
                               SessionBookingContext bookingContext,
                               SessionService sessions,
                               TrustedConversationStateResolver trustedStateResolver,
                               QueryTransformer queryTransformer,
                               ContextRelevancePolicy contextRelevancePolicy,
                               RetrievalQueryExpander queryExpander,
                               IntentClassifier classifier,
                               SemanticRoutingPolicy semanticRoutingPolicy,
                               AdaptiveRoutePlanner routePlanner,
                               ConversationResponder conversationResponder,
                               RetrievalFilterFactory retrievalFilterFactory,
                               ToolOrchestrator toolOrchestrator,
                               HybridRetriever retriever,
                               PromptAssembler promptAssembler,
                               ChatGateway chatGateway,
                               AnswerEvaluator evaluator,
                               AnswerPresentationService presentation,
                               ToolAnswerComposer toolAnswerComposer,
                               FollowUpService followUpService,
                               CitationBuilder citationBuilder,
                               CitationAttacher citationAttacher,
                               EmptyContextPolicy emptyContextPolicy,
                               QueryEvidenceRelevance queryEvidenceRelevance,
                               AuditService audit,
                               ActionService actions,
                               BookingConversationCoordinator bookingConversation,
                               StructuredTurnDeliveryService structuredTurns,
                               UnitedAirProperties properties,
                               AiMode aiMode) {
        this.redactor = redactor;
        this.pnrContextRedactor = pnrContextRedactor;
        this.memory = memory;
        this.bookingContext = bookingContext;
        this.sessions = sessions;
        this.trustedStateResolver = trustedStateResolver;
        this.queryTransformer = queryTransformer;
        this.contextRelevancePolicy = contextRelevancePolicy;
        this.queryExpander = queryExpander;
        this.classifier = classifier;
        this.semanticRoutingPolicy = semanticRoutingPolicy;
        this.routePlanner = routePlanner;
        this.conversationResponder = conversationResponder;
        this.retrievalFilterFactory = retrievalFilterFactory;
        this.toolOrchestrator = toolOrchestrator;
        this.retriever = retriever;
        this.promptAssembler = promptAssembler;
        this.chatGateway = chatGateway;
        this.evaluator = evaluator;
        this.presentation = presentation;
        this.toolAnswerComposer = toolAnswerComposer;
        this.followUpService = followUpService;
        this.citationBuilder = citationBuilder;
        this.citationAttacher = citationAttacher;
        this.emptyContextPolicy = emptyContextPolicy;
        this.queryEvidenceRelevance = queryEvidenceRelevance;
        this.audit = audit;
        this.actions = actions;
        this.bookingConversation = bookingConversation;
        this.structuredTurns = structuredTurns;
        this.properties = properties;
        this.aiMode = aiMode;
    }

    /**
     * Answers one turn.
     *
     * @param progress receives pipeline stage events; the SSE endpoint forwards them to the
     *                 browser, the synchronous endpoint passes a no-op
     */
    public GroundingDtos.GroundedAnswer answer(String rawMessage,
                                               String sessionUuid,
                                               Role actorRole,
                                               Long userId,
                                               boolean deepRequested,
                                               Consumer<OrchestrationDtos.StreamEvent> progress) {

        long started = System.nanoTime();
        String traceId = TraceContext.traceId();
        TraceContext.setSessionUuid(sessionUuid);

        // Load only already-redacted history. It lets the privacy layer recognise a bare
        // six-letter legacy PNR solely when the preceding assistant turn requested one.
        List<ChatDtos.HistoryTurn> history = memory.window(sessionUuid);
        String pendingSlot = sessions.pendingSlot(sessionUuid)
                .map(SessionService.PendingSlot::name)
                .orElse(null);

        // ---- 1. PII redaction (SRS 4.1.6) -------------------------------------------
        RedactionResult redaction = pnrContextRedactor.redact(
                rawMessage, history, pendingSlot);
        String redactedQuery = redaction.redacted();
        redaction.first(PiiType.PNR)
                .ifPresent(pnr -> bookingContext.remember(
                        sessionUuid, pnr, actorRole, userId));
        if (actorRole == Role.PASSENGER
                && redaction.first(PiiType.PNR).isEmpty()
                && referencesCurrentBooking(rawMessage)
                && bookingContext.resolve(sessionUuid).isEmpty()) {
            bookingContext.rememberUpcoming(sessionUuid, userId);
        }
        TrustedConversationState trustedState = trustedStateResolver.resolve(
                sessionUuid,
                actorRole,
                userId,
                redaction.first(PiiType.PNR).isPresent());
        if (redaction.first(PiiType.PNR).isPresent()) {
            sessions.clearPendingSlot(sessionUuid);
        }
        if (redaction.hasRedactions()) {
            audit.record("PII_REDACTED", actorRole.name(), userId,
                    Map.of("detections", redaction.detectionSummary()));
        }
        emit(progress, "redaction", Map.of(
                "redactionCount", redaction.redactionCount(),
                "types", redaction.detectionSummary().keySet()));

        audit.record("QUERY_RECEIVED", actorRole.name(), userId,
                Map.of("queryRedacted", redactedQuery, "deepRequested", deepRequested));

        // ---- 2. ChatMemory window (SRS 4.3.1) ---------------------------------------
        // ---- 3. Query transformation ------------------------------------------------
        ContextRelevancePolicy.Decision contextDecision =
                contextRelevancePolicy.evaluate(redactedQuery, history, pendingSlot);
        QueryTransformer.Transformation transformation =
                queryTransformer.transform(redactedQuery, history, contextDecision);
        String standaloneQuery = transformation.standalone();
        if (!standaloneQuery.equals(redactedQuery)) {
            emit(progress, "rewrite", Map.of("rewritten", standaloneQuery));
        }

        // ---- 4. Routing (SRS 2.2) ---------------------------------------------------
        // The classifier sees the raw redaction result so it can read the real PNR from the
        // vault; the value never leaves this method.
        boolean explicitBookingReference = referencesCurrentBooking(rawMessage);
        boolean useBookingContext = contextDecision.useHistory()
                || explicitBookingReference;
        RedactionResult contextualRedaction = useBookingContext
                ? withSessionBooking(
                        redaction,
                        explicitBookingReference ? redactedQuery : standaloneQuery,
                        sessionUuid)
                : redaction;
        String routingQuery = useBookingContext
                ? withBookingFlightForStatus(rawMessage, sessionUuid)
                : rawMessage;
        OrchestrationDtos.Classification currentClassification =
                classifier.classify(routingQuery, contextualRedaction, actorRole);
        boolean preserveCurrentRoute =
                shouldPreserveCurrentRoute(currentClassification, redactedQuery);
        OrchestrationDtos.Classification deterministicClassification =
                !preserveCurrentRoute && !standaloneQuery.equals(redactedQuery)
                        ? classifier.classify(standaloneQuery, contextualRedaction, actorRole)
                        : currentClassification;
        RoutingContext routingContext = new RoutingContext(
                redactedQuery,
                standaloneQuery,
                history,
                contextDecision.useHistory(),
                trustedState,
                actorRole,
                userId,
                deterministicClassification);
        ValidatedRoute route = routePlanner.plan(
                routingContext,
                semanticRoutingPolicy.evaluate(routingContext));
        final OrchestrationDtos.Classification classification = route.primary();
        audit.recordSemanticRoute(
                actorRole.name(),
                userId,
                route.source().name(),
                route.scope().name(),
                route.useHistory(),
                contextDecision.reason(),
                route.semanticConfidence(),
                route.degradationReason());
        if ("MODEL_CAPACITY".equalsIgnoreCase(route.degradationReason())
                && route.tools().isEmpty()
                && classification.intent()
                    == OrchestrationDtos.Intent.CLARIFICATION) {
            ChatDtos.ChatResult capacity = new ChatDtos.ChatResult(
                    "",
                    0,
                    0,
                    "hosted-model",
                    ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                    "MODEL_CAPACITY");
            return deliverModelCapacity(
                    classification,
                    capacity,
                    "-",
                    actorRole,
                    userId,
                    sessionUuid,
                    traceId,
                    null,
                    redactedQuery,
                    0,
                    started,
                    progress);
        }
        if (route.actionDecision() == ValidatedRoute.ActionDecision.CONFIRM
                || route.actionDecision() == ValidatedRoute.ActionDecision.REJECT) {
            return structuredTurns.settlePendingAction(
                    route.actionDecision(),
                    sessionUuid,
                    actorRole,
                    userId,
                    redactedQuery,
                    traceId,
                    started,
                    progress);
        }
        if (shouldDeliverCommerce(
                actorRole, trustedState, classification)) {
            BookingConversationCoordinator.CommerceTurn turn =
                    bookingConversation.handlePlanned(
                            redactedQuery,
                            classification,
                            userId,
                            sessionUuid);
            return structuredTurns.deliverCommerce(
                    turn,
                    redactedQuery,
                    sessionUuid,
                    actorRole,
                    userId,
                    traceId,
                    started,
                    progress);
        }
        // The rewritten query supplies retrieval and tool context. Completeness belongs
        // to the current turn: using the rewritten history here made focused follow-ups
        // inherit stale requirements such as SEATS or BOOKING from older turns.
        AnswerRequirements requirements =
                AnswerRequirements.from(redactedQuery, classification)
                        .withTopics(route.topics());
        String retrievalQuery = queryExpander.expand(
                standaloneQuery, classification.documentCodeHints(), requirements);
        if (!retrievalQuery.equals(standaloneQuery)) {
            emit(progress, "rewrite", Map.of("rewritten", retrievalQuery));
        }
        audit.record("INTENT_CLASSIFIED", actorRole.name(), userId, Map.of(
                "intent", classification.intent().name(),
                "tool", classification.tool().name(),
                "rationale", classification.rationale(),
                "routeSource", route.source().name(),
                "historyUsed", route.useHistory(),
                "contextReason", contextDecision.reason(),
                "canonicalToolFamily", canonicalToolFamily(classification.tool()),
                "springAiToolFamilies", chatGateway.registeredToolFamilies()));
        emit(progress, "intent", Map.of(
                "intent", classification.intent().name(),
                "tool", classification.tool().name(),
                "rationale", classification.rationale()));

        if (classification.intent() == OrchestrationDtos.Intent.SMALL_TALK
                || classification.intent() == OrchestrationDtos.Intent.BOOK_FLIGHT
                || classification.intent() == OrchestrationDtos.Intent.CLARIFICATION
                || classification.intent() == OrchestrationDtos.Intent.OUT_OF_SCOPE) {
            return deliverConversation(
                    classification, redactedQuery, history, actorRole, userId,
                    sessionUuid, traceId, started, progress);
        }

        if (classification.intent() == OrchestrationDtos.Intent.ESCALATION) {
            return escalateDirectly(classification, redactedQuery, actorRole, userId,
                    sessionUuid, traceId, started, progress);
        }

        // ---- 5. Tools and retrieval, concurrently (Parallelization) ------------------
        RetrievalDtos.Filter filter = buildFilter(actorRole, classification, requirements);
        RetrievalDtos.Lane lane = deepRequested ? RetrievalDtos.Lane.DEEP : RetrievalDtos.Lane.FAST;

        CompletableFuture<List<ToolDtos.ToolOutcome>> toolFuture =
                !route.tools().isEmpty() || classification.needsTool()
                        ? CompletableFuture.supplyAsync(() ->
                                toolOrchestrator.execute(
                                        route, standaloneQuery, actorRole, userId,
                                        sessionUuid, traceId))
                        : CompletableFuture.completedFuture(List.of());

        CompletableFuture<RetrievalDtos.Result> retrievalFuture =
                classification.needsKb()
                        ? CompletableFuture.supplyAsync(() ->
                                retriever.retrieve(retrievalQuery, filter, lane, 1))
                        : CompletableFuture.completedFuture(emptyRetrieval(filter, lane));

        List<ToolDtos.ToolOutcome> toolOutcomes = toolFuture.join();
        rememberUniqueRefundCase(
                toolOutcomes, bookingContext, sessionUuid, actorRole, userId);
        RetrievalDtos.Result retrieval = retrievalFuture.join();
        List<String> coverageQueries = queryExpander.coverageQueries(requirements);
        if (classification.needsKb()
                && !coverageQueries.isEmpty()) {
            retrieval = retriever.augmentFocused(retrieval, coverageQueries);
        }

        QueryEvidenceRelevance.Result relevance = queryEvidenceRelevance.evaluate(
                standaloneQuery, classification, requirements, retrieval.evidence());
        if (classification.needsKb() && !relevance.relevant()) {
            audit.record("EVIDENCE_REJECTED", actorRole.name(), userId,
                    Map.of("reason", relevance.reason()));
            retrieval = emptyRetrieval(filter, lane);
        }

        if (!toolOutcomes.isEmpty()) {
            emit(progress, "tools", toolOutcomes.stream()
                    .map(t -> Map.of(
                            "toolName", t.toolName(),
                            "success", t.success(),
                            "summary", t.summary() == null ? "" : t.summary(),
                            "error", t.errorMessage() == null ? "" : t.errorMessage(),
                            "durationMs", t.durationMs()))
                    .toList());
        }

        Long retrievalRunId = persistRetrieval(
                retrieval, sessionUuid, traceId, redactedQuery, retrievalQuery);
        emit(progress, "evidence", buildEvidencePreview(retrieval));

        boolean toolGrounded = toolOutcomes.stream().anyMatch(ToolDtos.ToolOutcome::success);

        // ---- 6. Grounding gate (SRS 4.1.1 / 4.1.3) ----------------------------------
        Optional<String> toolFailure = toolAnswerComposer.composeFailure(toolOutcomes);
        if (toolFailure.isPresent()
                && (retrieval.isEmpty()
                    || hasBlockingToolFailure(classification, toolOutcomes))) {
            return deliverToolFailure(
                    toolFailure.get(), toolOutcomes, classification, retrieval,
                    sessionUuid, traceId, actorRole, userId, redactedQuery,
                    retrievalRunId, started, progress);
        }
        if (retrieval.isEmpty() && !toolGrounded) {
            return emptyContext(classification, retrieval, sessionUuid, traceId,
                    actorRole, userId, redactedQuery, retrievalRunId, started, progress);
        }

        // ---- 7-9. Generate, evaluate, and repair once if needed ----------------------
        Attempt attempt = generateAndEvaluate(actorRole, redactedQuery, retrievalQuery,
                retrieval, toolOutcomes, history, toolGrounded, 1, null,
                requirements, progress);

        int repairAttempts = 0;
        if (shouldReturnModelCapacity(
                attempt.verdict().passed(), attempt.result().degradedReason())) {
            persistValidation(sessionUuid, traceId, attempt, false);
            return deliverModelCapacity(
                    classification, attempt.result(), retrieval.lane().name(),
                    actorRole, userId,
                    sessionUuid, traceId, retrievalRunId, redactedQuery,
                    repairAttempts, started, progress);
        }

        if (!attempt.verdict().passed()
                && attempt.verdict().isRepairable()
                && properties.getRag().getMaxRepairAttempts() > 0) {

            repairAttempts = 1;
            audit.record("REPAIR_ATTEMPTED", actorRole.name(), userId,
                    Map.of("failedGates", attempt.verdict().failedGates()));
            emit(progress, "repair", Map.of("failedGates", attempt.verdict().failedGates()));

            // Widening the search only helps when the problem was the evidence. If the
            // answer was well grounded but under-cited, re-retrieving costs another
            // embedding round trip and returns the same passages - so re-prompt against
            // the evidence we already have and keep the repair cheap.
            boolean evidenceWasThin = attempt.verdict().failedGates().stream()
                    .anyMatch(gate -> gate.equals("UNSUPPORTED_CLAIM")
                            || gate.equals("EMPTY_ANSWER")
                            || gate.equals("MISSING_REQUESTED_TOPIC")
                            || gate.equals("MISSING_REQUIRED_CATEGORY")
                            || gate.equals("INCOMPLETE_ANSWER"));

            RetrievalDtos.Result deeper = (evidenceWasThin && classification.needsKb())
                    ? retriever.retrieve(retrievalQuery, filter, RetrievalDtos.Lane.DEEP, 2)
                    : retrieval;
            if (evidenceWasThin
                    && classification.needsKb()
                    && !requirements.requiredCategories().isEmpty()) {
                deeper = retriever.augmentFocused(
                        deeper, queryExpander.coverageQueries(requirements));
            }

            Long deeperRunId = persistRetrieval(
                    deeper, sessionUuid, traceId, redactedQuery, retrievalQuery);
            if (deeperRunId != null) {
                retrievalRunId = deeperRunId;
            }
            emit(progress, "evidence", buildEvidencePreview(deeper));

            Attempt repaired = generateAndEvaluate(actorRole, redactedQuery, retrievalQuery,
                    deeper, toolOutcomes, history, toolGrounded, 2,
                    attempt.verdict().failedGates(), requirements, progress);

            if (repaired.verdict().passed() || !attempt.verdict().passed()) {
                attempt = repaired;
                retrieval = deeper;
            }
        }

        /*
         * A successful structured tool call is already trusted evidence. Occasionally the
         * model restates that data with one uncited sentence even after the repair prompt.
         * Do not throw away valid availability/pricing and create a false escalation:
         * render the structured result deterministically, then run the exact same gates.
         */
        if (!attempt.verdict().passed() && toolGrounded) {
            var deterministicAnswer = toolAnswerComposer.compose(toolOutcomes, retrievalQuery);
            if (deterministicAnswer.isPresent()) {
                List<String> followups = attempt.followups().size() >= properties.getFollowups().getMinimum()
                        ? attempt.followups()
                        : deriveFollowups(retrieval, actorRole, toolOutcomes);
                AnswerEvaluator.Verdict deterministicVerdict = evaluator.evaluate(
                        deterministicAnswer.get(), followups, retrieval.evidence(),
                        retrieval.confidence(), actorRole, true,
                        requirements, toolOutcomes);

                if (deterministicVerdict.passed()) {
                    attempt = new Attempt(deterministicAnswer.get(), followups,
                            deterministicVerdict, attempt.result(), attempt.attemptNo());
                    audit.record("TOOL_ANSWER_FALLBACK", actorRole.name(), userId,
                            Map.of("reason", "MODEL_CITATION_VALIDATION_FAILED",
                                    "tools", toolOutcomes.stream()
                                            .filter(ToolDtos.ToolOutcome::success)
                                            .map(ToolDtos.ToolOutcome::toolName)
                                            .toList()));
                    emit(progress, "validated", Map.of(
                            "source", "STRUCTURED_TOOL_RESULT",
                            "reason", "MODEL_CITATION_VALIDATION_FAILED"));
                } else {
                    log.debug("Structured tool fallback failed gates={} answer={}",
                            deterministicVerdict.failedGates(),
                            deterministicAnswer.get());
                }
            }
        }

        persistValidation(sessionUuid, traceId, attempt, repairAttempts > 0);

        // Provider capacity is retryable and is not a reason to create a human case.
        if (shouldReturnModelCapacity(
                attempt.verdict().passed(), attempt.result().degradedReason())) {
            return deliverModelCapacity(
                    classification, attempt.result(), retrieval.lane().name(),
                    actorRole, userId,
                    sessionUuid, traceId, retrievalRunId, redactedQuery,
                    repairAttempts, started, progress);
        }

        // ---- 10. Escalate if the answer still cannot be trusted ---------------------
        if (!attempt.verdict().passed()) {
            return escalateAfterValidation(classification, attempt, retrieval, redactedQuery,
                    actorRole, userId, sessionUuid, traceId, retrievalRunId,
                    repairAttempts, started, progress);
        }

        // ---- 11. Deliver -------------------------------------------------------------
        ActionDtos.ActionView proposedAction = proposeRequestedCancellation(
                route.actionDecision(), classification,
                sessionUuid, userId, actorRole, toolGrounded);
        // Post-process KB-grounded answers into a cleaner structure for UI consumption
        // while preserving citations.
        return deliver(attempt, retrieval, toolOutcomes, classification, actorRole, userId,
                sessionUuid, traceId, retrievalRunId, repairAttempts, redactedQuery,
                toolGrounded, proposedAction, started, progress);
    }

    // ================================================================ generation ===

    private Attempt generateAndEvaluate(Role actorRole,
                                        String question,
                                        String standaloneQuery,
                                        RetrievalDtos.Result retrieval,
                                        List<ToolDtos.ToolOutcome> toolOutcomes,
                                        List<ChatDtos.HistoryTurn> history,
                                        boolean toolGrounded,
                                        int attemptNo,
                                        List<String> previousFailures,
                                        AnswerRequirements requirements,
                                        Consumer<OrchestrationDtos.StreamEvent> progress) {

        int generateFrom = retrieval.lane() == RetrievalDtos.Lane.DEEP
                ? properties.getRag().getDeep().getGenerateFrom()
                : properties.getRag().getFast().getGenerateFrom();

        ChatDtos.ChatRequest request = promptAssembler.assemble(
                actorRole, question, standaloneQuery, retrieval.evidence(),
                toolOutcomes, history, generateFrom, requirements);
        List<RetrievalDtos.Ranked> generationEvidence =
                evidenceSuppliedToModel(request, retrieval.evidence());

        if (previousFailures != null && !previousFailures.isEmpty()) {
            request = new ChatDtos.ChatRequest(
                    request.systemPrompt()
                            + promptAssembler.repairInstruction(
                                    previousFailures, requirements),
                    request.history(), request.userPrompt(), request.grounding());
        }

        emit(progress, "generating", Map.of("attempt", attemptNo, "lane", retrieval.lane().name()));

        ChatDtos.ChatResult result = chatGateway.complete(request);
        if (!result.live() && toolGrounded) {
            var deterministicToolAnswer = toolAnswerComposer.compose(toolOutcomes, standaloneQuery);
            if (deterministicToolAnswer.isPresent()) {
                result = new ChatDtos.ChatResult(
                        deterministicToolAnswer.get(),
                        result.promptTokens(),
                        result.completionTokens(),
                        result.model(),
                        ChatDtos.GenerationSource.STRUCTURED_TOOL,
                        result.degradedReason());
                emit(progress, "validated", Map.of(
                        "source", "STRUCTURED_TOOL_RESULT",
                        "reason", result.degradedReason() == null
                                ? "OFFLINE_TOOL_COMPOSITION"
                                : result.degradedReason()));
            }
        }
        Parsed parsed = splitFollowups(result.text());
        CitationAttacher.AttachmentResult attached = citationAttacher.attach(
                parsed.answer(), generationEvidence, toolOutcomes);

        List<String> followups = parsed.followups().isEmpty()
                ? deriveFollowups(retrieval, actorRole, toolOutcomes)
                : parsed.followups();

        AnswerEvaluator.Verdict verdict = evaluator.evaluate(
                attached.answer(), followups, generationEvidence,
                retrieval.confidence(), actorRole, toolGrounded,
                requirements, toolOutcomes);

        if (shouldTryGroundedFallback(
                verdict.passed(), previousFailures != null, result.live())) {
            ChatDtos.ChatResult fallbackResult = chatGateway.composeGroundedFallback(
                    request, "MODEL_CITATION_VALIDATION_FAILED");
            Parsed fallbackParsed = splitFollowups(fallbackResult.text());
            CitationAttacher.AttachmentResult fallbackAttached = citationAttacher.attach(
                    fallbackParsed.answer(), generationEvidence, toolOutcomes);
            List<String> fallbackFollowups = fallbackParsed.followups().isEmpty()
                    ? deriveFollowups(retrieval, actorRole, toolOutcomes)
                    : fallbackParsed.followups();
            AnswerEvaluator.Verdict fallbackVerdict = evaluator.evaluate(
                    fallbackAttached.answer(), fallbackFollowups, generationEvidence,
                    retrieval.confidence(), actorRole, toolGrounded,
                    requirements, toolOutcomes);
            if (fallbackVerdict.passed()) {
                emit(progress, "validated", Map.of(
                        "source", "STRUCTURED_KB_RESULT",
                        "reason", "MODEL_CITATION_VALIDATION_FAILED"));
                return new Attempt(
                        fallbackAttached.answer(), fallbackFollowups,
                        fallbackVerdict, fallbackResult, attemptNo);
            }
        }

        log.debug("Attempt {} verdict={} gates={} coverage={} attached={} unmatched={}",
                attemptNo, verdict.passed(), verdict.failedGates(), verdict.citationCoverage(),
                attached.matchedSentences(), attached.unmatchedFactualSentences().size());
        if (!verdict.passed()) {
            log.debug("Rejected grounded draft: {}",
                    attached.answer().replace('\n', ' '));
        }

        return new Attempt(attached.answer(), followups, verdict, result, attemptNo);
    }

    static List<RetrievalDtos.Ranked> evidenceSuppliedToModel(
            ChatDtos.ChatRequest request,
            List<RetrievalDtos.Ranked> retrievedEvidence) {
        if (request == null
                || request.grounding() == null
                || retrievedEvidence == null
                || retrievedEvidence.isEmpty()) {
            return List.of();
        }
        Set<String> suppliedHandles = request.grounding().stream()
                .map(ChatDtos.Grounding::handle)
                .filter(handle -> handle != null && handle.startsWith("E"))
                .collect(java.util.stream.Collectors.toSet());
        return retrievedEvidence.stream()
                .filter(ranked -> suppliedHandles.contains(
                        CitationBuilder.handleFor(ranked.rank())))
                .toList();
    }

    static boolean shouldTryGroundedFallback(
            boolean verdictPassed,
            boolean repairAttempt,
            boolean liveResult) {
        return !verdictPassed && repairAttempt && liveResult;
    }

    /**
     * Separates the answer body from the FOLLOWUPS line the system prompt requests.
     * A model that ignores the instruction is not a failure state - {@link #deriveFollowups}
     * covers it - but the marker is stripped either way so it never reaches the user.
     */
    static Parsed splitFollowups(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Parsed("", List.of());
        }
        int marker = raw.lastIndexOf(FOLLOWUPS_MARKER);
        if (marker < 0) {
            return new Parsed(raw.trim(), List.of());
        }

        String body = raw.substring(0, marker).trim();
        String tail = raw.substring(marker + FOLLOWUPS_MARKER.length()).trim();

        List<String> followups = Arrays.stream(tail.split("\\||\\n"))
                .map(s -> s.replaceAll("^\\s*[-*\\d.)]+\\s*", "").trim())
                .filter(s -> s.length() > 8)
                .toList();

        return new Parsed(body, distinctFollowups(followups, 4));
    }

    static List<String> distinctFollowups(List<String> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String cleaned = candidate.trim().replaceAll("\\s+", " ");
            unique.putIfAbsent(cleaned.toLowerCase(), cleaned);
            if (unique.size() >= limit) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    static boolean shouldPreserveCurrentRoute(
            OrchestrationDtos.Classification classification,
            String redactedQuery) {
        boolean pnrOnlyReply = redactedQuery != null && redactedQuery.matches(
                "(?i)^\\s*(?:(?:the\\s+)?(?:pnr|booking\\s+reference|record\\s+locator)"
                        + "(?:\\s+is)?\\s*[:#-]?\\s*)?"
                        + "\\[air-pnr-redacted]\\s*[?.!]*\\s*$");
        return classification.intent() == OrchestrationDtos.Intent.ESCALATION
                || classification.intent() == OrchestrationDtos.Intent.SMALL_TALK
                || classification.intent() == OrchestrationDtos.Intent.OUT_OF_SCOPE
                || (classification.tool()
                        == OrchestrationDtos.ToolTarget.FLIGHT_SEARCH
                    && classification.origin() != null
                    && classification.destination() != null)
                || (classification.needsTool()
                    && classification.missingParameters().isEmpty()
                    && !pnrOnlyReply)
                || classification.missingParameters().contains("request");
    }

    static boolean shouldDeliverCommerce(
            Role actorRole,
            TrustedConversationState trustedState,
            OrchestrationDtos.Classification classification) {
        if (actorRole != Role.PASSENGER || classification == null) {
            return false;
        }
        if (classification.intent() == OrchestrationDtos.Intent.BOOK_FLIGHT) {
            return true;
        }
        return trustedState != null
                && trustedState.activeBookingDraft()
                && classification.tool()
                    == OrchestrationDtos.ToolTarget.FLIGHT_SEARCH
                && classification.origin() != null
                && classification.destination() != null;
    }

    static boolean hasBlockingToolFailure(
            OrchestrationDtos.Classification classification,
            List<ToolDtos.ToolOutcome> outcomes) {
        return classification != null
                && outcomes != null
                && !outcomes.isEmpty()
                && classification.needsTool()
                && outcomes.stream().noneMatch(ToolDtos.ToolOutcome::success);
    }

    /**
     * Fallback follow-ups built from the sections actually retrieved.
     *
     * <p>SRS 4.3.3 requires suggestions to come from the same chunks that produced the
     * answer rather than being invented, so these name real sections of real documents.
     */
    private List<String> deriveFollowups(RetrievalDtos.Result retrieval,
                                         Role role,
                                         List<ToolDtos.ToolOutcome> toolOutcomes) {
        return followUpService.texts(followUpService.suggest(
                role, retrieval.evidence(), toolOutcomes, false));
    }

    static List<String> deriveToolFollowups(List<ToolDtos.ToolOutcome> toolOutcomes) {
        for (ToolDtos.ToolOutcome outcome : toolOutcomes) {
            if ("FlightSearchTool".equals(outcome.toolName())
                    && outcome.data() instanceof ToolDtos.FlightSearchResult result) {
                if (result.flights() == null || result.flights().isEmpty()) {
                    return List.of(
                            "Search this route on a different date.",
                            "Search a different origin or destination.");
                }
                return List.of(
                        "Show the fare and baggage details for these flights.",
                        "Are there refundable options on this route?");
            }
            if ("BookingManagementTool".equals(outcome.toolName())) {
                if (outcome.data() instanceof List<?> rows
                        && !rows.isEmpty()
                        && rows.stream().allMatch(
                                com.unitedair.ai.commerce.RefundDtos
                                        .RefundStatusView.class::isInstance)) {
                    return List.of(
                            "Check the latest refund status for this case.",
                            "Show the refund amount and settlement timeline.");
                }
                return List.of(
                        "Show the fare and baggage rules for this booking.",
                        "Check whether this booking is refundable.");
            }
            if ("CheckInStatusTool".equals(outcome.toolName())) {
                return List.of(
                        "Check the latest gate and flight status.",
                        "Explain the check-in and boarding requirements.");
            }
        }
        return List.of();
    }

    private static String toolOperation(ToolDtos.ToolOutcome outcome) {
        if (outcome.request() != null) {
            Object operation = outcome.request().get("operation");
            if (operation != null && !String.valueOf(operation).isBlank()) {
                return String.valueOf(operation).toUpperCase(java.util.Locale.ROOT);
            }
        }
        if (outcome.data() instanceof ToolDtos.FlightSearchResult) {
            return "SEARCH_FLIGHTS";
        }
        if (outcome.data() instanceof ToolDtos.BookingView) {
            return "GET_BOOKING";
        }
        if (outcome.data() instanceof ToolDtos.RefundQuote) {
            return "QUOTE_REFUND";
        }
        if (outcome.data() instanceof ToolDtos.FlightStatusView) {
            return "GET_FLIGHT_STATUS";
        }
        if (outcome.data() instanceof ToolDtos.CheckInEligibility) {
            return "CHECK_IN_ELIGIBILITY";
        }
        return "READ";
    }

    private static String toolProvider(ToolDtos.ToolOutcome outcome) {
        if (outcome.request() != null) {
            Object provider = outcome.request().get("provider");
            if (provider != null && !String.valueOf(provider).isBlank()) {
                return String.valueOf(provider);
            }
        }
        return "UNITEDAIR_SIMULATOR";
    }

    private static String lowerFirst(String value) {
        return value.isEmpty() ? value : Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private RedactionResult withSessionBooking(RedactionResult redaction,
                                               String standaloneQuery,
                                               String sessionUuid) {
        if (redaction.first(PiiType.PNR).isPresent()
                || (!standaloneQuery.toLowerCase().contains("[air-pnr-redacted]")
                    && !referencesCurrentBooking(standaloneQuery))) {
            return redaction;
        }
        return bookingContext.resolve(sessionUuid)
                .map(pnr -> new RedactionResult(
                        standaloneQuery,
                        Map.of(PiiType.PNR, List.of(pnr)),
                        Math.max(1, redaction.redactionCount())))
                .orElse(redaction);
    }

    private static boolean referencesCurrentBooking(String query) {
        String lower = query == null ? "" : query.toLowerCase();
        return lower.matches(".*\\bmy\\s+(?:current\\s+|active\\s+|upcoming\\s+)?booking\\b.*")
                || lower.matches(".*\\bmy\\s+(?:current\\s+|active\\s+|upcoming\\s+)?flight\\b.*")
                || lower.contains("this booking")
                || lower.contains("that booking")
                || lower.contains("the booking")
                || lower.contains("booking refundable")
                || lower.contains("cancel it")
                || lower.contains("reschedule it");
    }

    private String withBookingFlightForStatus(String query, String sessionUuid) {
        return routingQueryWithBookingContext(
                query,
                bookingContext.resolveReference(sessionUuid).orElse(null),
                bookingContext.resolveDetails(sessionUuid).orElse(null));
    }

    static String routingQueryWithBookingContext(
            String query,
            SessionBookingContext.ConversationReference reference,
            SessionBookingContext.BookingContext details) {
        String lower = query == null ? "" : query.toLowerCase();
        boolean asksStatus = lower.contains("status")
                || lower.contains("on time")
                || lower.contains("on-time")
                || lower.contains("delayed")
                || lower.contains("delay")
                || lower.contains("gate")
                || lower.contains("terminal");
        if (!asksStatus) {
            return query;
        }
        boolean explicitFlightStatus = lower.contains("flight status")
                || lower.contains("that flight")
                || lower.contains("the flight")
                || lower.contains("on time")
                || lower.contains("on-time")
                || lower.contains("delayed")
                || lower.contains("delay")
                || lower.contains("gate")
                || lower.contains("terminal");
        boolean bookingReferenceStatus = lower.contains("booking")
                || lower.contains("reference")
                || lower.contains("pnr");
        if (!explicitFlightStatus
                && reference != null
                && reference.refundCaseUuid() != null
                && (lower.contains("refund") || bookingReferenceStatus)) {
            return query + " refund status";
        }
        if (explicitFlightStatus && details != null) {
            return query + " Flight " + details.flightNo()
                    + " on " + details.travelDate();
        }
        return query;
    }

    static void rememberUniqueRefundCase(
            List<ToolDtos.ToolOutcome> outcomes,
            SessionBookingContext bookingContext,
            String sessionUuid,
            Role actorRole,
            Long userId) {
        if (outcomes == null || bookingContext == null) {
            return;
        }
        List<com.unitedair.ai.commerce.RefundDtos.RefundStatusView> cases =
                outcomes.stream()
                        .filter(ToolDtos.ToolOutcome::success)
                        .map(ToolDtos.ToolOutcome::data)
                        .filter(List.class::isInstance)
                        .map(List.class::cast)
                        .filter(rows -> rows.size() == 1
                                && rows.getFirst()
                                        instanceof com.unitedair.ai.commerce
                                                .RefundDtos.RefundStatusView)
                        .map(rows -> (com.unitedair.ai.commerce
                                .RefundDtos.RefundStatusView) rows.getFirst())
                        .toList();
        if (cases.size() == 1) {
            bookingContext.remember(
                    sessionUuid,
                    cases.getFirst().bookingReferenceDisplay(),
                    actorRole,
                    userId);
        }
    }

    private ActionDtos.ActionView proposeRequestedCancellation(
            ValidatedRoute.ActionDecision actionDecision,
            OrchestrationDtos.Classification classification,
            String sessionUuid,
            Long userId,
            Role actorRole,
            boolean toolGrounded) {
        if (!toolGrounded
                || classification.tool() != OrchestrationDtos.ToolTarget.REFUND_QUOTE
                || classification.pnr() == null) {
            return null;
        }
        if (actionDecision != ValidatedRoute.ActionDecision.PROPOSE) {
            return null;
        }
        try {
            return actions.propose(
                    new ActionDtos.ProposeRequest(
                            "CANCEL_BOOKING", classification.pnr(), sessionUuid,
                            null, null, null),
                    sessionUuid,
                    userId,
                    actorRole);
        } catch (RuntimeException unableToPropose) {
            log.warn("Could not create cancellation proposal for trace {}: {}",
                    TraceContext.traceId(), unableToPropose.getMessage());
            return null;
        }
    }

    // ================================================================== outcomes ===

    private GroundingDtos.GroundedAnswer deliver(Attempt attempt,
                                                 RetrievalDtos.Result retrieval,
                                                 List<ToolDtos.ToolOutcome> toolOutcomes,
                                                 OrchestrationDtos.Classification classification,
                                                 Role actorRole,
                                                 Long userId,
                                                 String sessionUuid,
                                                 String traceId,
                                                 Long retrievalRunId,
                                                 int repairAttempts,
                                                 String redactedQuery,
                                                 boolean toolGrounded,
                                                 ActionDtos.ActionView proposedAction,
                                                 long started,
                                                 Consumer<OrchestrationDtos.StreamEvent> progress) {

        List<GroundingDtos.Citation> offered = citationBuilder.fromEvidence(retrieval.evidence());
        int toolHandle = 1;
        for (ToolDtos.ToolOutcome outcome : toolOutcomes) {
            if (outcome.success()) {
                offered = new ArrayList<>(offered);
                offered.add(GroundingDtos.Citation.fromTool(
                        "T" + toolHandle++, outcome.toolName(),
                        toolOperation(outcome), toolProvider(outcome),
                        outcome.summary(), outcome.invokedAt()));
            }
        }

        // Redaction runs again on the way out: the model was given redacted input, but an
        // answer can still echo a value out of a tool result.
        String redactedAnswer = redactor.redact(attempt.answer()).redacted();
        AnswerPresentationService.PresentedAnswer presented = presentation.present(
                new AnswerPresentationService.PresentationRequest(
                        actorRole,
                        redactedQuery,
                        redactedAnswer,
                        retrieval.evidence(),
                        classification.intent().name(),
                        false));
        String safeAnswer = presented.text();
        List<GroundingDtos.Citation> used = citationBuilder.retainCited(offered, safeAnswer);
        if (classification.intent() == OrchestrationDtos.Intent.TOOL_PLUS_KB) {
            Optional<GroundingDtos.Citation> kb = offered.stream()
                    .filter(citation -> !citation.isToolCitation())
                    .findFirst();
            Optional<GroundingDtos.Citation> tool = offered.stream()
                    .filter(GroundingDtos.Citation::isToolCitation)
                    .findFirst();
            if (kb.isPresent() && tool.isPresent()) {
                java.util.LinkedHashSet<GroundingDtos.Citation> completeSources =
                        new java.util.LinkedHashSet<>(used);
                completeSources.add(kb.get());
                completeSources.add(tool.get());
                used = List.copyOf(completeSources);
                if (!safeAnswer.contains("[" + kb.get().handle() + "]")
                        || !safeAnswer.contains("[" + tool.get().handle() + "]")) {
                    safeAnswer += "\n\nSources used: [" + kb.get().handle()
                            + "] [" + tool.get().handle() + "].";
                }
            }
        }

        GroundingDtos.AnswerStatus status = toolGrounded && retrieval.isEmpty()
                ? GroundingDtos.AnswerStatus.TOOL_GROUNDED
                : GroundingDtos.AnswerStatus.GROUNDED;

        long durationMs = (System.nanoTime() - started) / 1_000_000;

        Long answerId = audit.saveAnswerRecord(new AuditDtos.AnswerRecordRow(
                sessionUuid, traceId, retrievalRunId, status.name(),
                classification.intent().name(), actorRole.name(), safeAnswer,
                used, attempt.followups(),
                toolOutcomes.stream().map(ToolDtos.ToolOutcome::toolName).toList(),
                retrieval.confidence(), attempt.verdict().citationCoverage(), repairAttempts,
                false, attempt.result().model(), aiMode.name(), attempt.result().degradedReason(),
                attempt.result().promptTokens(), attempt.result().completionTokens(), durationMs));

        audit.record("ANSWER_GENERATED", actorRole.name(), userId, Map.of(
                "status", status.name(),
                "confidence", retrieval.confidence(),
                "citations", used.size(),
                "repairAttempts", repairAttempts));

        recordTurn(sessionUuid, redactedQuery, safeAnswer, answerId, traceId);

        emit(progress, "answer", Map.of("text", safeAnswer));

        return new GroundingDtos.GroundedAnswer(
                safeAnswer, status, false, used, attempt.followups(),
                toolOutcomes.stream().map(ToolDtos.ToolOutcome::toolName).toList(),
                retrieval.confidence(), attempt.verdict().citationCoverage(),
                repairAttempts, classification.intent().name(), retrieval.lane().name(),
                traceId, sessionUuid, attempt.result().generationSource(),
                attempt.result().degradedReason(), proposedAction);
    }

    private GroundingDtos.GroundedAnswer deliverToolFailure(
            String failureMessage,
            List<ToolDtos.ToolOutcome> toolOutcomes,
            OrchestrationDtos.Classification classification,
            RetrievalDtos.Result retrieval,
            String sessionUuid,
            String traceId,
            Role actorRole,
            Long userId,
            String redactedQuery,
            Long retrievalRunId,
            long started,
            Consumer<OrchestrationDtos.StreamEvent> progress) {

        String safeAnswer = redactor.redact(failureMessage).redacted()
                .replaceFirst("[.?!]\\s*$", "") + " [T1].";
        List<String> toolsUsed = toolOutcomes.stream()
                .map(ToolDtos.ToolOutcome::toolName)
                .distinct()
                .toList();
        List<String> followups = List.of(
                "Would you like to check the details and try again?",
                "Can I help with another UnitedAir request?");
        List<GroundingDtos.Citation> citations = new ArrayList<>();
        int handle = 0;
        for (ToolDtos.ToolOutcome outcome : toolOutcomes) {
            citations.add(GroundingDtos.Citation.fromTool(
                    "T" + (++handle),
                    outcome.toolName(),
                    toolOperation(outcome),
                    toolProvider(outcome),
                    outcome.success() ? outcome.summary() : outcome.errorMessage(),
                    outcome.invokedAt()));
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;

        audit.record("TOOL_VALIDATION_FAILED", actorRole.name(), userId, Map.of(
                "tools", toolsUsed,
                "intent", classification.intent().name()));
        Long answerId = audit.saveAnswerRecord(new AuditDtos.AnswerRecordRow(
                sessionUuid, traceId, retrievalRunId,
                GroundingDtos.AnswerStatus.ERROR.name(),
                classification.intent().name(), actorRole.name(), safeAnswer,
                citations, followups, toolsUsed,
                retrieval.confidence(), 1.0, 0, false,
                null, aiMode.name(), null, null, null, durationMs));

        recordTurn(sessionUuid, redactedQuery, safeAnswer, answerId, traceId);
        emit(progress, "answer", Map.of("text", safeAnswer));

        return new GroundingDtos.GroundedAnswer(
                safeAnswer, GroundingDtos.AnswerStatus.ERROR, false,
                citations, followups, toolsUsed,
                retrieval.confidence(), 1.0, 0,
                classification.intent().name(), retrieval.lane().name(),
                traceId, sessionUuid, ChatDtos.GenerationSource.STRUCTURED_TOOL,
                null, firstOperationalFailure(toolOutcomes), null);
    }

    private GroundingDtos.GroundedAnswer deliverModelCapacity(
            OrchestrationDtos.Classification classification,
            ChatDtos.ChatResult modelResult,
            String lane,
            Role actorRole,
            Long userId,
            String sessionUuid,
            String traceId,
            Long retrievalRunId,
            String redactedQuery,
            int repairAttempts,
            long started,
            Consumer<OrchestrationDtos.StreamEvent> progress) {

        String message =
                "The AI model is currently at capacity. Please try again shortly.";
        long durationMs = (System.nanoTime() - started) / 1_000_000;

        audit.record("MODEL_CAPACITY_REACHED", actorRole.name(), userId, Map.of(
                "intent", classification.intent().name(),
                "providerReason", modelResult.degradedReason()));
        Long answerId = audit.saveAnswerRecord(new AuditDtos.AnswerRecordRow(
                sessionUuid, traceId, retrievalRunId,
                GroundingDtos.AnswerStatus.ERROR.name(),
                classification.intent().name(), actorRole.name(), message,
                List.of(), List.of(), List.of(),
                null, null, repairAttempts, false,
                modelResult.model(), aiMode.name(), "MODEL_CAPACITY",
                modelResult.promptTokens(), modelResult.completionTokens(), durationMs));

        recordTurn(sessionUuid, redactedQuery, message, answerId, traceId);
        emit(progress, "answer", Map.of("text", message));

        return new GroundingDtos.GroundedAnswer(
                message, GroundingDtos.AnswerStatus.ERROR, false,
                List.of(), List.of(), List.of(),
                null, null, repairAttempts,
                classification.intent().name(), lane,
                traceId, sessionUuid,
                ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                "MODEL_CAPACITY", null);
    }

    private static OperationalFailure firstOperationalFailure(
            List<ToolDtos.ToolOutcome> outcomes) {
        return outcomes.stream()
                .filter(outcome -> !outcome.success())
                .map(ToolDtos.ToolOutcome::data)
                .filter(OperationalFailure.class::isInstance)
                .map(OperationalFailure.class::cast)
                .findFirst()
                .orElse(null);
    }

    private GroundingDtos.GroundedAnswer emptyContext(OrchestrationDtos.Classification classification,
                                                      RetrievalDtos.Result retrieval,
                                                      String sessionUuid,
                                                      String traceId,
                                                      Role actorRole,
                                                      Long userId,
                                                      String redactedQuery,
                                                      Long retrievalRunId,
                                                      long started,
                                                      Consumer<OrchestrationDtos.StreamEvent> progress) {

        log.info("Empty context for trace {} - returning the SRS 4.1.3 response without calling the model",
                traceId);

        GroundingDtos.GroundedAnswer answer = emptyContextPolicy.response(
                classification.intent().name(), retrieval.lane().name(),
                traceId, sessionUuid, retrieval.confidence());

        audit.record("EMPTY_CONTEXT", actorRole.name(), userId, Map.of(
                "confidence", retrieval.confidence(),
                "vectorHits", retrieval.vectorHits(),
                "lexicalHits", retrieval.lexicalHits()));

        Long answerId = audit.saveAnswerRecord(new AuditDtos.AnswerRecordRow(
                sessionUuid, traceId, retrievalRunId,
                GroundingDtos.AnswerStatus.EMPTY_CONTEXT.name(),
                classification.intent().name(), actorRole.name(), answer.answer(),
                List.of(), List.of(), List.of(),
                retrieval.confidence(), 0.0, 0, true, null, aiMode.name(), null, null, null,
                (System.nanoTime() - started) / 1_000_000));

        recordTurn(sessionUuid, redactedQuery, answer.answer(), answerId, traceId);
        emit(progress, "answer", Map.of("text", answer.answer()));
        return answer;
    }

    private GroundingDtos.GroundedAnswer escalateDirectly(OrchestrationDtos.Classification classification,
                                                          String redactedQuery,
                                                          Role actorRole,
                                                          Long userId,
                                                          String sessionUuid,
                                                          String traceId,
                                                          long started,
                                                          Consumer<OrchestrationDtos.StreamEvent> progress) {

        ToolDtos.EscalationResult escalation = toolOrchestrator.escalate(
                classification.escalationReason(),
                "Passenger reported: " + redactedQuery,
                classification.pnr(), null, actorRole);

        return finishEscalation(escalation, classification, redactedQuery, actorRole, userId,
                sessionUuid, traceId, null, null, 0, started, progress);
    }

    private GroundingDtos.GroundedAnswer escalateAfterValidation(OrchestrationDtos.Classification classification,
                                                                 Attempt attempt,
                                                                 RetrievalDtos.Result retrieval,
                                                                 String redactedQuery,
                                                                 Role actorRole,
                                                                 Long userId,
                                                                 String sessionUuid,
                                                                 String traceId,
                                                                 Long retrievalRunId,
                                                                 int repairAttempts,
                                                                 long started,
                                                                 Consumer<OrchestrationDtos.StreamEvent> progress) {

        String reason = attempt.verdict().failedGates().contains("LOW_CONFIDENCE")
                ? "LOW_CONFIDENCE"
                : "VALIDATION_FAILURE";

        log.info("Escalating trace {} after validation failure: {}", traceId, attempt.verdict().failedGates());

        ToolDtos.EscalationResult escalation = toolOrchestrator.escalate(
                reason,
                "Assistant could not produce a sufficiently grounded answer to: " + redactedQuery
                        + " (failed: " + attempt.verdict().gatesAsString() + ")",
                classification.pnr(), retrieval.confidence(), actorRole);

        return finishEscalation(escalation, classification, redactedQuery, actorRole, userId,
                sessionUuid, traceId, retrievalRunId, retrieval.confidence(),
                repairAttempts, started, progress);
    }

    private GroundingDtos.GroundedAnswer finishEscalation(ToolDtos.EscalationResult escalation,
                                                          OrchestrationDtos.Classification classification,
                                                          String redactedQuery,
                                                          Role actorRole,
                                                          Long userId,
                                                          String sessionUuid,
                                                          String traceId,
                                                          Long retrievalRunId,
                                                          Double confidence,
                                                          int repairAttempts,
                                                          long started,
                                                          Consumer<OrchestrationDtos.StreamEvent> progress) {

        String message = escalation == null
                ? "I am not able to resolve this here. Please contact your UnitedAir Customer "
                        + "Support Manager, who can take this forward."
                : escalation.message();
        message = message.replaceFirst("[.?!]\\s*$", "") + " [T1].";

        // SRS 4.3.3: once escalated, follow-ups point at the escalation channel only.
        List<String> followups = followUpService.texts(followUpService.suggest(
                actorRole, List.of(), List.of(), true));
        List<GroundingDtos.Citation> citations = List.of(
                GroundingDtos.Citation.fromTool(
                        "T1",
                        "EscalationTool",
                        "CREATE_ESCALATION",
                        "UNITEDAIR_SUPPORT_CASES",
                        escalation == null
                                ? "Escalation requested"
                                : "Case " + escalation.caseUuid() + " created in "
                                        + escalation.targetQueue(),
                        escalation == null
                                ? java.time.Instant.now()
                                : escalation.createdAt()));

        audit.record("ESCALATED", actorRole.name(), userId, Map.of(
                "reason", escalation == null ? "UNKNOWN" : escalation.reason(),
                "queue", escalation == null ? "UNKNOWN" : escalation.targetQueue(),
                "confidence", confidence == null ? -1 : confidence));

        Long answerId = audit.saveAnswerRecord(new AuditDtos.AnswerRecordRow(
                sessionUuid, traceId, retrievalRunId,
                GroundingDtos.AnswerStatus.ESCALATED.name(),
                classification.intent().name(), actorRole.name(), message,
                citations, followups, List.of("EscalationTool"),
                confidence, 1.0, repairAttempts, true, null, aiMode.name(), null, null, null,
                (System.nanoTime() - started) / 1_000_000));

        recordTurn(sessionUuid, redactedQuery, message, answerId, traceId);
        emit(progress, "escalated", Map.of(
                "message", message,
                "queue", escalation == null ? "" : escalation.targetQueue()));
        emit(progress, "answer", Map.of("text", message));

        return new GroundingDtos.GroundedAnswer(
                message, GroundingDtos.AnswerStatus.ESCALATED, true,
                citations, followups, List.of("EscalationTool"),
                confidence, 1.0, repairAttempts,
                classification.intent().name(), "-", traceId, sessionUuid,
                ChatDtos.GenerationSource.STRUCTURED_TOOL, null, null);
    }

    // =================================================================== helpers ===

    private GroundingDtos.GroundedAnswer deliverConversation(
            OrchestrationDtos.Classification classification,
            String redactedQuery,
            List<ChatDtos.HistoryTurn> history,
            Role actorRole,
            Long userId,
            String sessionUuid,
            String traceId,
            long started,
            Consumer<OrchestrationDtos.StreamEvent> progress) {
        ConversationResponder.Response response = conversationResponder.respond(
                classification.intent(), redactedQuery, history, classification.missingParameters());
        ChatDtos.ChatResult modelResult = response.modelResult();
        if (shouldReturnModelCapacity(false, modelResult.degradedReason())) {
            return deliverModelCapacity(
                    classification, modelResult, "-", actorRole, userId,
                    sessionUuid, traceId, null, redactedQuery,
                    0, started, progress);
        }
        if (classification.missingParameters().contains("pnr")) {
            sessions.rememberPendingSlot(
                    sessionUuid, "pnr", classification.tool());
        }
        String safeAnswer = redactor.redact(response.text()).redacted();
        GroundingDtos.AnswerStatus status = switch (classification.intent()) {
            case SMALL_TALK -> GroundingDtos.AnswerStatus.CONVERSATIONAL;
            case BOOK_FLIGHT -> GroundingDtos.AnswerStatus.CLARIFICATION;
            case CLARIFICATION -> GroundingDtos.AnswerStatus.CLARIFICATION;
            case OUT_OF_SCOPE -> GroundingDtos.AnswerStatus.OUT_OF_SCOPE;
            default -> throw new IllegalStateException(
                    "Unsupported conversational route " + classification.intent());
        };
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        List<GroundingDtos.Citation> citations = List.of();
        List<String> followups = response.followups();

        Long answerId = audit.saveAnswerRecord(new AuditDtos.AnswerRecordRow(
                sessionUuid, traceId, null, status.name(),
                classification.intent().name(), actorRole.name(), safeAnswer,
                citations, followups, List.of(),
                0.0, 0.0, 0, false,
                modelResult.model(), aiMode.name(), modelResult.degradedReason(),
                modelResult.promptTokens(), modelResult.completionTokens(), durationMs));

        audit.record("CONVERSATION_ANSWERED", actorRole.name(), userId, Map.of(
                "status", status.name(),
                "live", modelResult.live()));
        recordTurn(sessionUuid, redactedQuery, safeAnswer, answerId, traceId);
        emit(progress, "answer", Map.of("text", safeAnswer));

        return new GroundingDtos.GroundedAnswer(
                safeAnswer, status, false,
                citations, followups, List.of(),
                0.0, 0.0, 0,
                classification.intent().name(), "-", traceId, sessionUuid,
                modelResult.generationSource(),
                modelResult.degradedReason(), null);
    }

    /**
     * Builds the retrieval filter.
     *
     * <p><b>Audience is a hard filter and always comes from the role</b>, never from the
     * request. That is the security boundary of SRS 4.3.2 and it is not negotiable.
     *
     * <p><b>Category is deliberately not filtered here.</b> Intent-derived category hints
     * were originally pushed into the WHERE clause as well, and it actively broke
     * retrieval: "when is the flight from Bengaluru to Delhi" hints at {@code fare-rule},
     * which excludes KB-AIR-001 - the flight booking document that actually answers it.
     * Twelve chunks were retrieved and none survived, so a routine question returned
     * "no matching policy found".
     *
     * <p>A hint is a guess about topic. Excluding documents on a guess trades a large
     * recall loss for a small precision gain, and on a corpus of 148 chunks the precision
     * was never the problem. The reranker already boosts topical agreement, which is the
     * right place for a soft signal. Explicit category filters supplied through
     * {@code POST /kb/search} are still honoured, because there the caller means it.
     */
    private RetrievalDtos.Filter buildFilter(
            Role actorRole,
            OrchestrationDtos.Classification classification,
            AnswerRequirements requirements) {
        return retrievalFilterFactory.create(actorRole, classification, requirements);
    }

    private RetrievalDtos.Result emptyRetrieval(RetrievalDtos.Filter filter, RetrievalDtos.Lane lane) {
        return new RetrievalDtos.Result(List.of(), lane, 1, 0, 0, 0, 0, 0, 0, filter);
    }

    private Long persistRetrieval(RetrievalDtos.Result retrieval,
                                  String sessionUuid,
                                  String traceId,
                                  String redactedQuery,
                                  String rewritten) {
        if (retrieval.evidence().isEmpty() && retrieval.vectorHits() == 0 && retrieval.lexicalHits() == 0) {
            return null;
        }

        Long runId = audit.saveRetrievalRun(new AuditDtos.RetrievalRunRecord(
                sessionUuid, traceId, redactedQuery, rewritten,
                retrieval.lane().name(), retrieval.attempt(),
                retrieval.filter().describe(),
                retrieval.vectorHits(), retrieval.lexicalHits(),
                retrieval.fusedHits(), retrieval.evidence().size(),
                retrieval.topSimilarity(), retrieval.confidence(), retrieval.durationMs()));

        if (runId != null) {
            List<AuditDtos.EvidenceRow> rows = new ArrayList<>();
            for (RetrievalDtos.Ranked ranked : retrieval.evidence()) {
                RetrievalDtos.Chunk chunk = ranked.chunk();
                rows.add(new AuditDtos.EvidenceRow(
                        chunk.id(), chunk.documentCode(), chunk.documentTitle(),
                        chunk.section(), chunk.page(), CitationBuilder.handleFor(ranked.rank()),
                        chunk.vectorScore(), chunk.lexicalScore(), ranked.rerankScore(),
                        ranked.rank(), true, excerpt(chunk.content())));
            }
            audit.saveEvidence(runId, rows);
        }
        return runId;
    }

    private void persistValidation(String sessionUuid, String traceId,
                                   Attempt attempt, boolean triggeredRepair) {
        audit.saveValidation(new AuditDtos.ValidationRow(
                sessionUuid, traceId, null, attempt.attemptNo(),
                attempt.verdict().passed() ? "PASSED" : "FAILED",
                attempt.verdict().gatesAsString(),
                attempt.verdict().citationCoverage(),
                attempt.verdict().confidence(),
                Map.of("detail", attempt.verdict().detail()),
                triggeredRepair, null));
    }

    private void recordTurn(String sessionUuid, String userText, String answerText,
                            Long answerId, String traceId) {
        sessions.appendMessage(sessionUuid, "USER", userText, null, traceId);
        sessions.appendMessage(sessionUuid, "ASSISTANT", answerText, answerId, traceId);
        memory.append(sessionUuid, "USER", userText, null);
        memory.append(sessionUuid, "ASSISTANT", answerText, null);
        sessions.touch(sessionUuid);
    }

    private List<Map<String, Object>> buildEvidencePreview(RetrievalDtos.Result retrieval) {
        List<Map<String, Object>> preview = new ArrayList<>();
        for (RetrievalDtos.Ranked ranked : retrieval.evidence()) {
            RetrievalDtos.Chunk chunk = ranked.chunk();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("handle", CitationBuilder.handleFor(ranked.rank()));
            row.put("documentCode", chunk.documentCode());
            row.put("documentTitle", chunk.documentTitle());
            row.put("section", chunk.section());
            row.put("page", chunk.page());
            row.put("relevance", Math.round(HybridRetriever.relevance(chunk) * 1000) / 1000.0);
            row.put("excerpt", excerpt(chunk.content()));
            preview.add(row);
        }
        return preview;
    }

    private static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String collapsed = content.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 400 ? collapsed : collapsed.substring(0, 400) + "...";
    }

    private void emit(Consumer<OrchestrationDtos.StreamEvent> progress, String type, Object payload) {
        if (progress != null) {
            progress.accept(new OrchestrationDtos.StreamEvent(type, payload));
        }
    }

    private static String canonicalToolFamily(
            OrchestrationDtos.ToolTarget target) {
        return switch (target) {
            case FLIGHT_SEARCH, SEAT_MAP, MEAL_AVAILABILITY, EXCESS_BAGGAGE_QUOTE ->
                    "FlightSearchTool";
            case BOOKING_CREATE, BOOKING_LOOKUP, REFUND_QUOTE, REFUND_CASES,
                    REFUND_STATUS, OPERATIONAL_DECISIONS, DISRUPTION_RECOVERY,
                    ESCALATION_QUEUE ->
                    "BookingManagementTool";
            case OPERATIONAL_DATA_QUERY -> "OperationalDataAgent";
            case FLIGHT_STATUS, CHECK_IN -> "CheckInStatusTool";
            case NONE -> "";
        };
    }

    static boolean shouldReturnModelCapacity(
            boolean validationPassed,
            String degradedReason) {
        return !validationPassed
                && degradedReason != null
                && ("MODEL_CAPACITY".equalsIgnoreCase(degradedReason)
                    || "RATE_LIMIT".equalsIgnoreCase(degradedReason)
                    || "HTTP_429".equalsIgnoreCase(degradedReason));
    }

    record Parsed(String answer, List<String> followups) { }

    private record Attempt(String answer,
                           List<String> followups,
                           AnswerEvaluator.Verdict verdict,
                           ChatDtos.ChatResult result,
                           int attemptNo) { }
}
