package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import org.junit.jupiter.api.Test;

class AnswerPresentationServiceTest {

    private final AnswerPresentationService service = new AnswerPresentationService();

    @Test
    void initialCancellationAnswerConvertsRawHeaderAndRowsIntoPassengerCopy() {
        String raw = """
                2 Passenger-initiated Cancellation Policy [E1].

                Fare Category | Cancellation Timing | Cancellation Fee | Refund of Base Fare | Refund of Taxes [E1].

                Full Flex | Any time up to 2 hours before departure | Nil | Full base fare | Yes [E1].

                Promotional / Sale Fare | Any time | 100% forfeited | Nil | Statutory taxes only [E1].
                """;

        var answer = service.present(request(raw, false));

        assertThat(answer.valid()).isTrue();
        assertThat(answer.usedDeterministicFallback()).isTrue();
        assertThat(answer.text())
                .startsWith("Here are the passenger-initiated cancellation terms")
                .contains("**Full Flex")
                .contains("No cancellation fee")
                .contains("Full base fare is refundable")
                .contains("taxes are refundable")
                .contains("**Promotional / Sale Fare")
                .contains("100% forfeited")
                .contains("Statutory taxes only")
                .contains("[E1]")
                .doesNotContain("Fare Category |")
                .doesNotContain(" | ")
                .doesNotContain(" Nil ")
                .doesNotContain("2 Passenger-initiated");
    }

    @Test
    void regenerationUsesTheSameRawEvidenceProtectionAsInitialDelivery() {
        String raw = """
                Fare Category | Cancellation Timing | Cancellation Fee | Refund of Base Fare | Refund of Taxes [E1].
                Value | More than 7 days before departure | INR 2,000 per Passenger | Balance after fee | Yes [E1].
                """;

        var first = service.present(request(raw, false));
        var regenerated = service.present(request(raw, true));

        assertThat(first.text()).isEqualTo(regenerated.text());
        assertThat(first.text())
                .contains("**Value, more than 7 days before departure:**")
                .contains("Cancellation fee: INR 2,000 per Passenger")
                .contains("Remaining base fare after the fee is refundable")
                .contains("taxes are refundable")
                .doesNotContain("Fare Category |");
    }

    @Test
    void offlineDashDelimitedCancellationRowsBecomeReadablePolicyCopy() {
        String raw = """
                2.1 Cancellation Fee Matrix by Fare Type [E1].

                Full Flex - Any time up to 2 hours before departure - Nil - Full base fare - Yes [E1].

                Saver / Super Saver - Any time - 100% of base fare forfeited - Nil - Statutory taxes refunded (PSF, UDF) [E1].
                """;

        var answer = service.present(request(raw, false));

        assertThat(answer.text())
                .startsWith("Here are the passenger-initiated cancellation terms")
                .contains("No cancellation fee")
                .contains("Full base fare is refundable")
                .contains("No base fare is refundable")
                .doesNotContain(" - Nil -", " Nil ");
    }

    @Test
    void cleanStructuredAnswerKeepsItsWordingAndCitationHandles() {
        String clean = """
                Here are the applicable cancellation terms [E1].

                - **Value fare:** The cancellation fee is INR 2,000 per passenger [E1].
                """;

        var answer = service.present(request(clean, false));

        assertThat(answer.text()).isEqualTo(clean.trim());
        assertThat(answer.usedDeterministicFallback()).isFalse();
        assertThat(answer.repairReason()).isNull();
    }

    @Test
    void adjacentEvidenceAndToolHandlesRemainVisuallySeparate() {
        String answer = "The flight is on time [T1][T2].";

        var presented = service.present(new AnswerPresentationService.PresentationRequest(
                Role.PASSENGER, "Is it on time?", answer, List.of(),
                "TOOL_CALL", false));

        assertThat(presented.text()).isEqualTo("The flight is on time [T1] [T2].");
    }

    @Test
    void rawJsonReceivesSafePassengerCopyAtTheFinalPresentationBoundary() {
        String raw = """
                {"pnr":"your booking reference","status":"CONFIRMED",
                 "flightNo":"UA101","checkedBaggageKg":15}
                """;

        var presented = service.present(new AnswerPresentationService.PresentationRequest(
                Role.PASSENGER, "Show my booking.", raw, List.of(),
                "TOOL_CALL", false));

        assertThat(presented.text())
                .isEqualTo("I couldn’t safely format that result. Please try again.");
        assertThat(presented.repairReason())
                .isEqualTo("RAW_STRUCTURED_PAYLOAD");
        assertThat(presented.text()).doesNotContain("{", "pnr", "UA101");
    }

    @Test
    void cleanHostedCancellationAnswerIsNotReplacedByRetrievedMatrix() {
        String incomplete = """
                Here are the passenger-initiated cancellation terms:

                - **Full Flex:** No cancellation fee [E1].
                - **Value within 3 days:** INR 4,000 per passenger [E1].
                """;
        String matrix = """
                Fare Category | Cancellation Timing | Cancellation Fee | Refund of Base Fare | Refund of Taxes
                Saver / Super Saver | Any time | 100% of base fare forfeited | Nil | Statutory taxes refunded
                Value | More than 7 days before departure | INR 2,000 per Passenger | Balance after fee | Yes
                Value | 3-7 days before departure | INR 3,000 per Passenger | Balance after fee | Yes
                Value | Within 3 days of departure | INR 4,000 per Passenger | Balance after fee | Yes
                Flex | More than 3 hours before departure | INR 1,000 per Passenger | Balance after fee | Yes
                Full Flex | Any time up to 2 hours before departure | Nil | Full base fare | Yes
                Business Saver | More than 7 days before departure | INR 4,000 per Passenger | Balance after fee | Yes
                Business Saver | Within 7 days of departure | INR 5,000 per Passenger | Balance after fee | Yes
                Business Flex | Any time up to 2 hours before departure | Nil | Full base fare | Yes
                Promotional / Sale Fare | Any time | 100% forfeited | Nil | Statutory taxes only
                """;
        RetrievalDtos.Chunk chunk = new RetrievalDtos.Chunk(
                1L, "chunk-1", "KB-AIR-004", "Cancellation policy",
                "Passenger-Initiated Cancellation Policy", 2, "TXT", "fare-rule",
                "passenger", matrix, 0.9, 0.9);
        RetrievalDtos.Ranked evidence =
                new RetrievalDtos.Ranked(chunk, 0.9, 0.9, 1);

        var answer = service.present(new AnswerPresentationService.PresentationRequest(
                Role.PASSENGER, "what is the cancelation policy?", incomplete,
                List.of(evidence), "KB_LOOKUP", false));

        assertThat(answer.text()).isEqualTo(incomplete.trim());
        assertThat(answer.usedDeterministicFallback()).isFalse();
    }

    @Test
    void compoundBookingAndPolicyAnswerIsNeverReplacedByThePolicyMatrix() {
        String generated = """
                Your booking is confirmed for flight UA404 [T1].

                Saver and Super Saver fares forfeit the base fare [E1].
                Value fares use timing-based cancellation fees [E1].
                Refund timing depends on the original payment method [E2].
                """;
        String matrix = """
                Fare Category | Cancellation Timing | Cancellation Fee | Refund of Base Fare | Refund of Taxes
                Saver / Super Saver | Any time | 100% forfeited | Nil | Statutory taxes refunded
                Value | More than 7 days before departure | INR 2,000 per Passenger | Balance after fee | Yes
                Value | 3-7 days before departure | INR 3,000 per Passenger | Balance after fee | Yes
                Value | Within 3 days of departure | INR 4,000 per Passenger | Balance after fee | Yes
                Flex | More than 3 hours before departure | INR 1,000 per Passenger | Balance after fee | Yes
                Full Flex | Any time up to 2 hours before departure | Nil | Full base fare | Yes
                """;
        RetrievalDtos.Chunk chunk = new RetrievalDtos.Chunk(
                1L, "chunk-1", "KB-AIR-004", "Cancellation policy",
                "Passenger-Initiated Cancellation Policy", 2, "TXT", "fare-rule",
                "passenger", matrix, 0.9, 0.9);

        var answer = service.present(new AnswerPresentationService.PresentationRequest(
                Role.PASSENGER,
                "What is my PNR status and tell me the cancellation and refund policy?",
                generated,
                List.of(new RetrievalDtos.Ranked(chunk, 0.9, 0.9, 1)),
                "TOOL_PLUS_KB",
                false));

        assertThat(answer.text()).isEqualTo(generated.trim());
        assertThat(answer.usedDeterministicFallback()).isFalse();
    }

    @Test
    void baggageRowsReceiveLabelsInsteadOfRawPipeFormatting() {
        String raw = """
                Economy | 1 piece | 7 kg | 55 x 35 x 25 cm [E1].
                Economy | Value | 15 kg [E2].
                """;

        var answer = service.present(new AnswerPresentationService.PresentationRequest(
                Role.PASSENGER, "What baggage can I take?", raw, List.of(),
                "KB_LOOKUP", false));

        assertThat(answer.text())
                .contains("**Cabin baggage:** 1 piece, up to 7 kg")
                .contains("**Checked baggage (Value fare):** 15 kg")
                .contains("[E1]", "[E2]")
                .doesNotContain(" | ");
    }

    @Test
    void seatTableUsesItsOwnColumnLabelsInsteadOfCancellationLabels() {
        String raw = """
                Seat Category | Description | Domestic Fee | International Fee [E1].
                Preferred Economy | Front Economy rows 1-5 | INR 400-800 | USD 10-20 [E1].
                Business Window | Window seat in Business cabin | Included in Business fare | Included [E1].
                """;

        var answer = service.present(new AnswerPresentationService.PresentationRequest(
                Role.PASSENGER, "What seat types and fees are available?", raw, List.of(),
                "KB_LOOKUP", false));

        assertThat(answer.text())
                .contains("**Preferred Economy:** Front Economy rows 1-5")
                .contains("**Domestic fee:** INR 400-800")
                .contains("**International fee:** USD 10-20")
                .contains("**Business Window:** Window seat in Business cabin")
                .doesNotContain("Cancellation fee")
                .doesNotContain("Seat Category |")
                .doesNotContain(" | ");
    }

    private AnswerPresentationService.PresentationRequest request(
            String generatedText, boolean regenerated) {
        return new AnswerPresentationService.PresentationRequest(
                Role.PASSENGER,
                "what is cancellation polic",
                generatedText,
                List.<RetrievalDtos.Ranked>of(),
                "KB_LOOKUP",
                regenerated);
    }
}
