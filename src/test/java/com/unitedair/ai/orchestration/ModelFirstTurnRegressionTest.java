package com.unitedair.ai.orchestration;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.llm.ChatGateway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wording-independent contract tests for the live semantic controller.
 *
 * <p>The cases intentionally avoid testing exact prompt prose. They prove that the hosted
 * semantic plan is authoritative for normal live turns while server-side validation still
 * owns role scope, trusted state, tool allowlists and mutation decisions.
 */
class ModelFirstTurnRegressionTest {

    record SemanticCase(
            String id,
            Role role,
            String message,
            String hostedPlan,
            OrchestrationDtos.Intent expectedIntent,
            List<OrchestrationDtos.ToolTarget> expectedTools,
            Set<AnswerRequirements.RequestedTopic> expectedTopics,
            ValidatedRoute.ActionDecision expectedAction) { }

    @ParameterizedTest(name = "{0}")
    @MethodSource("semanticCases")
    void liveSemanticPlanControlsWordingVariantsWithoutLeakingTrustedData(
            SemanticCase testCase) {
        ChatGateway gateway = mock(ChatGateway.class);
        AdaptiveRoutePlanner planner = new AdaptiveRoutePlanner(gateway);
        RoutingContext context = context(testCase);

        if (testCase.hostedPlan() != null) {
            when(gateway.completeDirect(
                    anyString(), anyList(), anyString(), anyString()))
                    .thenReturn(live(testCase.hostedPlan()));
        }

        ValidatedRoute route = planner.plan(
                context,
                new SemanticRoutingPolicy.Eligibility(
                        true, false, "LIVE_MODEL_FIRST"));

        assertThat(route.primary().intent())
                .isEqualTo(testCase.expectedIntent());
        assertThat(route.tools()).containsExactlyElementsOf(
                testCase.expectedTools());
        assertThat(route.topics()).containsAll(testCase.expectedTopics());
        assertThat(route.actionDecision())
                .isEqualTo(testCase.expectedAction());
        assertThat(route.tools()).doesNotHaveDuplicates();
        if (testCase.role() == Role.PASSENGER) {
            assertThat(route.tools()).doesNotContain(
                    OrchestrationDtos.ToolTarget.REFUND_CASES,
                    OrchestrationDtos.ToolTarget.ESCALATION_QUEUE,
                    OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS,
                    OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY);
        }
        if (testCase.expectedAction()
                == ValidatedRoute.ActionDecision.PROPOSE) {
            assertThat(context.trustedState().ownedBookingResolved()).isTrue();
            assertThat(route.tools())
                    .contains(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        }
        if (testCase.hostedPlan() == null) {
            verify(gateway, never()).completeDirect(
                    anyString(), anyList(), anyString(), anyString());
            return;
        }

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(1)).completeDirect(
                anyString(), anyList(), prompt.capture(), anyString());
        assertThat(prompt.getValue())
                .doesNotContain("N7QTX2", "H3PL8M", "sim_booking",
                        "refund_case", "SELECT *");
    }

    static Stream<SemanticCase> semanticCases() {
        return Stream.of(
                semantic(
                        "formal-flight-search",
                        Role.PASSENGER,
                        "Please locate tomorrow’s services from Bengaluru to Goa.",
                        "TOOL_CALL",
                        List.of("FLIGHT_SEARCH"),
                        List.of("FLIGHT_SEARCH"),
                        "NONE",
                        OrchestrationDtos.Intent.TOOL_CALL,
                        List.of(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH),
                        Set.of(AnswerRequirements.RequestedTopic.FLIGHT_SEARCH),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "casual-cancellation-action",
                        Role.PASSENGER,
                        "yeah, drop this itinerary for me",
                        "TOOL_PLUS_KB",
                        List.of("REFUND_QUOTE"),
                        List.of("BOOKING", "CANCELLATION", "REFUND"),
                        "PROPOSE",
                        OrchestrationDtos.Intent.TOOL_PLUS_KB,
                        List.of(OrchestrationDtos.ToolTarget.REFUND_QUOTE),
                        Set.of(
                                AnswerRequirements.RequestedTopic.BOOKING,
                                AnswerRequirements.RequestedTopic.CANCELLATION,
                                AnswerRequirements.RequestedTopic.REFUND),
                        ValidatedRoute.ActionDecision.PROPOSE),
                semantic(
                        "terse-policy-only",
                        Role.PASSENGER,
                        "cancel rules?",
                        "KB_LOOKUP",
                        List.of("NONE"),
                        List.of("CANCELLATION", "REFUND"),
                        "QUOTE_ONLY",
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        List.of(),
                        Set.of(
                                AnswerRequirements.RequestedTopic.CANCELLATION,
                                AnswerRequirements.RequestedTopic.REFUND),
                        ValidatedRoute.ActionDecision.QUOTE_ONLY),
                semantic(
                        "misspelled-bare-pnr-pending-slot",
                        Role.PASSENGER,
                        "[AIR-PNR-REDACTED]",
                        "TOOL_PLUS_KB",
                        List.of("BOOKING_LOOKUP"),
                        List.of("BOOKING", "CANCELLATION", "REFUND"),
                        "PROPOSE",
                        OrchestrationDtos.Intent.TOOL_PLUS_KB,
                        List.of(OrchestrationDtos.ToolTarget.REFUND_QUOTE),
                        Set.of(
                                AnswerRequirements.RequestedTopic.BOOKING,
                                AnswerRequirements.RequestedTopic.CANCELLATION,
                                AnswerRequirements.RequestedTopic.REFUND),
                        ValidatedRoute.ActionDecision.PROPOSE),
                semantic(
                        "reordered-booking-status-baggage",
                        Role.PASSENGER,
                        "For this booking, baggage first, then tell me whether that flight is on time.",
                        "TOOL_PLUS_KB",
                        List.of("BOOKING_LOOKUP", "FLIGHT_STATUS"),
                        List.of("BOOKING", "BAGGAGE", "STATUS"),
                        "NONE",
                        OrchestrationDtos.Intent.TOOL_PLUS_KB,
                        List.of(
                                OrchestrationDtos.ToolTarget.BOOKING_LOOKUP,
                                OrchestrationDtos.ToolTarget.FLIGHT_STATUS),
                        Set.of(
                                AnswerRequirements.RequestedTopic.BOOKING,
                                AnswerRequirements.RequestedTopic.BAGGAGE,
                                AnswerRequirements.RequestedTopic.STATUS),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "synonym-heavy-services-and-seats",
                        Role.PASSENGER,
                        "Compare mobility help and child escort assistance with preferred-chair charges.",
                        "KB_LOOKUP",
                        List.of("NONE"),
                        List.of("SPECIAL_SERVICES", "SEATS"),
                        "NONE",
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        List.of(),
                        Set.of(
                                AnswerRequirements.RequestedTopic.SPECIAL_SERVICES,
                                AnswerRequirements.RequestedTopic.SEATS),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "staff-refunds-escalations-audit",
                        Role.AIRLINE_STAFF,
                        "Summarise pending refunds, open escalations and their audit decisions.",
                        "TOOL_CALL",
                        List.of("OPERATIONAL_DATA_QUERY"),
                        List.of("REFUND", "AUDIT"),
                        "NONE",
                        OrchestrationDtos.Intent.TOOL_PLUS_KB,
                        List.of(OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY),
                        Set.of(
                                AnswerRequirements.RequestedTopic.REFUND,
                                AnswerRequirements.RequestedTopic.AUDIT),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "staff-ffp-complete",
                        Role.AIRLINE_STAFF,
                        "Explain points earning, redemption, tiers, upgrades and retro credit.",
                        "KB_LOOKUP",
                        List.of("NONE"),
                        List.of("FFP"),
                        "NONE",
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        List.of(),
                        Set.of(AnswerRequirements.RequestedTopic.FFP),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "staff-baggage-tracing-complete",
                        Role.AIRLINE_STAFF,
                        "Give PIR, WorldTracer, claim SLA and Montreal Convention steps.",
                        "KB_LOOKUP",
                        List.of("NONE"),
                        List.of("BAGGAGE", "COMPLIANCE"),
                        "NONE",
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        List.of(),
                        Set.of(
                                AnswerRequirements.RequestedTopic.BAGGAGE,
                                AnswerRequirements.RequestedTopic.COMPLIANCE),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "greeting",
                        Role.PASSENGER,
                        "hiiii, what can you help with?",
                        "SMALL_TALK",
                        List.of("NONE"),
                        List.of(),
                        "NONE",
                        OrchestrationDtos.Intent.SMALL_TALK,
                        List.of(),
                        Set.of(),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "thanks",
                        Role.PASSENGER,
                        "cheers, that helped",
                        "SMALL_TALK",
                        List.of("NONE"),
                        List.of(),
                        "NONE",
                        OrchestrationDtos.Intent.SMALL_TALK,
                        List.of(),
                        Set.of(),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "unrelated-math",
                        Role.PASSENGER,
                        "What is two plus two?",
                        "OUT_OF_SCOPE",
                        List.of("NONE"),
                        List.of(),
                        "NONE",
                        OrchestrationDtos.Intent.OUT_OF_SCOPE,
                        List.of(),
                        Set.of(),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "dangerous-goods-policy",
                        Role.PASSENGER,
                        "May a passenger pack fireworks or a knife?",
                        "KB_LOOKUP",
                        List.of("NONE"),
                        List.of("BAGGAGE", "COMPLIANCE"),
                        "NONE",
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        List.of(),
                        Set.of(
                                AnswerRequirements.RequestedTopic.BAGGAGE,
                                AnswerRequirements.RequestedTopic.COMPLIANCE),
                        ValidatedRoute.ActionDecision.NONE),
                new SemanticCase(
                        "active-threat-boundary",
                        Role.PASSENGER,
                        "There is an explosive device at the gate right now.",
                        null,
                        OrchestrationDtos.Intent.ESCALATION,
                        List.of(),
                        Set.of(),
                        ValidatedRoute.ActionDecision.NONE),
                semantic(
                        "unrelated-follow-up-does-not-inherit-booking",
                        Role.PASSENGER,
                        "Now explain loyalty tiers instead.",
                        "KB_LOOKUP",
                        List.of("NONE"),
                        List.of("FFP"),
                        "NONE",
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        List.of(),
                        Set.of(AnswerRequirements.RequestedTopic.FFP),
                        ValidatedRoute.ActionDecision.NONE));
    }

    private static SemanticCase semantic(
            String id,
            Role role,
            String message,
            String intent,
            List<String> tools,
            List<String> topics,
            String action,
            OrchestrationDtos.Intent expectedIntent,
            List<OrchestrationDtos.ToolTarget> expectedTools,
            Set<AnswerRequirements.RequestedTopic> expectedTopics,
            ValidatedRoute.ActionDecision expectedAction) {
        String json = """
                {"scope":"IN_SCOPE","intent":"%s","tools":%s,
                 "useHistory":true,"topics":%s,"missingParameters":[],
                 "actionDecision":"%s","confidence":0.98,"ambiguity":0.01,
                 "rationale":"Validated semantic case %s"}
                """.formatted(
                        intent,
                        jsonArray(tools),
                        jsonArray(topics),
                        action,
                        id);
        if ("OUT_OF_SCOPE".equals(intent)) {
            json = json.replace(
                    "\"scope\":\"IN_SCOPE\"",
                    "\"scope\":\"OUT_OF_SCOPE\"");
        }
        return new SemanticCase(
                id,
                role,
                message,
                json,
                expectedIntent,
                expectedTools,
                expectedTopics,
                expectedAction);
    }

    private static RoutingContext context(SemanticCase testCase) {
        TrustedConversationState state = TrustedConversationState.empty();
        OrchestrationDtos.Classification deterministic = uncertain();
        String current = testCase.message();
        if (testCase.id().equals("active-threat-boundary")) {
            deterministic = classification(
                    OrchestrationDtos.Intent.ESCALATION,
                    OrchestrationDtos.ToolTarget.NONE,
                    1.0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "ACTIVE_SECURITY_THREAT");
        } else if (testCase.id().equals("formal-flight-search")) {
            deterministic = classification(
                    OrchestrationDtos.Intent.TOOL_CALL,
                    OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                    0.98,
                    "BLR",
                    "GOI",
                    LocalDate.of(2026, 7, 29),
                    null,
                    null,
                    null);
        } else if (testCase.id().equals("casual-cancellation-action")
                || testCase.id().equals("misspelled-bare-pnr-pending-slot")) {
            deterministic = classification(
                    OrchestrationDtos.Intent.TOOL_PLUS_KB,
                    OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                    0.98,
                    null,
                    null,
                    null,
                    "N7QTX2",
                    "UA101",
                    null);
            state = new TrustedConversationState(
                    true,
                    "UA101",
                    "BLR-DEL",
                    LocalDate.of(2026, 7, 29),
                    testCase.id().startsWith("misspelled")
                            ? OrchestrationDtos.ToolTarget.REFUND_QUOTE.name()
                            : null,
                    testCase.id().startsWith("misspelled") ? "pnr" : null,
                    null,
                    null,
                    testCase.id().startsWith("misspelled"),
                    false,
                    null);
        } else if (testCase.id().equals("reordered-booking-status-baggage")) {
            deterministic = classification(
                    OrchestrationDtos.Intent.TOOL_PLUS_KB,
                    OrchestrationDtos.ToolTarget.FLIGHT_STATUS,
                    0.98,
                    "DEL",
                    "LHR",
                    LocalDate.of(2026, 8, 18),
                    "H3PL8M",
                    "UA404",
                    null);
            state = new TrustedConversationState(
                    true,
                    "UA404",
                    "DEL-LHR",
                    LocalDate.of(2026, 8, 18),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null);
        }
        return new RoutingContext(
                current,
                current,
                testCase.id().equals("unrelated-follow-up-does-not-inherit-booking")
                        ? List.of(
                                new ChatDtos.HistoryTurn(
                                        "USER",
                                        "Show my booking [AIR-PNR-REDACTED]."),
                                new ChatDtos.HistoryTurn(
                                        "ASSISTANT",
                                        "Your booking is confirmed [T1]."))
                        : List.of(),
                !testCase.id().equals(
                        "unrelated-follow-up-does-not-inherit-booking"),
                state,
                testCase.role(),
                testCase.role() == Role.PASSENGER ? 1L : 2L,
                deterministic);
    }

    private static OrchestrationDtos.Classification uncertain() {
        return classification(
                OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE,
                0.60,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static OrchestrationDtos.Classification classification(
            OrchestrationDtos.Intent intent,
            OrchestrationDtos.ToolTarget tool,
            double confidence,
            String origin,
            String destination,
            LocalDate date,
            String pnr,
            String flightNo,
            String escalationReason) {
        return new OrchestrationDtos.Classification(
                intent,
                tool,
                confidence,
                "Trusted test candidate",
                origin,
                destination,
                date,
                null,
                pnr,
                flightNo,
                escalationReason,
                List.of(),
                List.of(),
                Set.of());
    }

    private static ChatDtos.ChatResult live(String text) {
        return new ChatDtos.ChatResult(
                text,
                10,
                5,
                "openai/gpt-4.1",
                ChatDtos.GenerationSource.HOSTED_MODEL,
                null);
    }

    private static String jsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
