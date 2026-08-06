package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.Test;

class AgenticOrchestratorFormattingTest {

    @Test
    void repairedLiveDraftMayUseGroundedFallbackWhenVerifiedToolsArePresent() {
        assertThat(AgenticOrchestrator.shouldTryGroundedFallback(
                false, true, true))
                .isTrue();
    }

    @Test
    void rateLimitedGenerationAlwaysReturnsCapacityInsteadOfEscalating() {
        assertThat(AgenticOrchestrator.shouldReturnModelCapacity(
                false, "MODEL_CAPACITY"))
                .isTrue();
        assertThat(AgenticOrchestrator.shouldReturnModelCapacity(
                false, "HTTP_429"))
                .isTrue();
        assertThat(AgenticOrchestrator.shouldReturnModelCapacity(
                true, "MODEL_CAPACITY"))
                .isFalse();
        assertThat(AgenticOrchestrator.shouldReturnModelCapacity(
                false, "UPSTREAM_UNAVAILABLE"))
                .isFalse();
    }

    private final AnswerPresentationService presentation = new AnswerPresentationService();

    @Test
    void labelsCabinAndCheckedBaggageRowsForPassengers() {
        String answer = present("""
                Economy | 1 piece | 7 kg | 55 x 35 x 25 cm [E1].

                Economy | Value | 15 kg [E2].
                """);

        assertThat(answer)
                .contains("**Cabin baggage:**", "1 piece", "7 kg", "[E1]")
                .contains("**Checked baggage (Value fare):**", "15 kg", "[E2]")
                .doesNotContain(" | ");
    }

    @Test
    void labelsCancellationMatrixRowsWithoutChangingPolicyFacts() {
        String answer = present(
                "Value | More than 7 days before departure | INR 2,000 per Passenger "
                        + "| Balance after fee | Yes [E1].");

        assertThat(answer)
                .startsWith("Here are the passenger-initiated cancellation terms:")
                .contains("**Value, more than 7 days before departure:**")
                .contains("INR 2,000 per Passenger")
                .contains("Remaining base fare after the fee is refundable")
                .contains("[E1]")
                .doesNotContain(" | ");
    }

    private String present(String text) {
        return presentation.present(new AnswerPresentationService.PresentationRequest(
                Role.PASSENGER, "policy question", text, List.of(), "KB_LOOKUP", false))
                .text();
    }
}
