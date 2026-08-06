package com.unitedair.ai.orchestration;

import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.llm.ChatGateway;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdaptiveRoutePlannerTest {

    private final ChatGateway gateway = mock(ChatGateway.class);
    private final AdaptiveRoutePlanner planner = new AdaptiveRoutePlanner(gateway);

    @Test
    void modelCapacityDoesNotLetAmbiguousFreeTextUseALexicalKnowledgeRoute() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(new ChatDtos.ChatResult(
                        "", 0, 0, "github-models",
                        ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                        "MODEL_CAPACITY"));
        var context = new RoutingContext(
                "help me with that thing",
                "help me with that thing",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, uncertain());

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "LIVE_MODEL_FIRST"));

        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.primary().tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.degradationReason()).isEqualTo("MODEL_CAPACITY");
    }

    @Test
    void modelCapacityMayKeepAnExactCompleteReadOnlyToolRoute() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(new ChatDtos.ChatResult(
                        "", 0, 0, "github-models",
                        ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                        "MODEL_CAPACITY"));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.BOOKING_LOOKUP,
                0.99, null, null, null, "N7QTX2", null);
        var context = new RoutingContext(
                "Show booking [AIR-PNR-REDACTED].",
                "Show booking [AIR-PNR-REDACTED].",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "LIVE_MODEL_FIRST"));

        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tools())
                .containsExactly(OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
        assertThat(result.degradationReason()).isEqualTo("MODEL_CAPACITY");
    }

    @Test
    void invalidHostedJsonForAmbiguousTextBecomesFocusedClarification() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("<html>upstream proxy error</html>"));
        var context = new RoutingContext(
                "do it for that one",
                "do it for that one",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, uncertain());

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "LIVE_MODEL_FIRST"));

        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.primary().tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.degradationReason())
                .isEqualTo("INVALID_SEMANTIC_PLAN");
    }

    @Test
    void invalidHostedPlanKeepsEverySafeReadNeededByACompoundPnrQuestion() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("<html>upstream proxy error</html>"));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.FLIGHT_STATUS,
                0.94,
                null,
                null,
                LocalDate.of(2026, 8, 18),
                "H3PL8M",
                "UA404");
        var context = new RoutingContext(
                "What is my checked baggage allowance, and is my flight on time? "
                        + "My PNR is [AIR-PNR-REDACTED].",
                "What is my checked baggage allowance, and is my flight on time? "
                        + "My PNR is [AIR-PNR-REDACTED].",
                List.of(),
                false,
                TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER,
                1L,
                deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "LIVE_MODEL_FIRST"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.DETERMINISTIC);
        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tools()).containsExactly(
                OrchestrationDtos.ToolTarget.FLIGHT_STATUS,
                OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
        assertThat(result.topics()).contains(
                AnswerRequirements.RequestedTopic.STATUS,
                AnswerRequirements.RequestedTopic.BAGGAGE);
        assertThat(result.degradationReason())
                .isEqualTo("INVALID_SEMANTIC_PLAN");
    }

    @Test
    void validatedPlanCanSelectOperationalDataAndMultipleTopics() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_CALL",
                         "tools":["OPERATIONAL_DATA_QUERY","ESCALATION_QUEUE"],
                         "useHistory":false,
                         "topics":["REFUND","AUDIT"],
                         "missingParameters":[],
                         "actionDecision":"NONE",
                         "confidence":0.96,"ambiguity":0.02,
                         "rationale":"Live operational summary"}
                        """));
        var context = new RoutingContext(
                "pending refunds and open escalations",
                "pending refunds and open escalations",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.AIRLINE_STAFF, 2L, uncertain());

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, true, "MULTI_TOPIC_OR_OPERATIONAL"));

        assertThat(result.scope()).isEqualTo(ValidatedRoute.Scope.IN_SCOPE);
        assertThat(result.tools()).containsExactly(
                OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY);
        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.HOSTED_SEMANTIC);
    }

    @Test
    void genericHostedDataPlanCannotReplaceExactDecisionAuditWorker() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_PLUS_KB",
                         "tools":["OPERATIONAL_DATA_QUERY"],"useHistory":false,
                         "topics":["AUDIT","COMPLIANCE"],"missingParameters":[],
                         "actionDecision":"NONE","confidence":0.98,"ambiguity":0.01,
                         "rationale":"Query operational audit data"}
                        """));
        var deterministic = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS,
                0.97,
                "Authorized operational decision audit request",
                null, null, null, null, null, null, null,
                List.of(
                        "decision:REFUND_APPROVAL",
                        "decision:UPGRADE_AUTHORIZATION",
                        "decision:BOARDING_OVERRIDE",
                        "decision:SPECIAL_SERVICE_EXCEPTION"),
                List.of(),
                Set.of());
        String question = "Show the audit requirements for refund approvals, "
                + "upgrade authorizations, boarding overrides and "
                + "special-service exceptions.";
        var context = new RoutingContext(
                question, question, List.of(), false,
                TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.AIRLINE_STAFF,
                2L,
                deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, true, "MULTI_TOPIC_OR_OPERATIONAL"));

        assertThat(result.scope()).isEqualTo(ValidatedRoute.Scope.IN_SCOPE);
        assertThat(result.primary().tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS);
        assertThat(result.tools())
                .containsExactly(OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS);
        assertThat(result.primary().categoryHints())
                .containsExactlyElementsOf(deterministic.categoryHints());
    }

    @Test
    void hostedKnowledgeLabelCannotDisableExactDecisionAuditWorker() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"KB_LOOKUP",
                         "tools":["OPERATIONAL_DECISIONS"],"useHistory":false,
                         "topics":["AUDIT","REFUND","SPECIAL_SERVICES"],
                         "missingParameters":[],
                         "actionDecision":"NONE","confidence":1.0,"ambiguity":0.0,
                         "rationale":"Query persisted operational decision audit records"}
                        """));
        var deterministic = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS,
                0.97,
                "Authorized operational decision audit request",
                null, null, null, null, null, null, null,
                List.of(
                        "decision:REFUND_APPROVAL",
                        "decision:UPGRADE_AUTHORIZATION",
                        "decision:BOARDING_OVERRIDE",
                        "decision:SPECIAL_SERVICE_EXCEPTION"),
                List.of(),
                Set.of());
        String question = "Show the audit requirements for refund approvals, "
                + "upgrade authorizations, boarding overrides and "
                + "special-service exceptions.";
        var context = new RoutingContext(
                question, question, List.of(), false,
                TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.AIRLINE_STAFF,
                2L,
                deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, true, "MULTI_TOPIC_OR_OPERATIONAL"));

        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.primary().tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS);
        assertThat(result.tools())
                .containsExactly(OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS);
        assertThat(result.topics())
                .containsExactly(AnswerRequirements.RequestedTopic.AUDIT);
    }

    @Test
    void semanticPlannerKeepsValidAllowlistedTopicsBesideAnUnknownAlias() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"KB_LOOKUP",
                         "tools":["NONE"],"useHistory":false,
                         "topics":["LOYALTY","FFP"],"missingParameters":[],
                         "actionDecision":"NONE",
                         "confidence":0.98,"ambiguity":0.01,
                         "rationale":"Loyalty policy question"}
                        """));
        var context = new RoutingContext(
                "How do points and tiers work?",
                "How do points and tiers work?",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, uncertain());

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "TEST"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.HOSTED_SEMANTIC);
        assertThat(result.topics()).contains(AnswerRequirements.RequestedTopic.FFP);
    }

    @Test
    void hostedPlanLeadsACompoundBookingBaggageAndFlightStatusTurn() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_PLUS_KB",
                         "tools":["BOOKING_LOOKUP","FLIGHT_STATUS"],
                         "useHistory":false,
                         "topics":["BOOKING","BAGGAGE","STATUS"],
                         "missingParameters":[],
                         "actionDecision":"NONE",
                         "confidence":0.97,"ambiguity":0.01,
                         "rationale":"Booking facts, allowance and live flight status"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.FLIGHT_STATUS,
                0.94, null, null, LocalDate.of(2026, 8, 18),
                "H3PL8M", "UA404");
        var context = new RoutingContext(
                "what is my baggage allowance and is my flight on time "
                        + "and my pnr is [AIR-PNR-REDACTED]",
                "what is my baggage allowance and is my flight on time "
                        + "and my pnr is [AIR-PNR-REDACTED]",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "HOSTED_LEAD"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.HOSTED_SEMANTIC);
        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tools()).containsExactly(
                OrchestrationDtos.ToolTarget.BOOKING_LOOKUP,
                OrchestrationDtos.ToolTarget.FLIGHT_STATUS);
        assertThat(result.topics()).contains(
                AnswerRequirements.RequestedTopic.BOOKING,
                AnswerRequirements.RequestedTopic.BAGGAGE,
                AnswerRequirements.RequestedTopic.STATUS);
        assertThat(result.primary().pnr()).isEqualTo("H3PL8M");
        assertThat(result.primary().flightNo()).isEqualTo("UA404");
    }

    @Test
    void hostedPlannerCannotDowngradeCompletedCancellationSlotToBookingLookup() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_PLUS_KB",
                         "tools":["BOOKING_LOOKUP"],"useHistory":true,
                         "topics":["BOOKING","CANCELLATION","REFUND"],
                         "missingParameters":[],"actionDecision":"NONE","confidence":0.96,"ambiguity":0.01,
                         "rationale":"Retrieve the booking before cancellation"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                0.96, null, null, null, "N7QTX2", null);
        var context = new RoutingContext(
                "[AIR-PNR-REDACTED]",
                "I want to cancel my flight. [AIR-PNR-REDACTED]",
                List.of(
                        new ChatDtos.HistoryTurn(
                                "USER", "I want to cancel my flight."),
                        new ChatDtos.HistoryTurn(
                                "ASSISTANT", "Please share the PNR.")),
                true, pendingRefundState(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "PENDING_SLOT"));

        assertThat(result.tools())
                .containsExactly(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        assertThat(result.primary().tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        assertThat(result.primary().pnr()).isEqualTo("N7QTX2");
    }

    @Test
    void hostedPlanCanProposeButNotExecuteAnOwnedCancellation() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_PLUS_KB",
                         "tools":["REFUND_QUOTE"],"useHistory":true,
                         "topics":["BOOKING","CANCELLATION","REFUND"],
                         "missingParameters":[],"actionDecision":"PROPOSE",
                         "confidence":0.98,"ambiguity":0.01,
                         "rationale":"Prepare a governed cancellation review"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                0.96, null, null, null, "N7QTX2", "UA101");
        var context = new RoutingContext(
                "Please remove this itinerary.",
                "Please remove this itinerary.",
                List.of(),
                true,
                new TrustedConversationState(
                        true, "UA101", "BLR-DEL",
                        LocalDate.of(2026, 7, 29),
                        null, null, null, null,
                        false, false, null),
                com.unitedair.ai.identity.Role.PASSENGER,
                1L,
                deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "LIVE_MODEL_FIRST"));

        assertThat(result.actionDecision())
                .isEqualTo(ValidatedRoute.ActionDecision.PROPOSE);
        assertThat(result.tools())
                .containsExactly(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
    }

    @Test
    void confirmationRequiresATrustedPendingAction() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_CALL",
                         "tools":["NONE"],"useHistory":true,
                         "topics":["BOOKING","CANCELLATION","REFUND"],
                         "missingParameters":[],"actionDecision":"CONFIRM",
                         "confidence":0.99,"ambiguity":0.0,
                         "rationale":"Explicitly confirms the pending action"}
                        """));
        var context = new RoutingContext(
                "Yes, cancel this booking.",
                "Yes, cancel this booking.",
                List.of(),
                true,
                TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER,
                1L,
                classification(
                        OrchestrationDtos.Intent.TOOL_CALL,
                        OrchestrationDtos.ToolTarget.NONE,
                        0.7, null, null, null, null, null));

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "LIVE_MODEL_FIRST"));

        assertThat(result.actionDecision())
                .isEqualTo(ValidatedRoute.ActionDecision.NONE);
    }

    @Test
    void refundQuoteDoesNotAskForFieldsItsToolDoesNotRequire() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_PLUS_KB",
                         "tools":["REFUND_QUOTE"],"useHistory":true,
                         "topics":["BOOKING","CANCELLATION","REFUND"],
                         "missingParameters":["travelDate","flightNo"],
                         "actionDecision":"NONE",
                         "confidence":0.95,"ambiguity":0.05,
                         "rationale":"Quote the resolved booking"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                0.96, null, null, null, "N7QTX2", null);
        var context = new RoutingContext(
                "[AIR-PNR-REDACTED]",
                "I want to cancel my flight. [AIR-PNR-REDACTED]",
                List.of(), true, pendingRefundState(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "PENDING_SLOT"));

        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tools())
                .containsExactly(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        assertThat(result.primary().missingParameters()).isEmpty();
    }

    @Test
    void suppliedSlotContinuesAnyTrustedPendingOperation() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_CALL",
                         "tools":["BOOKING_LOOKUP"],"useHistory":true,
                         "topics":["BOOKING","CHECK_IN"],
                         "missingParameters":[],"actionDecision":"NONE",
                         "confidence":0.95,"ambiguity":0.02,
                         "rationale":"Resolve the booking"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.CHECK_IN,
                0.96, null, null, null, "B6X9K2", null);
        var context = new RoutingContext(
                "[AIR-PNR-REDACTED]",
                "Please check me in. [AIR-PNR-REDACTED]",
                List.of(),
                true,
                new TrustedConversationState(
                        true, "UA101", "BLR-DEL",
                        LocalDate.of(2026, 7, 29),
                        "CHECK_IN", "pnr", null, null,
                        true, false, null),
                com.unitedair.ai.identity.Role.PASSENGER,
                1L,
                deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "LIVE_MODEL_FIRST"));

        assertThat(result.tools())
                .containsExactly(OrchestrationDtos.ToolTarget.CHECK_IN);
    }

    @Test
    void deterministicMissingDetailsCannotBypassHostedPlanning() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"AMBIGUOUS","intent":"CLARIFICATION",
                         "tools":["NONE"],"useHistory":false,
                         "topics":["FLIGHT_SEARCH"],
                         "missingParameters":["travelDate"],
                         "actionDecision":"NONE",
                         "confidence":0.91,"ambiguity":0.72,
                         "rationale":"The travel date is still required"}
                        """));
        var deterministic = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.CLARIFICATION,
                OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                0.99,
                "Missing travel date",
                "BLR", "DEL", null, "ECONOMY",
                null, null, null,
                List.of(), List.of("travelDate"), Set.of());
        var context = new RoutingContext(
                "Find a flight from Bengaluru to Delhi.",
                "Find a flight from Bengaluru to Delhi.",
                List.of(),
                false,
                TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER,
                1L,
                deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "LIVE_MODEL_FIRST"));

        verify(gateway).completeDirect(
                anyString(), anyList(), anyString(), anyString());
        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.primary().missingParameters())
                .contains("travelDate");
    }

    @Test
    void hostedPlannerCannotTreatARequestScopedPnrAsMissing() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"AMBIGUOUS","intent":"CLARIFICATION",
                         "tools":["NONE"],"useHistory":true,
                         "topics":["BOOKING","CHECK_IN","STATUS","DOCUMENTS"],
                         "missingParameters":["pnr"],"actionDecision":"NONE","confidence":0.98,"ambiguity":0.9,
                         "rationale":"The visible PNR is redacted"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.CHECK_IN,
                0.95, null, null, LocalDate.of(2026, 8, 4),
                "B6X9K2", "UA101");
        var context = new RoutingContext(
                "For my booking [AIR-PNR-REDACTED], can I check in and what is its status?",
                "For my booking [AIR-PNR-REDACTED], can I check in and what is its status?",
                List.of(), true, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "HOSTED_LEAD"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.DETERMINISTIC);
        assertThat(result.primary().pnr()).isEqualTo("B6X9K2");
        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
    }

    @Test
    void passengerSemanticPlanCannotInvokeStaffOperationalData() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_CALL",
                         "tools":["BOOKING_LOOKUP","CHECK_IN","FLIGHT_STATUS",
                                  "OPERATIONAL_DATA_QUERY"],
                         "useHistory":false,
                         "topics":["BOOKING","CHECK_IN","STATUS","DOCUMENTS"],
                         "missingParameters":[],"actionDecision":"NONE","confidence":0.98,"ambiguity":0.01,
                         "rationale":"Resolve all parts of the passenger request"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.CHECK_IN,
                0.95, null, null, LocalDate.of(2026, 8, 4),
                "B6X9K2", "UA101");
        var context = new RoutingContext(
                "For my booking [AIR-PNR-REDACTED], can I check in and what is its status?",
                "For my booking [AIR-PNR-REDACTED], can I check in and what is its status?",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "HOSTED_LEAD"));

        assertThat(result.tools()).containsExactly(
                OrchestrationDtos.ToolTarget.CHECK_IN,
                OrchestrationDtos.ToolTarget.FLIGHT_STATUS);
        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
    }

    @Test
    void hostedFlightSearchCannotDispatchWithoutTrustedRouteArguments() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_PLUS_KB",
                         "tools":["FLIGHT_SEARCH"],"useHistory":false,
                         "topics":["FLIGHT_SEARCH","FARES"],
                         "missingParameters":[],"actionDecision":"NONE","confidence":0.98,"ambiguity":0.01,
                         "rationale":"Search the requested route"}
                        """));
        var context = new RoutingContext(
                "search a flight for tomorrow",
                "search a flight for tomorrow",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, uncertain());

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "HOSTED_LEAD"));

        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.primary().missingParameters())
                .contains("origin", "destination");
        assertThat(result.tools()).isEmpty();
    }

    @Test
    void broadFlightSearchDoesNotDispatchASeatMapWithoutASelectedFlight() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_PLUS_KB",
                         "tools":["FLIGHT_SEARCH","SEAT_MAP"],"useHistory":false,
                         "topics":["FLIGHT_SEARCH","FARES","BAGGAGE","SEATS"],
                         "missingParameters":[],"actionDecision":"NONE","confidence":0.99,"ambiguity":0.0,
                         "rationale":"Search and report the returned fare inventory"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                0.95, "Bengaluru", "New Delhi",
                LocalDate.of(2026, 7, 29), null, null);
        var context = new RoutingContext(
                "Search Bengaluru to New Delhi tomorrow with fares, baggage and seats",
                "Search Bengaluru to New Delhi tomorrow with fares, baggage and seats",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "HOSTED_LEAD"));

        assertThat(result.tools())
                .containsExactly(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH);
    }

    @Test
    void genericMealPolicyDoesNotDispatchFlightSpecificAvailabilityWithoutAnItinerary() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"KB_LOOKUP",
                         "tools":["MEAL_AVAILABILITY"],"useHistory":false,
                         "topics":["MEALS","SPECIAL_SERVICES"],
                         "missingParameters":[],"actionDecision":"NONE","confidence":0.99,"ambiguity":0.0,
                         "rationale":"Explain meal codes and assistance policies"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE,
                0.95, null, null, null, null, null);
        var context = new RoutingContext(
                "Which VGML and KSML meals can I pre-order and what are the "
                        + "WCHR and MEDA request rules?",
                "Which VGML and KSML meals can I pre-order and what are the "
                        + "WCHR and MEDA request rules?",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "HOSTED_LEAD"));

        assertThat(result.primary().intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tools()).isEmpty();
        assertThat(result.topics()).contains(
                AnswerRequirements.RequestedTopic.MEALS,
                AnswerRequirements.RequestedTopic.SPECIAL_SERVICES);
    }

    @Test
    void hostedTopicsAreMergedWithTrustedDeterministicTopicsInsteadOfVetoingThePlan() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"KB_LOOKUP",
                         "tools":["NONE"],"useHistory":false,
                         "topics":["COMPLIANCE"],"missingParameters":[],
                         "actionDecision":"NONE",
                         "confidence":0.96,"ambiguity":0.02,
                         "rationale":"Dangerous goods policy"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE,
                0.85, null, null, null, null, null);
        var context = new RoutingContext(
                "Can I bring fireworks in my baggage?",
                "Can I bring fireworks in my baggage?",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, true, "HOSTED_LEAD"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.HOSTED_SEMANTIC);
        assertThat(result.topics()).contains(
                AnswerRequirements.RequestedTopic.BAGGAGE,
                AnswerRequirements.RequestedTopic.COMPLIANCE);
    }

    @Test
    void hostedPlannerCannotMisrouteMandatoryGroundedSafetyPolicy() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_CALL",
                         "tools":["FLIGHT_SEARCH"],"useHistory":false,
                         "topics":["FLIGHT_SEARCH"],"missingParameters":["origin","destination"],
                         "actionDecision":"NONE",
                         "confidence":1.0,"ambiguity":0.0,
                         "rationale":"Misread the word flight"}
                        """));
        var deterministic = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE,
                1.0,
                "Grounded prohibited-items safety policy",
                null, null, null, null, null, null,
                "SAFETY_POLICY", List.of("baggage", "safety"),
                List.of(), Set.of("KB-AIR-003"));
        var context = new RoutingContext(
                "can i bring bomb and knife to the airport?",
                "can i bring bomb and knife to the airport?",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, true, "SCOPE_SENSITIVE"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.DETERMINISTIC);
        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.degradationReason())
                .isEqualTo("MANDATORY_SAFETY_POLICY");
        verifyNoInteractions(gateway);
    }

    @Test
    void lowSemanticConfidenceDoesNotHandControlBackToLexicalRouting() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"AMBIGUOUS","intent":"CLARIFICATION",
                         "tools":["NONE"],"useHistory":true,
                         "topics":[],"missingParameters":["request"],
                         "actionDecision":"NONE",
                         "confidence":0.48,"ambiguity":0.82,
                         "rationale":"The airline request needs clarification"}
                        """));
        var context = new RoutingContext(
                "help with this", "help with this", List.of(), true, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, uncertain());

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, true, "HOSTED_LEAD"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.HOSTED_SEMANTIC);
        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.scope()).isEqualTo(ValidatedRoute.Scope.AMBIGUOUS);
    }

    @Test
    void mandatoryEscalationBypassesHostedPlanning() {
        var deterministic = classification(
                OrchestrationDtos.Intent.ESCALATION,
                OrchestrationDtos.ToolTarget.NONE,
                1.0, null, null, null, null, null);
        var context = new RoutingContext(
                "Please connect me to a human agent.",
                "Please connect me to a human agent.",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.PASSENGER, 1L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, false, "HOSTED_LEAD"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.DETERMINISTIC);
        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.ESCALATION);
        assertThat(result.tools()).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void validatedPlanRejectsSqlAndFallsBackToDeterministicRoute() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_CALL",
                         "tools":["OPERATIONAL_DATA_QUERY"],
                         "useHistory":false,"topics":["AUDIT"],
                         "missingParameters":[],"actionDecision":"NONE","confidence":0.99,"ambiguity":0.0,
                         "rationale":"SELECT * FROM sim_booking"}
                        """));
        var context = new RoutingContext(
                "show operations", "show operations", List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.AIRLINE_STAFF, 2L, uncertain());

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(true, true, "TEST"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.DETERMINISTIC);
        assertThat(result.degradationReason()).isEqualTo("INVALID_SEMANTIC_PLAN");
    }

    @Test
    void hostedPlannerCannotRemoveARequiredPnrFromRefundStatus() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_CALL",
                         "tools":["REFUND_STATUS"],"useHistory":true,
                         "topics":["REFUND","BOOKING"],"missingParameters":[],
                         "actionDecision":"NONE",
                         "confidence":0.98,"ambiguity":0.01,
                         "rationale":"Look up the refund"}
                        """));
        var deterministic = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.CLARIFICATION,
                OrchestrationDtos.ToolTarget.REFUND_STATUS,
                0.95,
                "Refund status requires a resolved booking",
                null, null, null, null, null, null, null,
                List.of(), List.of("pnr"), Set.of());
        var context = new RoutingContext(
                "What is the refund status for my booking reference?",
                "What is the refund status for my booking reference?",
                List.of(), false, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.AIRLINE_STAFF, 2L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "MULTI_TOPIC_OR_OPERATIONAL"));

        assertThat(result.primary().intent())
                .isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.primary().missingParameters()).contains("pnr");
        assertThat(result.tools()).isEmpty();
    }

    @Test
    void hostedSemanticPlannerCannotReplaceResolvedRefundStatusTool() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(live("""
                        {"scope":"IN_SCOPE","intent":"TOOL_CALL",
                         "tools":["OPERATIONAL_DATA_QUERY"],"useHistory":true,
                         "topics":["REFUND","BOOKING"],"missingParameters":[],
                         "actionDecision":"NONE",
                         "confidence":0.98,"ambiguity":0.01,
                         "rationale":"Use generic operations"}
                        """));
        var deterministic = classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.REFUND_STATUS,
                0.97, null, null, null, "K2MN7V", null);
        var context = new RoutingContext(
                "what is the status on the booking reference refund status",
                "what is the status on the booking reference refund status",
                List.of(), true, TrustedConversationState.empty(),
                com.unitedair.ai.identity.Role.AIRLINE_STAFF, 2L, deterministic);

        var result = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "MULTI_TOPIC_OR_OPERATIONAL"));

        assertThat(result.source()).isEqualTo(ValidatedRoute.Source.DETERMINISTIC);
        assertThat(result.primary().tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.REFUND_STATUS);
        assertThat(result.tools())
                .containsExactly(OrchestrationDtos.ToolTarget.REFUND_STATUS);
    }

    private OrchestrationDtos.Classification uncertain() {
        return classification(OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE, 0.6,
                null, null, null, null, null);
    }

    private TrustedConversationState pendingRefundState() {
        return new TrustedConversationState(
                true,
                null,
                null,
                null,
                OrchestrationDtos.ToolTarget.REFUND_QUOTE.name(),
                "pnr",
                null,
                null,
                true,
                false,
                null);
    }

    private OrchestrationDtos.Classification classification(
            OrchestrationDtos.Intent intent,
            OrchestrationDtos.ToolTarget tool,
            double confidence,
            String origin,
            String destination,
            LocalDate date,
            String pnr,
            String flightNo) {
        return new OrchestrationDtos.Classification(
                intent, tool, confidence, "deterministic", origin, destination, date,
                null, pnr, flightNo, null, List.of(), List.of(), Set.of());
    }

    private ChatDtos.ChatResult live(String text) {
        return new ChatDtos.ChatResult(text, 10, 10, "test-model", true, null);
    }
}
