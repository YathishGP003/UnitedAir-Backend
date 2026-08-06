package com.unitedair.ai.orchestration;

import com.unitedair.ai.llm.ChatDtos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRelevancePolicyTest {

    private final ContextRelevancePolicy policy = new ContextRelevancePolicy();

    private static final List<ChatDtos.HistoryTurn> BOOKING_HISTORY = List.of(
            new ChatDtos.HistoryTurn("USER", "Show booking [AIR-PNR-REDACTED]."),
            new ChatDtos.HistoryTurn("ASSISTANT", "Your booking is confirmed."));

    @Test
    void completeGeneralQuestionCannotInheritBookingContext() {
        assertThat(policy.evaluate("what is 2+2", BOOKING_HISTORY, null).useHistory())
                .isFalse();
    }

    @Test
    void completeUnrelatedQuestionCannotInheritBaggageContext() {
        var baggageHistory = List.of(
                new ChatDtos.HistoryTurn("USER", "Explain excess baggage."),
                new ChatDtos.HistoryTurn("ASSISTANT", "A flat charge can apply."));

        assertThat(policy.evaluate("why earth is flat?", baggageHistory, null).useHistory())
                .isFalse();
    }

    @Test
    void referentialBookingFollowUpUsesLatestCompatibleContext() {
        var decision = policy.evaluate("is it refundable?", BOOKING_HISTORY, null);

        assertThat(decision.useHistory()).isTrue();
        assertThat(decision.supportingTurnIndexes()).containsExactly(0);
    }

    @Test
    void directAnswerToPendingPnrSlotUsesContext() {
        var decision = policy.evaluate("X2LTWZ", BOOKING_HISTORY, "PNR");

        assertThat(decision.useHistory()).isTrue();
        assertThat(decision.reason()).isEqualTo("PENDING_SLOT");
    }

    @Test
    void completeNewPolicyQuestionDoesNotInheritBooking() {
        assertThat(policy.evaluate(
                "what is the cancellation policy?", BOOKING_HISTORY, null).useHistory())
                .isFalse();
    }

    @Test
    void namedCityPairCompletesThePendingFlightRoute() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Need flight options for tomorrow."),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Which origin and destination?"));

        assertThat(policy.evaluate(
                "Madras to Hyderabad.", history, null).useHistory()).isTrue();
    }

    @Test
    void evenIfPolicyFollowupKeepsItsAntecedent() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "I missed the first leg. What happens to my return sector?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "The no-show rule applies."));

        assertThat(policy.evaluate(
                "Even if I call only after departure?", history, null).useHistory())
                .isTrue();
    }
}
