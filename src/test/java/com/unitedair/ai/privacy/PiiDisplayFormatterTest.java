package com.unitedair.ai.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiDisplayFormatterTest {

    private final PiiDisplayFormatter formatter = new PiiDisplayFormatter();

    @Test
    void labelledPnrBecomesOneReadableReference() {
        assertThat(formatter.display(
                "PNR [AIR-PNR-REDACTED] is confirmed.",
                PiiDisplayFormatter.DisplayContext.ASSISTANT))
                .isEqualTo("your booking reference is confirmed.")
                .doesNotContainIgnoringCase("PNR your booking");
    }

    @Test
    void leadingBookingTokenBecomesReadableBookingAnswer() {
        assertThat(formatter.display(
                "[AIR-PNR-REDACTED]: UA404 DEL-LHR.",
                PiiDisplayFormatter.DisplayContext.ASSISTANT))
                .isEqualTo("Your booking: UA404 DEL-LHR.");
    }

    @Test
    void composedBookingSentenceDoesNotRepeatYour() {
        assertThat(formatter.display(
                "Your booking [AIR-PNR-REDACTED] is for flight UA102.",
                PiiDisplayFormatter.DisplayContext.ASSISTANT))
                .isEqualTo("Your booking is for flight UA102.");
    }

    @Test
    void flightFollowedByHiddenPnrBecomesAReadableBookingSubject() {
        assertThat(formatter.display(
                "Your flight [AIR-PNR-REDACTED] (UA101 BLR-DEL) can be cancelled.",
                PiiDisplayFormatter.DisplayContext.ASSISTANT))
                .isEqualTo("Your booking (UA101 BLR-DEL) can be cancelled.")
                .doesNotContainIgnoringCase("flight your booking");
    }

    @Test
    void possessiveDirectTokenDoesNotBecomeYourYourBookingReference() {
        assertThat(formatter.display(
                "Your [AIR-PNR-REDACTED], and the flight is currently on time.",
                PiiDisplayFormatter.DisplayContext.ASSISTANT))
                .isEqualTo("Your booking reference, and the flight is currently on time.")
                .doesNotContainIgnoringCase("your your");
    }

    @Test
    void possessiveLabelledPnrDoesNotBecomeYourYourBookingReference() {
        assertThat(formatter.display(
                "Your PNR is [AIR-PNR-REDACTED].",
                PiiDisplayFormatter.DisplayContext.ASSISTANT))
                .isEqualTo("Your booking reference.")
                .doesNotContainIgnoringCase("your your");
    }

    @Test
    void removesAHiddenPnrClauseWithoutLeavingBrokenGrammar() {
        assertThat(formatter.display(
                "Your PNR is [AIR-PNR-REDACTED], and your fare is Economy.",
                PiiDisplayFormatter.DisplayContext.ASSISTANT))
                .isEqualTo("Your fare is Economy.");
    }

    @Test
    void repairsMojibakeApostrophesFromHostedText() {
        assertThat(formatter.display(
                "Iâm sorry, I canât perform that request.",
                PiiDisplayFormatter.DisplayContext.ASSISTANT))
                .isEqualTo("I’m sorry, I can’t perform that request.");
    }

    @Test
    void markdownWrappedBookingTokenRemainsReadable() {
        assertThat(formatter.display(
                "The refund for booking **[AIR-PNR-REDACTED]** is completed.",
                PiiDisplayFormatter.DisplayContext.ASSISTANT))
                .isEqualTo("The refund for your booking reference is completed.");
    }
}
