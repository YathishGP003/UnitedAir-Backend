package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.unitedair.ai.grounding.CitationAttacher;
import com.unitedair.ai.grounding.CitationBuilder;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Grounding gates for the evaluator-optimizer stage. */
class AnswerEvaluatorTest {

    private final UnitedAirProperties properties = new UnitedAirProperties();
    private final AnswerEvaluator evaluator =
            new AnswerEvaluator(properties, new CitationBuilder(), new CitationAttacher());

    @Test
    @DisplayName("rejects any uncited factual sentence, not only numeric claims")
    void rejectsUncitedNarrativeClaim() {
        var verdict = evaluator.evaluate(
                "Value fares are refundable before departure. [E1] "
                        + "Refunds always return to the original payment method.",
                List.of("What fee applies?", "How long does it take?"),
                List.of(ranked("Value fares are refundable before departure. "
                        + "Refunds return to the original payment method.")),
                0.9,
                Role.PASSENGER,
                false);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failedGates()).contains("MISSING_CITATION");
    }

    @Test
    @DisplayName("accepts KB and tool handles when every factual sentence is cited")
    void acceptsCompleteCitationCoverage() {
        var kbVerdict = evaluator.evaluate(
                "Value fares are refundable before departure. [E1]",
                List.of("What fee applies?", "How long does it take?"),
                List.of(ranked("Value fares are refundable before departure.")),
                0.9,
                Role.PASSENGER,
                false);
        var toolVerdict = evaluator.evaluate(
                "UA101 departs at 10:30. [T1]",
                List.of("Which terminal?", "Is the flight delayed?"),
                List.of(),
                0.0,
                Role.PASSENGER,
                true);

        assertThat(kbVerdict.failedGates()).doesNotContain("MISSING_CITATION");
        assertThat(toolVerdict.failedGates()).doesNotContain("MISSING_CITATION");
    }

    @Test
    void acceptsOperationalWordingWhenPassengerVisiblePolicyExplicitlySupportsIt() {
        var verdict = evaluator.evaluate(
                "A complimentary upgrade requires Duty Manager approval. [E1]",
                List.of("Which upgrades can I request?", "Can I pay at check-in?"),
                List.of(rankedWithAudience(
                        "Complimentary upgrades require Duty Manager approval.",
                        "Passenger,Airline Staff")),
                0.9,
                Role.PASSENGER,
                false);

        assertThat(verdict.failedGates()).doesNotContain("ACTOR_SCOPE_LEAK");
    }

    @Test
    void stillRejectsInternalWordingWithoutPassengerVisibleSupport() {
        var verdict = evaluator.evaluate(
                "A complimentary upgrade requires Duty Manager approval. [E1]",
                List.of("Which upgrades can I request?", "Can I pay at check-in?"),
                List.of(rankedWithAudience(
                        "Complimentary upgrades require Duty Manager approval.",
                        "Airline Staff")),
                0.9,
                Role.PASSENGER,
                false);

        assertThat(verdict.failedGates()).contains("ACTOR_SCOPE_LEAK");
    }

    @Test
    void rejectsRawStructuredToolPayloadInsteadOfShowingJsonToPassenger() {
        var verdict = evaluator.evaluate(
                """
                {"pnr":"your booking reference","status":"CONFIRMED",
                "flightNo":"UA101","checkedBaggageKg":15} [T1].
                """,
                List.of("Show baggage rules.", "Check flight status."),
                List.of(),
                0.0,
                Role.PASSENGER,
                true);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failedGates()).contains("RAW_STRUCTURED_PAYLOAD");
        assertThat(verdict.isRepairable()).isTrue();
    }

    @Test
    void rejectsRawStructuredToolPayloadEvenAfterReadablePolicyProse() {
        var verdict = evaluator.evaluate(
                """
                Economy Value checked baggage allowance is 15 kg [E1].

                {"pnr":"H3PL8M","status":"CONFIRMED",
                "flightNo":"UA404","checkedBaggageKg":25} [T1].
                """,
                List.of("Show baggage rules.", "Check flight status."),
                List.of(ranked("Economy Value checked baggage allowance is 15 kg.")),
                0.9,
                Role.PASSENGER,
                true);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failedGates()).contains("RAW_STRUCTURED_PAYLOAD");
    }

    private static RetrievalDtos.Ranked ranked(String content) {
        return rankedWithAudience(content, "Passenger");
    }

    private static RetrievalDtos.Ranked rankedWithAudience(
            String content, String audience) {
        var chunk = new RetrievalDtos.Chunk(
                1L, "chunk-1", "KB-AIR-004", "Cancellation", "2.1", 1,
                "pdf", "fare-rule", audience, content, 0.9, 0.9);
        return new RetrievalDtos.Ranked(chunk, 0.9, 0.9, 1);
    }
}
