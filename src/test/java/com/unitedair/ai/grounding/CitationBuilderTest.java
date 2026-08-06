package com.unitedair.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SRS 4.1.2 - citation coverage measurement. */
class CitationBuilderTest {

    private final CitationBuilder builder = new CitationBuilder();

    @Test
    @DisplayName("a citation trailing its sentence counts for that sentence")
    void creditsTrailingCitations() {
        // This is the regression that matters. Splitting on sentence boundaries first
        // attaches "[E1]" to the FOLLOWING sentence, so the sentence it supports scores as
        // uncited. That drove coverage to 0.09 on a perfectly good answer and escalated it.
        String answer = "A Value fare cancelled more than seven days before departure incurs a fee. [E1] "
                + "Taxes are refunded in full to the original payment method. [E2]";

        assertThat(builder.coverage(answer)).isEqualTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("a citation inside its sentence also counts")
    void creditsInlineCitations() {
        String answer = "A Value fare cancelled more than seven days before departure incurs a fee [E1]. "
                + "Taxes are refunded in full to the original payment method [E2].";

        assertThat(builder.coverage(answer)).isEqualTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("an uncited substantive sentence lowers coverage")
    void penalisesUncitedClaims() {
        String answer = "A Value fare cancelled more than seven days before departure incurs a fee [E1]. "
                + "The refund is usually processed within about three working days in practice.";

        assertThat(builder.coverage(answer)).isEqualTo(0.5, within(0.001));
    }

    @Test
    @DisplayName("an answer with no citations at all scores zero")
    void zeroWhenNothingIsCited() {
        assertThat(builder.coverage("Cancellation fees vary depending on the fare you purchased."))
                .isZero();
    }

    @Test
    @DisplayName("short connective sentences are not counted against coverage")
    void ignoresShortConnectives() {
        // Otherwise the model is pushed to litter the answer with markers and coverage
        // stops being a quality signal.
        String answer = "Here is what applies. "
                + "A Value fare cancelled more than seven days before departure incurs a fee [E1].";

        assertThat(builder.coverage(answer)).isEqualTo(1.0, within(0.001));
    }

    @Test
    void extractsCitedHandles() {
        assertThat(builder.citedHandles("Fee applies [E1]. Taxes refunded [E3]."))
                .containsExactly("E1", "E3");
    }

    @Test
    @DisplayName("markers are rendered as readable references for the reader")
    void rendersHumanReadableCitations() {
        var citation = GroundingDtos.Citation.fromKb(
                "E1", "KB-AIR-004", "Cancellation", "2.1 Cancellation Fee Matrix", 1, "fare-rule", 0.8, "…");

        assertThat(builder.renderCitations("A fee applies [E1].", java.util.List.of(citation)))
                .isEqualTo("A fee applies [KB-AIR-004 2.1 Cancellation Fee Matrix].");
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(builder.coverage(null)).isZero();
        assertThat(builder.coverage("  ")).isZero();
    }
}
