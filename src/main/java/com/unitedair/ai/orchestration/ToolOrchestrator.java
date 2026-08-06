package com.unitedair.ai.orchestration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.commerce.RefundDtos;
import com.unitedair.ai.commerce.RefundOperationsWorker;
import com.unitedair.ai.audit.OperationalDecisionDtos;
import com.unitedair.ai.audit.OperationalDecisionService;
import com.unitedair.ai.shared.TraceContext;
import com.unitedair.ai.shared.UnitedAirProperties;
import com.unitedair.ai.tools.BookingManagementTool;
import com.unitedair.ai.tools.BookingAccess;
import com.unitedair.ai.tools.CheckInStatusTool;
import com.unitedair.ai.tools.DisruptionRecoveryWorker;
import com.unitedair.ai.tools.EscalationTool;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.OperationalFailure;
import com.unitedair.ai.tools.OperationalFailureKind;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.operations.OperationalDataAgent;
import com.unitedair.ai.operations.OperationalQueryDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Orchestrator-Worker pattern of SRS 2.2: dispatches the classified intent to the
 * tool that serves it and returns a uniform result.
 *
 * <p>Dispatch is a switch over the classification, not a model decision. The classifier has
 * already extracted the parameters; this is the step that actually calls the reservation
 * system, and calling the wrong one with the wrong arguments is not something to leave to
 * inference.
 *
 * <p>A failing tool does not fail the turn. The failure is returned in the envelope, the
 * prompt tells the model to say the lookup did not succeed, and the KB lane may still carry
 * a useful policy answer. Losing a live seat count should not cost the passenger the
 * baggage rule they also asked about.
 */
@Component
public class ToolOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ToolOrchestrator.class);

    private final FlightSearchTool flightSearchTool;
    private final BookingManagementTool bookingTool;
    private final CheckInStatusTool checkInTool;
    private final EscalationTool escalationTool;
    private final DisruptionRecoveryWorker recoveryTool;
    private final RefundOperationsWorker refundOperations;
    private final OperationalDecisionService operationalDecisions;
    private final OperationalDataAgent operationalDataAgent;
    private final UnitedAirProperties properties;

    public ToolOrchestrator(FlightSearchTool flightSearchTool,
                            BookingManagementTool bookingTool,
                            CheckInStatusTool checkInTool,
                            EscalationTool escalationTool,
                            DisruptionRecoveryWorker recoveryTool,
                            RefundOperationsWorker refundOperations,
                            OperationalDecisionService operationalDecisions,
                            OperationalDataAgent operationalDataAgent,
                            UnitedAirProperties properties) {
        this.flightSearchTool = flightSearchTool;
        this.bookingTool = bookingTool;
        this.checkInTool = checkInTool;
        this.escalationTool = escalationTool;
        this.recoveryTool = recoveryTool;
        this.refundOperations = refundOperations;
        this.operationalDecisions = operationalDecisions;
        this.operationalDataAgent = operationalDataAgent;
        this.properties = properties;
    }

    public List<ToolDtos.ToolOutcome> execute(OrchestrationDtos.Classification classification,
                                              Role actorRole,
                                              String sessionUuid,
                                              String traceId) {
        return execute(classification, actorRole, null, sessionUuid, traceId);
    }

    public List<ToolDtos.ToolOutcome> execute(OrchestrationDtos.Classification classification,
                                              Role actorRole,
                                              Long userId,
                                              String sessionUuid,
                                              String traceId) {
        return execute(
                classification, actorRole, userId, sessionUuid, traceId, null);
    }

    public List<ToolDtos.ToolOutcome> execute(
            ValidatedRoute route,
            String redactedQuery,
            Role actorRole,
            Long userId,
            String sessionUuid,
            String traceId) {
        List<ToolDtos.ToolOutcome> combined = new ArrayList<>();
        int budget = properties.getRag().getMaxToolCallsPerRequest();
        List<OrchestrationDtos.ToolTarget> targets = route.tools().isEmpty()
                && route.primary().needsTool()
                        ? List.of(route.primary().tool()) : route.tools();
        for (OrchestrationDtos.ToolTarget target : targets) {
            if (combined.size() >= budget) {
                break;
            }
            OrchestrationDtos.Classification classification =
                    withTool(route.primary(), target);
            List<ToolDtos.ToolOutcome> result = execute(
                    classification, actorRole, userId, sessionUuid, traceId,
                    redactedQuery);
            int remaining = budget - combined.size();
            combined.addAll(result.stream().limit(remaining).toList());
        }
        return List.copyOf(combined);
    }

    public List<ToolDtos.ToolOutcome> execute(
                                              OrchestrationDtos.Classification classification,
                                              Role actorRole,
                                              Long userId,
                                              String sessionUuid,
                                              String traceId,
                                              String redactedQuery) {

        // This runs on a worker thread so the orchestrator can fan tools and retrieval out
        // in parallel. TraceContext is a thread-local, so it does not travel with the task
        // and has to be re-established here - otherwise every tool call is logged under a
        // freshly minted trace with no session, and the audit trail silently loses the
        // link between a question and the tools used to answer it (SRS 4.1.4).
        TraceContext.setTraceId(traceId);
        TraceContext.setSessionUuid(sessionUuid);

        List<ToolDtos.ToolOutcome> outcomes = new ArrayList<>();
        int budget = properties.getRag().getMaxToolCallsPerRequest();
        String role = actorRole.name();
        BookingAccess bookingAccess = new BookingAccess(actorRole, userId);

        switch (classification.tool()) {

            case FLIGHT_SEARCH -> {
                if (classification.travelDate() == null) {
                    outcomes.add(missingDateFailure(classification));
                } else {
                    invokeWithRetry(outcomes, budget, () -> flightSearchTool.search(
                            new ToolDtos.FlightSearchRequest(
                                    classification.origin(),
                                    classification.destination(),
                                    classification.travelDate(),
                                    classification.cabin(), 1, 0, 0),
                            role).envelope());
                }
            }

            case BOOKING_LOOKUP -> invokeWithRetry(outcomes, budget, () ->
                    bookingTool.retrieve(classification.pnr(), bookingAccess).envelope());

            case REFUND_QUOTE -> {
                // Two calls: the itinerary the passenger is asking about, and the refund
                // arithmetic for it. Both are needed for the answer to be concrete.
                invokeWithRetry(outcomes, budget, () ->
                        bookingTool.retrieve(classification.pnr(), bookingAccess).envelope());
                if (outcomes.size() < budget) {
                    invokeWithRetry(outcomes, budget, () ->
                            bookingTool.refundQuote(classification.pnr(), bookingAccess).envelope());
                }
            }

            case FLIGHT_STATUS -> invokeWithRetry(outcomes, budget, () ->
                    checkInTool.flightStatus(
                            classification.flightNo(), classification.travelDate(), role).envelope());

            case CHECK_IN -> {
                invokeWithRetry(outcomes, budget, () ->
                        checkInTool.checkInEligibility(classification.pnr(), bookingAccess).envelope());
                if (outcomes.size() < budget) {
                    invokeWithRetry(outcomes, budget, () ->
                            bookingTool.retrieve(classification.pnr(), bookingAccess).envelope());
                }
            }

            case SEAT_MAP -> {
                if (classification.travelDate() == null) {
                    outcomes.add(missingDateFailure(classification));
                } else {
                    invokeWithRetry(outcomes, budget, () ->
                            flightSearchTool.seatMapForFlight(
                                    classification.flightNo(),
                                    classification.travelDate(),
                                    role));
                }
            }

            case MEAL_AVAILABILITY -> {
                if (classification.travelDate() == null) {
                    outcomes.add(missingDateFailure(classification));
                } else {
                    invokeWithRetry(outcomes, budget, () ->
                            flightSearchTool.mealAvailabilityForFlight(
                                    classification.flightNo(),
                                    classification.travelDate(),
                                    classification.mealCode(),
                                    role));
                }
            }

            case EXCESS_BAGGAGE_QUOTE -> invokeWithRetry(outcomes, budget, () ->
                    flightSearchTool.quoteExcessBaggage(
                            classification.routeType(),
                            classification.cabin(),
                            classification.excessBaggageKg(),
                            role));

            case OPERATIONAL_DECISIONS -> invokeWithRetry(outcomes, budget, () -> {
                java.util.Set<OperationalDecisionDtos.DecisionType> types =
                        classification.categoryHints().stream()
                                .filter(value -> value.startsWith("decision:"))
                                .map(value -> value.substring("decision:".length()))
                                .map(OperationalDecisionDtos.DecisionType::valueOf)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                String outcome = classification.categoryHints().stream()
                        .filter(value -> value.startsWith("outcome:"))
                        .map(value -> value.substring("outcome:".length()))
                        .findFirst().orElse(null);
                java.time.Instant from = classification.travelDate() == null
                        ? null : classification.travelDate()
                                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                java.time.Instant to = classification.travelDate() == null
                        ? null : classification.travelDate().plusDays(1)
                                .atStartOfDay(java.time.ZoneOffset.UTC).minusNanos(1).toInstant();
                List<OperationalDecisionDtos.DecisionView> rows =
                        operationalDecisions.search(new OperationalDecisionDtos.DecisionQuery(
                                types, classification.pnr(), null, from, to, outcome));
                return ToolDtos.ToolOutcome.ok(
                        BookingManagementTool.NAME,
                        rows,
                        rows.isEmpty()
                                ? "No operational decisions match those filters."
                                : rows.size() + " operational decision(s) match those filters.",
                        0,
                        java.time.Instant.now(),
                        java.util.Map.of("operation", "QUERY_OPERATIONAL_DECISIONS"));
            });

            case REFUND_CASES -> {
                java.time.Instant dueBefore = classification.travelDate() == null
                        ? null
                        : classification.travelDate().plusDays(1)
                                .atStartOfDay(java.time.ZoneOffset.UTC)
                                .minusNanos(1)
                                .toInstant();
                String status = classification.categoryHints().stream()
                        .filter(value -> value.startsWith("status:"))
                        .map(value -> value.substring("status:".length()))
                        .findFirst()
                        .orElse(null);
                invokeWithRetry(outcomes, budget, () -> refundOperations.listCases(
                        new RefundDtos.RefundCaseQuery(status, dueBefore, 50),
                        bookingAccess));
            }

            case REFUND_STATUS -> invokeWithRetry(outcomes, budget, () ->
                    refundOperations.status(classification.pnr(), bookingAccess));

            case ESCALATION_QUEUE, OPERATIONAL_DATA_QUERY -> {
                if (operationalDataAgent == null) {
                    outcomes.add(ToolDtos.ToolOutcome.failed(
                            "OperationalDataAgent",
                            "Operational data queries are temporarily unavailable.",
                            0, java.time.Instant.now(), java.util.Map.of()));
                    break;
                }
                OperationalDataAgent.AgentResult agentResult =
                        operationalDataAgent.answer(new OperationalDataAgent.AgentRequest(
                                redactedQuery == null ? classification.rationale() : redactedQuery,
                                actorRole,
                                userId,
                                classification.pnr(),
                                budget - outcomes.size()));
                if (agentResult.status() == OperationalDataAgent.Status.EXECUTED) {
                    for (OperationalQueryDtos.OperationalDataResult result
                            : agentResult.results()) {
                        if (outcomes.size() >= budget) {
                            break;
                        }
                        outcomes.add(ToolDtos.ToolOutcome.ok(
                                "OperationalDataAgent",
                                result,
                                result.rowCount() + " verified "
                                        + result.dataset().name().toLowerCase(Locale.ROOT)
                                        + " record(s).",
                                0,
                                result.snapshotAt(),
                                java.util.Map.of(
                                        "operation", "OPERATIONAL_DATA_QUERY",
                                        "dataset", result.dataset().name(),
                                        "queryFingerprint", result.queryFingerprint())));
                    }
                } else {
                    outcomes.add(ToolDtos.ToolOutcome.failed(
                            "OperationalDataAgent",
                            "I could not safely plan that operational data request.",
                            0, java.time.Instant.now(),
                            java.util.Map.of(
                                    "reason", agentResult.degradedReason() == null
                                            ? "UNAVAILABLE" : agentResult.degradedReason())));
                }
            }

            case DISRUPTION_RECOVERY -> invokeWithRetry(outcomes, budget, () ->
                    recoveryTool.invoke(classification.pnr(), bookingAccess));

            case NONE -> { /* KB-only turn; nothing to dispatch */ }

            default -> log.warn("Unhandled tool target {}", classification.tool());
        }

        if (outcomes.size() > budget) {
            // SRS caps tool calls per request; truncating here keeps the cap honest even if
            // a future branch queues more work than it should.
            log.warn("Tool budget of {} exceeded ({} queued); truncating.", budget, outcomes.size());
            return outcomes.subList(0, budget);
        }
        return outcomes;
    }

    private void invokeWithRetry(List<ToolDtos.ToolOutcome> outcomes,
                                 int budget,
                                 Supplier<ToolDtos.ToolOutcome> invocation) {
        if (outcomes.size() >= budget) {
            return;
        }
        ToolDtos.ToolOutcome first = invocation.get();
        outcomes.add(first);

        if (!first.success() && outcomes.size() < budget && isTransient(first.errorMessage())) {
            log.info("Retrying transient {} failure within the tool budget: {}",
                    first.toolName(), first.errorMessage());
            outcomes.add(invocation.get());
        }
    }

    private boolean isTransient(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String lower = errorMessage.toLowerCase(Locale.ROOT);
        return lower.contains("temporar")
                || lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("unavailable")
                || lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("deadlock");
    }

    private static OrchestrationDtos.Classification withTool(
            OrchestrationDtos.Classification source,
            OrchestrationDtos.ToolTarget tool) {
        return new OrchestrationDtos.Classification(
                source.intent(), tool, source.confidence(), source.rationale(),
                source.origin(), source.destination(), source.travelDate(),
                source.cabin(), source.pnr(), source.flightNo(),
                source.escalationReason(), source.categoryHints(),
                source.missingParameters(), source.documentCodeHints(),
                source.mealCode(), source.excessBaggageKg(), source.routeType());
    }

    private ToolDtos.ToolOutcome missingDateFailure(
            OrchestrationDtos.Classification classification) {
        OperationalFailure failure = new OperationalFailure(
                FlightSearchTool.NAME,
                switch (classification.tool()) {
                    case SEAT_MAP -> "seatMap";
                    case MEAL_AVAILABILITY -> "mealAvailability";
                    default -> "searchFlights";
                },
                OperationalFailureKind.VALIDATION,
                "Please provide a travel date before I check live flight data.",
                java.util.Map.of("missingParameter", "travelDate"));
        return ToolDtos.ToolOutcome.failed(
                FlightSearchTool.NAME,
                failure,
                0,
                java.time.Instant.now(),
                java.util.Map.of("travelDate", ""));
    }

    /** Raises an escalation case; used by the pipeline, not by the model. */
    public ToolDtos.EscalationResult escalate(String reason,
                                              String redactedSummary,
                                              String pnr,
                                              Double confidence,
                                              Role actorRole) {
        try {
            return escalationTool.raise(
                    new ToolDtos.EscalationRequest(reason, redactedSummary, pnr, confidence, null),
                    actorRole.name()).data();
        } catch (Exception e) {
            // If the escalation record cannot be written the user must still be told to
            // seek a human; losing the row is an audit problem, not a reason to answer.
            log.error("Escalation could not be recorded: {}", e.toString());
            return null;
        }
    }
}
