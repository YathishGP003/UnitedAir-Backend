package com.unitedair.ai.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.llm.ChatGateway;
import com.unitedair.ai.shared.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Uses the hosted model to understand each normal turn and propose an allowlisted plan.
 * It cannot execute a tool, generate SQL, or create an escalation. Deterministic data is
 * retained for trusted entity extraction and safety/authorization enforcement.
 */
@Component
public class AdaptiveRoutePlanner {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveRoutePlanner.class);
    private static final Set<String> SEMANTIC_PLAN_FIELDS = Set.of(
            "scope", "intent", "tools", "useHistory", "topics",
            "missingParameters", "actionDecision", "confidence",
            "ambiguity", "rationale");
    private static final Pattern SQL_TEXT = Pattern.compile(
            "(?i).*\\b(?:select|insert|update|delete|drop|alter|truncate|call)\\b"
                    + ".*\\b(?:from|into|table|set|view|procedure)\\b.*",
            Pattern.DOTALL);
    private static final String SEMANTIC_SYSTEM_PROMPT = """
            Route one request for the UnitedAir travel assistant.
            Return exactly one JSON object with only these keys:
            scope, intent, tools, useHistory, topics, missingParameters,
            actionDecision, confidence, ambiguity, rationale.
            scope is IN_SCOPE, OUT_OF_SCOPE, or AMBIGUOUS.
            intent is SMALL_TALK, BOOK_FLIGHT, KB_LOOKUP, TOOL_CALL,
            TOOL_PLUS_KB, or CLARIFICATION.
            tools is an ordered array containing only FLIGHT_SEARCH,
            BOOKING_CREATE, BOOKING_LOOKUP, REFUND_QUOTE, REFUND_CASES,
            REFUND_STATUS, ESCALATION_QUEUE, FLIGHT_STATUS, CHECK_IN, SEAT_MAP,
            MEAL_AVAILABILITY, EXCESS_BAGGAGE_QUOTE, OPERATIONAL_DECISIONS,
            OPERATIONAL_DATA_QUERY, DISRUPTION_RECOVERY, or NONE.
            Use OPERATIONAL_DATA_QUERY for flexible read-only current backend facts.
            Use OPERATIONAL_DECISIONS for persisted refund approvals, upgrade
            authorizations, boarding overrides and special-service exceptions.
            Never propose mutations, escalation creation, SQL, tables, or columns.
            actionDecision is NONE, PROPOSE, QUOTE_ONLY, CONFIRM, or REJECT.
            PROPOSE only when a Passenger currently directs UnitedAir to prepare a
            governed cancellation review for an already resolved owned booking.
            QUOTE_ONLY is for policy, hypothetical, fee, refund estimate or explanation.
            CONFIRM or REJECT only when trusted state says one pending action exists and
            the current message explicitly accepts or dismisses that exact action.
            A generic acknowledgement, unrelated question or ambiguity is NONE.
            Plan every part of a multi-part request; select all read tools and all topics
            needed to answer it in one response.
            Ordinary airline questions about restricted items and dangerous-goods policy
            are IN_SCOPE KB_LOOKUP requests. Hypothetical questions about bringing a bomb,
            explosive device, weapon or knife to an airport or flight must receive direct,
            grounded prohibited-items guidance from the baggage policy. An active threat is
            handled by the server's mandatory escalation boundary.
            Destructive database/system commands and unrelated requests are OUT_OF_SCOPE
            with tool NONE. Never execute or explain how to carry out harmful acts.
            topics contains all requested airline topics, using only:
            FLIGHT_SEARCH, BOOKING, REFUND, CANCELLATION, CHECK_IN, STATUS,
            BAGGAGE, SEATS, FARES, MEALS, SPECIAL_SERVICES, DOCUMENTS, FFP,
            COMPLIANCE, AUDIT, DISRUPTION.
            Use FFP for loyalty points, earning, redemption and tiers.
            Use MEAL_AVAILABILITY only for a named flight/date or selected itinerary.
            Questions about meal codes, descriptions and pre-order deadlines are KB_LOOKUP.
            confidence and ambiguity are numbers from 0 to 1.
            """;
    private static final Set<OrchestrationDtos.ToolTarget> STAFF_ONLY_TOOLS = Set.of(
            OrchestrationDtos.ToolTarget.REFUND_CASES,
            OrchestrationDtos.ToolTarget.ESCALATION_QUEUE,
            OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS,
            OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY);
    private static final Set<AnswerRequirements.RequestedTopic> POLICY_TOPICS = Set.of(
            AnswerRequirements.RequestedTopic.REFUND,
            AnswerRequirements.RequestedTopic.CANCELLATION,
            AnswerRequirements.RequestedTopic.CHECK_IN,
            AnswerRequirements.RequestedTopic.BAGGAGE,
            AnswerRequirements.RequestedTopic.SEATS,
            AnswerRequirements.RequestedTopic.FARES,
            AnswerRequirements.RequestedTopic.MEALS,
            AnswerRequirements.RequestedTopic.SPECIAL_SERVICES,
            AnswerRequirements.RequestedTopic.DOCUMENTS,
            AnswerRequirements.RequestedTopic.FFP,
            AnswerRequirements.RequestedTopic.COMPLIANCE,
            AnswerRequirements.RequestedTopic.DISRUPTION);
    private static final Set<OrchestrationDtos.ToolTarget> PNR_SCOPED_TOOLS = Set.of(
            OrchestrationDtos.ToolTarget.BOOKING_LOOKUP,
            OrchestrationDtos.ToolTarget.REFUND_QUOTE,
            OrchestrationDtos.ToolTarget.REFUND_STATUS,
            OrchestrationDtos.ToolTarget.CHECK_IN,
            OrchestrationDtos.ToolTarget.DISRUPTION_RECOVERY);
    private static final Set<OrchestrationDtos.ToolTarget> SAFE_DEGRADED_READ_TOOLS =
            Set.of(
                    OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                    OrchestrationDtos.ToolTarget.BOOKING_LOOKUP,
                    OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                    OrchestrationDtos.ToolTarget.REFUND_CASES,
                    OrchestrationDtos.ToolTarget.REFUND_STATUS,
                    OrchestrationDtos.ToolTarget.ESCALATION_QUEUE,
                    OrchestrationDtos.ToolTarget.FLIGHT_STATUS,
                    OrchestrationDtos.ToolTarget.CHECK_IN,
                    OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS,
                    OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY);

    private final ChatGateway chatGateway;

    public AdaptiveRoutePlanner(ChatGateway chatGateway) {
        this.chatGateway = chatGateway;
    }

    public ValidatedRoute plan(
            RoutingContext context,
            SemanticRoutingPolicy.Eligibility eligibility) {
        if (context.deterministic().intent()
                == OrchestrationDtos.Intent.ESCALATION) {
            return ValidatedRoute.deterministic(
                    context, "MANDATORY_ESCALATION");
        }
        if (context.deterministic().intent()
                == OrchestrationDtos.Intent.KB_LOOKUP
                && "SAFETY_POLICY".equals(
                        context.deterministic().escalationReason())) {
            return ValidatedRoute.deterministic(
                    context, "MANDATORY_SAFETY_POLICY");
        }
        if (eligibility == null || !eligibility.refine()) {
            return ValidatedRoute.deterministic(context, null);
        }
        ChatDtos.ChatResult result = chatGateway.completeDirect(
                SEMANTIC_SYSTEM_PROMPT,
                context.history(),
                "Current redacted request: " + context.currentQuery()
                        + "\nDeterministic candidate: "
                        + context.deterministic().intent() + "/"
                        + context.deterministic().tool()
                        + "\nActor role: " + context.actorRole()
                        + "\nHistory relevant: " + context.historyRelevant()
                        + "\nTrusted conversational state: "
                        + Json.write(context.trustedState())
                        + "\nTrusted server-side slots already present (values hidden): "
                        + "pnr=" + (context.deterministic().pnr() != null)
                        + ", flightNo=" + (context.deterministic().flightNo() != null)
                        + ", origin=" + (context.deterministic().origin() != null)
                        + ", destination="
                        + (context.deterministic().destination() != null)
                        + ", travelDate="
                        + (context.deterministic().travelDate() != null)
                        + "\nA redaction token is not a missing value when its trusted "
                        + "server-side slot is true. Passenger requests must never use "
                        + "REFUND_CASES, ESCALATION_QUEUE, OPERATIONAL_DECISIONS, or "
                        + "OPERATIONAL_DATA_QUERY.",
                "");
        if (result == null || !result.live()
                || result.text() == null || result.text().isBlank()) {
            return safeHostedFailure(
                    context,
                    result == null || result.degradedReason() == null
                            ? "SEMANTIC_PLANNER_UNAVAILABLE"
                            : result.degradedReason());
        }
        SemanticPlan proposed = parseSemantic(result.text());
        if (!validSemantic(proposed, result.text())) {
            return safeHostedFailure(context, "INVALID_SEMANTIC_PLAN");
        }
        if ((proposed.confidence() < 0.55 || proposed.ambiguity() > 0.70)
                && proposed.scope().equalsIgnoreCase("IN_SCOPE")) {
            return safeHostedFailure(
                    context, "LOW_CONFIDENCE_SEMANTIC_PLAN");
        }

        ValidatedRoute.Scope scope = ValidatedRoute.Scope.valueOf(
                proposed.scope().trim().toUpperCase(Locale.ROOT));
        if (scope == ValidatedRoute.Scope.AMBIGUOUS
                && context.deterministic().pnr() != null
                && proposed.missingParameters().stream()
                        .filter(value -> value != null)
                        .map(value -> value.replace("_", "")
                                .replace("-", "")
                                .toLowerCase(Locale.ROOT))
                        .anyMatch(value -> value.equals("pnr")
                                || value.equals("bookingreference")
                                || value.equals("recordlocator"))) {
            return safeHostedFailure(
                    context, "HOSTED_IGNORED_TRUSTED_PNR");
        }
        OrchestrationDtos.Intent intent = parseIntent(proposed.intent());
        ValidatedRoute.ActionDecision actionDecision =
                parseActionDecision(proposed.actionDecision());
        if (actionDecision == null) {
            return safeHostedFailure(
                    context, "INVALID_SEMANTIC_PLAN");
        }
        ArrayList<OrchestrationDtos.ToolTarget> tools = new ArrayList<>();
        for (String value : proposed.tools()) {
            OrchestrationDtos.ToolTarget target = parseTool(value);
            if (target == null) {
                return safeHostedFailure(
                        context, "INVALID_SEMANTIC_PLAN");
            }
            if (target != OrchestrationDtos.ToolTarget.NONE && !tools.contains(target)) {
                tools.add(target);
            }
        }
        if (context.deterministic().tool()
                == OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS) {
            /*
             * The exact audit worker has a narrower authorization and schema than
             * the flexible data agent. A hosted proposal may add semantic context,
             * but it cannot replace or disable this trusted specialized read.
             */
            tools.remove(OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY);
            tools.remove(OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS);
            tools.addFirst(OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS);
            intent = OrchestrationDtos.Intent.TOOL_CALL;
        }
        if (tools.contains(OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY)) {
            tools.removeIf(target ->
                    target == OrchestrationDtos.ToolTarget.REFUND_CASES
                            || target == OrchestrationDtos.ToolTarget.ESCALATION_QUEUE
                            || target
                                == OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS);
        }
        if (context.actorRole()
                == com.unitedair.ai.identity.Role.PASSENGER) {
            tools.removeIf(STAFF_ONLY_TOOLS::contains);
        }
        OrchestrationDtos.ToolTarget pendingTool =
                trustedPendingTool(context.trustedState());
        if (context.trustedState().suppliedTrustedSlot()
                && pendingTool != null
                && pendingTool == context.deterministic().tool()
                && !tools.contains(pendingTool)) {
            if (PNR_SCOPED_TOOLS.contains(pendingTool)) {
                tools.remove(OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
            }
            tools.addFirst(pendingTool);
            intent = context.deterministic().intent();
        }
        boolean pendingAction = "PENDING".equalsIgnoreCase(
                context.trustedState().pendingActionStatus());
        if ((actionDecision == ValidatedRoute.ActionDecision.CONFIRM
                || actionDecision == ValidatedRoute.ActionDecision.REJECT)
                && !pendingAction) {
            actionDecision = ValidatedRoute.ActionDecision.NONE;
        }
        if (actionDecision == ValidatedRoute.ActionDecision.PROPOSE
                && (context.actorRole()
                        != com.unitedair.ai.identity.Role.PASSENGER
                    || !context.trustedState().ownedBookingResolved()
                    || !tools.contains(
                            OrchestrationDtos.ToolTarget.REFUND_QUOTE))) {
            actionDecision = ValidatedRoute.ActionDecision.QUOTE_ONLY;
        }
        if (tools.contains(OrchestrationDtos.ToolTarget.CHECK_IN)
                || tools.contains(OrchestrationDtos.ToolTarget.REFUND_QUOTE)) {
            // These compound tools already return the governed booking view needed
            // alongside eligibility/quote data. Keeping BOOKING_LOOKUP would consume
            // the bounded tool budget twice and starve another requested live tool.
            tools.remove(OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
        }
        if (tools.contains(OrchestrationDtos.ToolTarget.MEAL_AVAILABILITY)
                && context.deterministic().tool()
                    != OrchestrationDtos.ToolTarget.MEAL_AVAILABILITY
                && context.deterministic().flightNo() == null
                && context.deterministic().travelDate() == null) {
            // Meal codes, descriptions and order deadlines are policy. The live
            // availability tool is flight-instance scoped and must never receive
            // an invented or blank itinerary merely because a policy turn names
            // VGML/KSML.
            tools.remove(OrchestrationDtos.ToolTarget.MEAL_AVAILABILITY);
            if (tools.isEmpty()
                    && context.deterministic().intent()
                        == OrchestrationDtos.Intent.KB_LOOKUP) {
                intent = OrchestrationDtos.Intent.KB_LOOKUP;
            }
        }
        if (context.deterministic().tool()
                == OrchestrationDtos.ToolTarget.REFUND_STATUS
                && (tools.size() != 1
                    || tools.getFirst()
                        != OrchestrationDtos.ToolTarget.REFUND_STATUS)) {
            return safeHostedFailure(
                    context, "PROTECTED_REFUND_STATUS_ROUTE");
        }
        if (scope != ValidatedRoute.Scope.IN_SCOPE) {
            tools.clear();
            intent = scope == ValidatedRoute.Scope.OUT_OF_SCOPE
                    ? OrchestrationDtos.Intent.OUT_OF_SCOPE
                    : OrchestrationDtos.Intent.CLARIFICATION;
        } else if ((intent == OrchestrationDtos.Intent.TOOL_CALL
                || intent == OrchestrationDtos.Intent.TOOL_PLUS_KB)
                && tools.isEmpty()
                && actionDecision != ValidatedRoute.ActionDecision.CONFIRM
                && actionDecision != ValidatedRoute.ActionDecision.REJECT) {
            return safeHostedFailure(
                    context, "INVALID_SEMANTIC_PLAN");
        }

        ArrayList<String> effectiveMissing = new ArrayList<>(
                proposed.missingParameters());
        if (scope == ValidatedRoute.Scope.IN_SCOPE
                && tools.size() == 1
                && PNR_SCOPED_TOOLS.contains(tools.getFirst())) {
            // These tools resolve itinerary/date/fare from the owned booking. A model
            // may conservatively request those fields, but they are not arguments in
            // the tool contract and must not block a valid PNR-scoped call.
            effectiveMissing.removeIf(value -> !isPnrParameter(value));
            if (context.deterministic().pnr() != null) {
                effectiveMissing.removeIf(AdaptiveRoutePlanner::isPnrParameter);
            } else if (effectiveMissing.stream()
                    .noneMatch(AdaptiveRoutePlanner::isPnrParameter)) {
                effectiveMissing.add("pnr");
            }
        }
        if (scope == ValidatedRoute.Scope.IN_SCOPE
                && tools.contains(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH)) {
            if (context.deterministic().origin() == null) {
                effectiveMissing.add("origin");
            }
            if (context.deterministic().destination() == null) {
                effectiveMissing.add("destination");
            }
            if (context.deterministic().travelDate() == null) {
                effectiveMissing.add("travelDate");
            }
        }
        if (scope == ValidatedRoute.Scope.IN_SCOPE
                && tools.contains(OrchestrationDtos.ToolTarget.SEAT_MAP)
                && context.deterministic().flightNo() == null) {
            if (tools.contains(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH)) {
                // SEARCH_FLIGHTS already returns live seatsAvailable for every fare.
                // A full seat map needs one selected flight and must not receive a
                // blank flight number while the user is still comparing departures.
                tools.remove(OrchestrationDtos.ToolTarget.SEAT_MAP);
            } else {
                effectiveMissing.add("flightnumber");
            }
        }
        effectiveMissing = new ArrayList<>(effectiveMissing.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());
        if (!effectiveMissing.isEmpty()
                && (intent == OrchestrationDtos.Intent.TOOL_CALL
                    || intent == OrchestrationDtos.Intent.TOOL_PLUS_KB)) {
            intent = OrchestrationDtos.Intent.CLARIFICATION;
            scope = ValidatedRoute.Scope.AMBIGUOUS;
            tools.clear();
        }

        Set<AnswerRequirements.RequestedTopic> topics = parseTopics(proposed.topics());
        Set<AnswerRequirements.RequestedTopic> deterministicTopics =
                AnswerRequirements.from(
                        context.currentQuery(), context.deterministic()).topics();
        if (scope == ValidatedRoute.Scope.IN_SCOPE) {
            topics.addAll(deterministicTopics);
        } else {
            topics.clear();
        }
        if (scope == ValidatedRoute.Scope.IN_SCOPE
                && context.deterministic().tool()
                    == OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS) {
            /*
             * Hosted topic labels such as REFUND or SPECIAL_SERVICES describe
             * decision filters here. They are not requests for those complete
             * policy domains.
             */
            topics.clear();
            topics.add(AnswerRequirements.RequestedTopic.AUDIT);
        }
        if (scope == ValidatedRoute.Scope.IN_SCOPE
                && intent == OrchestrationDtos.Intent.TOOL_CALL
                && topics.stream().anyMatch(POLICY_TOPICS::contains)) {
            intent = OrchestrationDtos.Intent.TOOL_PLUS_KB;
        }
        OrchestrationDtos.ToolTarget primaryTool = tools.isEmpty()
                ? OrchestrationDtos.ToolTarget.NONE : tools.getFirst();
        OrchestrationDtos.Classification primary =
                copyClassification(context.deterministic(), intent, primaryTool,
                        proposed.confidence(), proposed.rationale(),
                        effectiveMissing);
        return new ValidatedRoute(
                primary,
                tools,
                scope,
                Boolean.TRUE.equals(proposed.useHistory())
                        && context.historyRelevant(),
                ValidatedRoute.Source.HOSTED_SEMANTIC,
                proposed.confidence(),
                proposed.ambiguity(),
                topics,
                actionDecision,
                proposed.rationale(),
                null);
    }

    private ValidatedRoute safeHostedFailure(
            RoutingContext context,
            String reason) {
        if (isExactSafeRead(context.deterministic())) {
            return safeReadFallbackRoute(context, reason);
        }
        OrchestrationDtos.Classification clarification = copyClassification(
                context.deterministic(),
                OrchestrationDtos.Intent.CLARIFICATION,
                OrchestrationDtos.ToolTarget.NONE,
                0.0,
                "The hosted semantic plan was unavailable or could not be validated.",
                focusedMissing(context.deterministic()));
        return new ValidatedRoute(
                clarification,
                List.of(),
                ValidatedRoute.Scope.AMBIGUOUS,
                false,
                ValidatedRoute.Source.DETERMINISTIC,
                0.0,
                1.0,
                Set.of(),
                ValidatedRoute.ActionDecision.NONE,
                clarification.rationale(),
                reason);
    }

    private ValidatedRoute safeReadFallbackRoute(
            RoutingContext context,
            String reason) {
        OrchestrationDtos.Classification deterministic = context.deterministic();
        AnswerRequirements requirements = AnswerRequirements.from(
                context.currentQuery(), deterministic);
        ArrayList<OrchestrationDtos.ToolTarget> tools = new ArrayList<>();
        tools.add(deterministic.tool());

        if (deterministic.pnr() != null) {
            if ((requirements.topics().contains(
                            AnswerRequirements.RequestedTopic.BOOKING)
                        || requirements.topics().contains(
                            AnswerRequirements.RequestedTopic.BAGGAGE))
                    && !tools.contains(
                            OrchestrationDtos.ToolTarget.BOOKING_LOOKUP)) {
                tools.add(OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
            }
            if ((requirements.topics().contains(
                            AnswerRequirements.RequestedTopic.CANCELLATION)
                        || requirements.topics().contains(
                            AnswerRequirements.RequestedTopic.REFUND))
                    && deterministic.tool()
                        != OrchestrationDtos.ToolTarget.REFUND_STATUS
                    && !tools.contains(
                            OrchestrationDtos.ToolTarget.REFUND_QUOTE)) {
                tools.add(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
            }
            if (requirements.topics().contains(
                        AnswerRequirements.RequestedTopic.CHECK_IN)
                    && !tools.contains(
                            OrchestrationDtos.ToolTarget.CHECK_IN)) {
                tools.add(OrchestrationDtos.ToolTarget.CHECK_IN);
            }
        }
        if (requirements.topics().contains(
                    AnswerRequirements.RequestedTopic.STATUS)
                && deterministic.flightNo() != null
                && deterministic.travelDate() != null
                && !tools.contains(
                        OrchestrationDtos.ToolTarget.FLIGHT_STATUS)) {
            tools.add(OrchestrationDtos.ToolTarget.FLIGHT_STATUS);
        }
        if (tools.contains(OrchestrationDtos.ToolTarget.REFUND_QUOTE)) {
            // The cancellation quote operation also emits the governed booking view.
            tools.remove(OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
        }

        OrchestrationDtos.Intent intent = deterministic.intent();
        if (intent == OrchestrationDtos.Intent.TOOL_CALL
                && requirements.topics().stream().anyMatch(POLICY_TOPICS::contains)) {
            intent = OrchestrationDtos.Intent.TOOL_PLUS_KB;
        }
        OrchestrationDtos.Classification primary = copyClassification(
                deterministic,
                intent,
                tools.getFirst(),
                deterministic.confidence(),
                deterministic.rationale(),
                deterministic.missingParameters());
        return new ValidatedRoute(
                primary,
                tools,
                ValidatedRoute.Scope.IN_SCOPE,
                context.historyRelevant(),
                ValidatedRoute.Source.DETERMINISTIC,
                deterministic.confidence(),
                0.0,
                requirements.topics(),
                ValidatedRoute.ActionDecision.NONE,
                deterministic.rationale(),
                reason);
    }

    private static List<String> focusedMissing(
            OrchestrationDtos.Classification deterministic) {
        if (deterministic.intent() == OrchestrationDtos.Intent.CLARIFICATION
                && deterministic.missingParameters() != null
                && !deterministic.missingParameters().isEmpty()) {
            return deterministic.missingParameters();
        }
        return List.of("request");
    }

    private static boolean isExactSafeRead(
            OrchestrationDtos.Classification classification) {
        if (classification == null
                || classification.confidence() < 0.90
                || (classification.intent()
                        != OrchestrationDtos.Intent.TOOL_CALL
                    && classification.intent()
                        != OrchestrationDtos.Intent.TOOL_PLUS_KB)
                || !SAFE_DEGRADED_READ_TOOLS.contains(classification.tool())
                || (classification.missingParameters() != null
                    && !classification.missingParameters().isEmpty())) {
            return false;
        }
        return switch (classification.tool()) {
            case FLIGHT_SEARCH -> classification.origin() != null
                    && classification.destination() != null
                    && classification.travelDate() != null;
            case BOOKING_LOOKUP, REFUND_QUOTE, REFUND_STATUS, CHECK_IN ->
                    classification.pnr() != null;
            case FLIGHT_STATUS -> classification.flightNo() != null
                    && classification.travelDate() != null;
            case REFUND_CASES, ESCALATION_QUEUE, OPERATIONAL_DECISIONS,
                    OPERATIONAL_DATA_QUERY -> true;
            default -> false;
        };
    }

    private static boolean isPnrParameter(String value) {
        if (value == null) {
            return false;
        }
        String canonical = value.replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
        return canonical.equals("pnr")
                || canonical.equals("bookingreference")
                || canonical.equals("recordlocator");
    }

    private static OrchestrationDtos.ToolTarget trustedPendingTool(
            TrustedConversationState state) {
        if (state == null || state.pendingOperation() == null) {
            return null;
        }
        try {
            return OrchestrationDtos.ToolTarget.valueOf(
                    state.pendingOperation().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private SemanticPlan parseSemantic(String text) {
        try {
            JsonNode tree = Json.mapper().readTree(text.trim());
            if (!tree.isObject()) {
                return null;
            }
            Set<String> fields = new HashSet<>();
            tree.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(SEMANTIC_PLAN_FIELDS)) {
                return null;
            }
            return Json.mapper().treeToValue(tree, SemanticPlan.class);
        } catch (Exception invalid) {
            log.debug("Ignoring invalid semantic route response: {}", invalid.toString());
            return null;
        }
    }

    private boolean validSemantic(SemanticPlan plan, String rawText) {
        if (plan == null || SQL_TEXT.matcher(rawText).matches()
                || plan.scope() == null || plan.intent() == null
                || plan.tools() == null || plan.useHistory() == null
                || plan.topics() == null || plan.missingParameters() == null
                || plan.actionDecision() == null
                || plan.confidence() == null || plan.ambiguity() == null
                || plan.rationale() == null
                || plan.confidence() < 0.0 || plan.confidence() > 1.0
                || plan.ambiguity() < 0 || plan.ambiguity() > 1.0) {
            return false;
        }
        try {
            ValidatedRoute.Scope.valueOf(plan.scope().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            return false;
        }
        OrchestrationDtos.Intent intent = parseIntent(plan.intent());
        return intent != null
                && intent != OrchestrationDtos.Intent.ESCALATION
                && parseActionDecision(plan.actionDecision()) != null;
    }

    private OrchestrationDtos.Classification copyClassification(
            OrchestrationDtos.Classification trusted,
            OrchestrationDtos.Intent intent,
            OrchestrationDtos.ToolTarget tool,
            double confidence,
            String rationale,
            List<String> missing) {
        return new OrchestrationDtos.Classification(
                intent, tool, confidence, rationale,
                trusted.origin(), trusted.destination(), trusted.travelDate(),
                trusted.cabin(), trusted.pnr(), trusted.flightNo(),
                trusted.escalationReason(), trusted.categoryHints(),
                missing == null ? List.of() : List.copyOf(missing),
                trusted.documentCodeHints(), trusted.mealCode(),
                trusted.excessBaggageKg(), trusted.routeType());
    }

    private OrchestrationDtos.Intent parseIntent(String value) {
        try {
            return value == null ? null
                    : OrchestrationDtos.Intent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private OrchestrationDtos.ToolTarget parseTool(String value) {
        try {
            return value == null ? null
                    : OrchestrationDtos.ToolTarget.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ValidatedRoute.ActionDecision parseActionDecision(String value) {
        try {
            return value == null ? null
                    : ValidatedRoute.ActionDecision.valueOf(
                            value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private Set<AnswerRequirements.RequestedTopic> parseTopics(List<String> values) {
        Set<AnswerRequirements.RequestedTopic> topics = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String canonical = switch (
                    value.trim().toUpperCase(Locale.ROOT).replace(' ', '_')) {
                case "LOYALTY", "LOYALTY_PROGRAM", "POINTS",
                        "FREQUENT_FLYER", "FREQUENT_FLYER_PROGRAM" -> "FFP";
                case "TRAVEL_DOCUMENT", "TRAVEL_DOCUMENTS", "IDENTIFICATION" ->
                        "DOCUMENTS";
                case "SPECIAL_ASSISTANCE" -> "SPECIAL_SERVICES";
                default -> value.trim().toUpperCase(Locale.ROOT);
            };
            try {
                topics.add(AnswerRequirements.RequestedTopic.valueOf(
                        canonical));
            } catch (RuntimeException invalid) {
                log.debug("Ignoring non-allowlisted semantic topic '{}'.", value);
            }
        }
        return topics;
    }

    private record SemanticPlan(
            String scope,
            String intent,
            List<String> tools,
            Boolean useHistory,
            List<String> topics,
            List<String> missingParameters,
            String actionDecision,
            Double confidence,
            Double ambiguity,
            String rationale) { }
}
