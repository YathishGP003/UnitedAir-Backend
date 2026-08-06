package com.unitedair.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Deterministic citation attachment required by SRS 4.1.2. */
class CitationAttacherTest {

    private final CitationAttacher attacher = new CitationAttacher();

    @Test
    @DisplayName("attaches the best matching KB handle to an uncited factual sentence")
    void attachesBestEvidence() {
        var fee = ranked(1, "A Value fare cancelled more than seven days before departure "
                + "has an INR 2,000 cancellation fee.");
        var baggage = ranked(2, "Economy passengers may check in 15 kg of baggage.");

        var result = attacher.attach(
                "The cancellation fee for this Value fare is INR 2,000.",
                List.of(fee, baggage),
                List.of());

        assertThat(result.answer())
                .isEqualTo("The cancellation fee for this Value fare is INR 2,000. [E1]");
        assertThat(result.matchedSentences()).isEqualTo(1);
        assertThat(result.unmatchedFactualSentences()).isEmpty();
    }

    @Test
    @DisplayName("does not manufacture a citation when no source supports the claim")
    void leavesUnsupportedClaimUncited() {
        var evidence = ranked(1, "A Value fare cancellation fee depends on departure timing.");

        var result = attacher.attach(
                "UnitedAir provides free hotel accommodation after every delay.",
                List.of(evidence),
                List.of());

        assertThat(result.answer())
                .isEqualTo("UnitedAir provides free hotel accommodation after every delay.");
        assertThat(result.matchedSentences()).isZero();
        assertThat(result.unmatchedFactualSentences())
                .containsExactly("UnitedAir provides free hotel accommodation after every delay.");
    }

    @Test
    @DisplayName("preserves an existing citation and can attach a live tool citation")
    void preservesExistingAndAttachesToolCitation() {
        var tool = ToolDtos.ToolOutcome.ok(
                "FlightSearchTool",
                Map.of("flightNo", "UA101", "departureTime", "10:30"),
                "UA101 departs at 10:30.",
                12,
                Instant.parse("2026-07-26T10:00:00Z"),
                Map.of());

        var result = attacher.attach(
                "The baggage allowance is 15 kg. [E2]\nUA101 departs at 10:30.",
                List.of(ranked(2, "Economy passengers may check in 15 kg of baggage.")),
                List.of(tool));

        assertThat(result.answer())
                .isEqualTo("The baggage allowance is 15 kg. [E2]\nUA101 departs at 10:30. [T1]");
        assertThat(result.matchedSentences()).isEqualTo(2);
        assertThat(result.unmatchedFactualSentences()).isEmpty();
    }

    @Test
    @DisplayName("adds the authoritative booking tool handle to a personal allowance claim")
    void personalBaggageAllowanceCannotRelyOnlyOnAGeneralPolicyCitation() {
        ToolDtos.BookingView booking = new ToolDtos.BookingView(
                "H3PL8M", "Passenger", "CONFIRMED", "UA404", "DEL", "LHR",
                LocalDate.of(2026, 8, 18), LocalTime.of(2, 35), LocalTime.of(7, 20),
                "ON_TIME", 0, "T3", "B9", "ECONOMY", "Q", "Super Saver",
                false, false, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(25940), BigDecimal.ZERO, "18C", 25,
                "BLUE", false, "B9",
                Instant.parse("2026-07-20T10:00:00Z"),
                Instant.parse("2026-07-28T10:00:00Z"));
        ToolDtos.ToolOutcome tool = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool",
                booking,
                "H3PL8M is confirmed on UA404.",
                10,
                Instant.parse("2026-07-28T10:00:00Z"),
                Map.of("operation", "RETRIEVE_BOOKING"));

        var result = attacher.attach(
                "Your baggage allowance for this booking is 25 kg of free checked baggage. [E1]",
                List.of(ranked(
                        1, "General Economy fares have several checked baggage allowances.")),
                List.of(tool));

        assertThat(result.answer()).contains("[E1] [T1]");
    }

    @Test
    @DisplayName("attaches multiple handles when one summary claim is supported collectively")
    void attachesCollectiveEvidence() {
        var wheelchair = ranked(
                1, "Wheelchair assistance is available with advance request requirements.");
        var minor = ranked(
                2, "Unaccompanied minor escort is available with a per-sector fee.");
        var pet = ranked(
                3, "Pet carriage in the cabin is available with specific requirements.");

        var result = attacher.attach(
                "Wheelchair assistance, unaccompanied minor escort and pet carriage "
                        + "are available with specific requirements and fees.",
                List.of(wheelchair, minor, pet),
                List.of());

        assertThat(result.answer()).contains("[E1]").contains("[E2]").contains("[E3]");
        assertThat(result.unmatchedFactualSentences()).isEmpty();
    }

    @Test
    @DisplayName("headings and short connective text do not demand fake citations")
    void ignoresNonFactualConnectiveText() {
        var result = attacher.attach(
                "Here is what applies.\n\nCancellation details\n\n"
                        + "A Value fare has an INR 2,000 cancellation fee.",
                List.of(ranked(1, "A Value fare has an INR 2,000 cancellation fee.")),
                List.of());

        assertThat(result.answer()).endsWith("fee. [E1]");
        assertThat(result.unmatchedFactualSentences()).isEmpty();
    }

    @Test
    @DisplayName("long bold Markdown section headings do not trigger false escalation")
    void ignoresLongBoldMarkdownSectionHeadings() {
        String answer = """
                **Fare Class Revenue Bands (Y/B/M/K/H/Q/V/W):**
                - Y is Economy Full and has the highest yield [E1].

                **Overbooking, Waitlist, and Denied Boarding Procedures:**
                - Domestic short-haul overbooking is capped at 5% [E2].
                """;

        assertThat(attacher.uncitedFactualSentences(answer)).isEmpty();
    }

    @Test
    @DisplayName("keeps decimal section numbers inside their cited sentence")
    void keepsDecimalSectionNumbersInsideSentence() {
        String answer = "6.2 GDS Booking Codes and BSP Reconciliation [E1].";

        assertThat(attacher.uncitedFactualSentences(answer)).isEmpty();
    }

    @Test
    @DisplayName("handles a large structured tool answer without overflowing the regex engine")
    void handlesLargeStructuredAnswer() {
        String answer = "UA101 departs Bangalore for Delhi at 10:30 with seats available "
                + "and current fare inventory ".repeat(2_000);
        var tool = ToolDtos.ToolOutcome.ok(
                "FlightSearchTool",
                Map.of("answer", answer),
                answer,
                12,
                Instant.parse("2026-07-26T10:00:00Z"),
                Map.of());

        var result = attacher.attach(answer, List.of(), List.of(tool));

        assertThat(result.answer()).endsWith("[T1]");
        assertThat(result.unmatchedFactualSentences()).isEmpty();
    }

    private static RetrievalDtos.Ranked ranked(int rank, String content) {
        var chunk = new RetrievalDtos.Chunk(
                (long) rank,
                "chunk-" + rank,
                "KB-AIR-00" + rank,
                "Policy",
                "2." + rank,
                1,
                "pdf",
                "fare-rule",
                "PASSENGER",
                content,
                0.9,
                0.9);
        return new RetrievalDtos.Ranked(chunk, 0.9, 0.9, rank);
    }
}
