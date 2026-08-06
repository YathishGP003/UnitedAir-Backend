package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KbFrontMatterParserTest {

    private final KbFrontMatterParser parser = new KbFrontMatterParser();

    @Test
    void separatorLineCannotBecomeDocumentTitle() {
        KbFrontMatter parsed = parser.parse(
                "====================\nBaggage Policy and Handling\n\n1 Scope",
                "KB_03.txt");

        assertThat(parsed.title()).isEqualTo("Baggage Policy and Handling");
    }

    @Test
    void parsesHeaderWithoutTreatingBodyTableAsMetadata() {
        KbFrontMatter parsed = parser.parse("""
                UnitedAir AI Knowledge Base Document
                Cancellation and Refund Policy
                Document Code | KB-AIR-004
                Audience | Passenger, Airline Staff
                Category | Fare Rules

                Fee | INR 2,000
                """, "fallback.txt");

        assertThat(parsed.documentCode()).isEqualTo("KB-AIR-004");
        assertThat(parsed.audiences()).containsExactly("Passenger", "Airline Staff");
        assertThat(parsed.srsCategory()).isEqualTo("fare-rule");
    }
}
