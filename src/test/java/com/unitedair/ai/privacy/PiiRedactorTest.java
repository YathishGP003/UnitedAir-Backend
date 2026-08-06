package com.unitedair.ai.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SRS 4.1.6. */
class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    static Stream<Arguments> srsExamples() {
        // Every row is an example given verbatim in SRS 4.1.6.
        return Stream.of(
                Arguments.of("PNR", "My booking is B6X9K2 please", "[AIR-PNR-REDACTED]", "B6X9K2"),
                Arguments.of("Card", "Card 4111 1111 1111 1111 was charged", "[AIR-CARD-NO-REDACTED]", "4111 1111 1111 1111"),
                Arguments.of("Passport", "Passport P1234567 expires soon", "[AIR-PASSPORT-NO-REDACTED]", "P1234567"),
                Arguments.of("FFP", "My number is ZA-12345678 thanks", "[AIR-FFP-ID-REDACTED]", "ZA-12345678"),
                Arguments.of("Aadhaar", "Aadhaar 1234 5678 9012 attached", "[AADHAAR-REDACTED]", "1234 5678 9012"),
                Arguments.of("PAN", "PAN ABCDE1234F for the invoice", "[PAN-REDACTED]", "ABCDE1234F"),
                Arguments.of("Phone", "Call me on +91 98765 43210 today", "[PHONE-REDACTED]", "98765 43210"),
                Arguments.of("Email", "Write to passenger@example.com now", "[EMAIL-REDACTED]", "passenger@example.com"),
                Arguments.of("Address", "I live at 45, Nehru Place, New Delhi 110019 currently", "[ADDRESS-REDACTED]", "Nehru Place"),
                Arguments.of("DOB", "Born 22/04/1985 in Chennai", "[DOB-REDACTED]", "22/04/1985"));
    }

    @ParameterizedTest(name = "{0} is redacted to its SRS token")
    @MethodSource("srsExamples")
    void redactsEachSrsPattern(String label, String input, String expectedToken, String secret) {
        RedactionResult result = redactor.redact(input);

        assertThat(result.redacted())
                .as("%s should be replaced by its token", label)
                .contains(expectedToken)
                .doesNotContain(secret);
    }

    @Test
    @DisplayName("the original value is recoverable from the request-scoped vault")
    void keepsOriginalsForToolResolution() {
        // Without this, redaction would break BookingManagementTool: the tool needs the
        // real PNR even though nothing durable may contain it.
        RedactionResult result = redactor.redact("Cancel booking B6X9K2 for me");

        assertThat(result.first(PiiType.PNR)).contains("B6X9K2");
        assertThat(result.redacted()).doesNotContain("B6X9K2");
    }

    @Test
    @DisplayName("a card number is not shredded into a phone number")
    void ordersNumericPatternsLongestFirst() {
        RedactionResult result = redactor.redact("Charge 4111 1111 1111 1111 to my account");

        assertThat(result.redacted()).isEqualTo("Charge [AIR-CARD-NO-REDACTED] to my account");
        assertThat(result.all(PiiType.PHONE)).isEmpty();
        assertThat(result.all(PiiType.AADHAAR)).isEmpty();
    }

    @Test
    @DisplayName("a 12-digit Aadhaar is distinguished from a 16-digit card")
    void distinguishesAadhaarFromCard() {
        assertThat(redactor.redact("1234 5678 9012").redacted()).isEqualTo("[AADHAAR-REDACTED]");
    }

    @Test
    @DisplayName("a long number that fails the Luhn check is not treated as a card")
    void luhnRejectsNonCards() {
        // A booking amount or reference must survive intact, or answers become unreadable.
        RedactionResult result = redactor.redact("Reference 1234 5678 9012 3456 7 was quoted");
        assertThat(result.all(PiiType.PAYMENT_CARD)).isEmpty();
    }

    @Test
    @DisplayName("ordinary six-letter words are not mistaken for PNRs")
    void doesNotRedactPlainWords() {
        // The PNR pattern requires both a letter and a digit precisely so that policy
        // prose survives.
        String input = "REFUND and CANCEL policies differ for DOMESTIC travel";
        assertThat(redactor.redact(input).redacted()).isEqualTo(input);
    }

    @Test
    @DisplayName("operational prose is not mistaken for a home address")
    void doesNotRedactGateReferences() {
        String input = "Proceed to Gate 12, Terminal 3 for boarding";
        assertThat(redactor.redact(input).redacted()).isEqualTo(input);
    }

    @Test
    @DisplayName("redaction is idempotent")
    void redactingTwiceChangesNothing() {
        // Guards the placeholder scheme: without it the PNR pattern would match the
        // six-character uppercase run inside a token that is already a token.
        String once = redactor.redact("PNR B6X9K2 and FFN ZA-12345678").redacted();
        String twice = redactor.redact(once).redacted();

        assertThat(twice).isEqualTo(once);
    }

    @Test
    @DisplayName("multiple values of different types are all redacted in one pass")
    void redactsEverythingInOneMessage() {
        RedactionResult result = redactor.redact(
                "I am B6X9K2, reach me at me@example.com or +91 98765 43210, born 22/04/1985");

        assertThat(result.redacted())
                .contains("[AIR-PNR-REDACTED]", "[EMAIL-REDACTED]", "[PHONE-REDACTED]", "[DOB-REDACTED]");
        assertThat(result.redactionCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("the detection summary records types but never values")
    void summaryIsSafeToPersist() {
        RedactionResult result = redactor.redact("Booking B6X9K2 and card 4111 1111 1111 1111");

        assertThat(result.detectionSummary())
                .containsEntry("PNR", 1)
                .containsEntry("PAYMENT_CARD", 1);
        assertThat(result.detectionSummary().toString()).doesNotContain("B6X9K2");
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(redactor.redact(null).redacted()).isEmpty();
        assertThat(redactor.redact("   ").hasRedactions()).isFalse();
    }
}
