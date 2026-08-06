package com.unitedair.ai.orchestration;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.AiMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticRoutingPolicyTest {

    private final SemanticRoutingPolicy policy =
            new SemanticRoutingPolicy(AiMode.LIVE);

    @Test
    void actionAndStatusLanguageReceivesSemanticReviewEvenAtHighConfidence() {
        assertThat(policy.evaluate(context("i want to cancel flight", 0.92)).refine())
                .isTrue();
        assertThat(policy.evaluate(
                context("status of the refund for [AIR-PNR-REDACTED]", 0.92)).refine())
                .isTrue();
    }

    @Test
    void multiTopicOperationalQuestionReceivesSemanticReview() {
        var result = policy.evaluate(
                context("show pending refunds and open escalations", 0.92));

        assertThat(result.refine()).isTrue();
        assertThat(result.reason()).isEqualTo("LIVE_MODEL_FIRST");
    }

    @Test
    void everyCompletedNormalTurnReceivesHostedSemanticPlanning() {
        assertThat(policy.evaluate(
                context("what is my baggage allowance?", 0.99)).refine())
                .isTrue();
        assertThat(policy.evaluate(
                context("hello", 0.99)).refine())
                .isTrue();
    }

    @Test
    void compoundBookingStatusAndBaggageQuestionCannotBypassSemanticPlanning() {
        var result = policy.evaluate(context(
                "what is my baggage allowance and is my flight on time "
                        + "and my pnr is [AIR-PNR-REDACTED]",
                0.94));

        assertThat(result.refine()).isTrue();
    }

    @Test
    void nonAirlineQuestionStillUsesTheModelWithoutKeywordRouting() {
        var result = policy.evaluate(context("what is 2+2", 0.92));

        assertThat(result.refine()).isTrue();
        assertThat(result.scopeSensitive()).isFalse();
        assertThat(result.reason()).isEqualTo("LIVE_MODEL_FIRST");
    }

    @Test
    void offlineModeUsesTheSafeDeterministicCandidate() {
        SemanticRoutingPolicy offline =
                new SemanticRoutingPolicy(AiMode.OFFLINE);

        assertThat(offline.evaluate(context("hello", 0.99)).refine())
                .isFalse();
        assertThat(offline.evaluate(context("hello", 0.99)).reason())
                .isEqualTo("OFFLINE_MODE");
    }

    private static RoutingContext context(String query, double confidence) {
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE,
                confidence,
                "test",
                null, null, null, null, null, null, null,
                List.of(), List.of(), Set.of());
        return new RoutingContext(
                query, query, List.of(), false,
                TrustedConversationState.empty(),
                Role.PASSENGER, 1L, classification);
    }
}
