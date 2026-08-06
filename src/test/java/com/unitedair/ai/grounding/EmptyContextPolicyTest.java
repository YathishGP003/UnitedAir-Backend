package com.unitedair.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SRS 4.1.1 and 4.1.3. */
class EmptyContextPolicyTest {

    private final EmptyContextPolicy policy = new EmptyContextPolicy();

    @Test
    @DisplayName("the empty-context answer matches SRS 4.1.3 byte for byte")
    void answerTextIsExact() {
        // Deliberately a literal rather than a reference to the constant. Consumers and
        // the marking scheme both match on this exact string, so a well-meaning edit to
        // the wording has to fail here.
        assertThat(EmptyContextPolicy.EMPTY_CONTEXT_ANSWER)
                .isEqualTo("No matching policy found. Please contact your UnitedAir Customer Support Manager.");
    }

    @Test
    @DisplayName("the empty-context response is flagged as escalated")
    void isEscalated() {
        GroundingDtos.GroundedAnswer answer = policy.response("KB_LOOKUP", "FAST", "t", "s", 0.0);

        assertThat(answer.escalated()).isTrue();
        assertThat(answer.status()).isEqualTo(GroundingDtos.AnswerStatus.EMPTY_CONTEXT);
    }

    @Test
    @DisplayName("SRS 4.3.3: no follow-ups accompany an empty-context response")
    void emitsNoFollowups() {
        // There is nothing grounded to base a suggestion on, so suggesting anything would
        // be invention.
        assertThat(policy.response("KB_LOOKUP", "FAST", "t", "s", 0.0).followups()).isEmpty();
    }

    @Test
    @DisplayName("no citations are claimed when there was no evidence")
    void emitsNoCitations() {
        assertThat(policy.response("KB_LOOKUP", "FAST", "t", "s", 0.0).citations()).isEmpty();
    }
}
