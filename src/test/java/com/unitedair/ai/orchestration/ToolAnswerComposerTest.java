package com.unitedair.ai.orchestration;

import com.unitedair.ai.audit.OperationalDecisionDtos;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.commerce.RefundDtos;
import com.unitedair.ai.tools.OperationalFailure;
import com.unitedair.ai.tools.OperationalFailureKind;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAnswerComposerTest {

    private final ToolAnswerComposer composer = new ToolAnswerComposer();

    @Test
    void rendersFlightSearchResultsWithAValidCitationOnEveryFactualLine() {
        ToolDtos.FareOption fare = new ToolDtos.FareOption(null,
                "Y", "Economy", "Super Saver",
                new BigDecimal("3000"), new BigDecimal("446"), new BigDecimal("3446"),
                false, true, new BigDecimal("2500"), new BigDecimal("3000"),
                15, 7, 6, 50);

        ToolDtos.FlightOption morning = new ToolDtos.FlightOption(
                101L, "UA101", "BLR", "Bengaluru", "DEL", "Delhi",
                LocalDate.of(2026, 7, 27), LocalTime.of(6, 15), LocalTime.of(9, 5),
                170, "A320", false, "SCHEDULED", 0, "1", "A4", List.of(fare));
        ToolDtos.FlightOption evening = new ToolDtos.FlightOption(
                102L, "UA102", "BLR", "Bengaluru", "DEL", "Delhi",
                LocalDate.of(2026, 7, 27), LocalTime.of(18, 40), LocalTime.of(21, 30),
                170, "A320", false, "SCHEDULED", 0, "1", "A6", List.of(fare));

        ToolDtos.FlightSearchResult result = new ToolDtos.FlightSearchResult(
                "BLR", "DEL", LocalDate.of(2026, 7, 27), null,
                2, List.of(morning, evening), Instant.parse("2026-07-26T12:00:00Z"));
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME, result, "2 departures.", 20,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of());

        String answer = composer.compose(List.of(outcome)).orElseThrow();

        assertThat(answer)
                .contains("2 UnitedAir flights")
                .contains("Bengaluru (BLR)")
                .contains("Delhi (DEL)")
                .contains("UA101")
                .contains("06:15")
                .contains("UA102")
                .contains("INR 3,446");
        assertThat(answer.lines()
                .filter(line -> !line.isBlank())
                .allMatch(line -> line.contains("[T1]")))
                .isTrue();
    }

    @Test
    void rendersAProperlyCitedNoFlightsAnswer() {
        ToolDtos.FlightSearchResult result = new ToolDtos.FlightSearchResult(
                "BLR", "DEL", LocalDate.of(2026, 7, 27), null,
                0, List.of(), Instant.parse("2026-07-26T12:00:00Z"));
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME, result, "No departures.", 20,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of());

        assertThat(composer.compose(List.of(outcome)).orElseThrow())
                .isEqualTo("I couldn't find any UnitedAir flights from BLR to DEL on 27 Jul 2026 [T1].");
    }

    @Test
    void mealAnswerUsesDatedFlightAvailabilityRatherThanTheGenericPolicyList() {
        ToolDtos.FlightOption flight = new ToolDtos.FlightOption(
                404L, "UA404", "DEL", "New Delhi", "LHR", "London",
                LocalDate.of(2026, 8, 17), LocalTime.of(2, 35), LocalTime.of(7, 20),
                585, "B787", true, "SCHEDULED", 0, "T3", "B9", List.of(),
                new ToolDtos.MealAvailability(
                        "UA404", LocalDate.of(2026, 8, 17), true,
                        Set.of("VGML", "KSML"),
                        Instant.parse("2026-08-16T21:05:00Z")));
        ToolDtos.FlightSearchResult result = new ToolDtos.FlightSearchResult(
                "DEL", "LHR", LocalDate.of(2026, 8, 17), null,
                1, List.of(flight), Instant.parse("2026-07-27T12:00:00Z"));
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME, result, "1 departure.", 20,
                Instant.parse("2026-07-27T12:00:00Z"), Map.of());

        assertThat(composer.compose(List.of(outcome), "Is VGML available?").orElseThrow())
                .contains("UA404", "VGML is available", "order by", "[T1]");
    }

    @Test
    void rendersTypedExcessBaggageQuoteAsReadableCitedProse() {
        ToolDtos.ExcessBaggageQuote quote = new ToolDtos.ExcessBaggageQuote(
                "DOMESTIC", "ECONOMY", 5,
                new BigDecimal("2500"), new BigDecimal("3250"),
                "INR", Instant.parse("2026-07-27T12:00:00Z"));
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME, quote, "quote", 5,
                quote.retrievedAt(), Map.of("operation", "QUOTE_EXCESS_BAGGAGE"));

        assertThat(composer.compose(List.of(outcome)).orElseThrow())
                .contains("5 kg", "domestic economy", "INR 2,500", "INR 3,250", "[T1]");
    }

    @Test
    void prefersTheRefundQuoteOverThePrecedingBookingLookup() {
        ToolDtos.ToolOutcome booking = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", "booking", "Booking is refundable.", 10,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of());
        ToolDtos.RefundQuote quote = new ToolDtos.RefundQuote(
                "B6X9K2", "Value", true, 200, "More than 7 days",
                new BigDecimal("4811"), new BigDecimal("2000"),
                new BigDecimal("2811"), "7-10 business days", "KB-AIR-004");
        ToolDtos.ToolOutcome quoted = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", quote,
                "Cancellation fee INR 2000; estimated refund INR 2811.", 12,
                Instant.parse("2026-07-26T12:00:01Z"), Map.of("operation", "refundQuote"));

        assertThat(composer.compose(List.of(booking, quoted)).orElseThrow())
                .contains("**Fare:** Value (refundable)")
                .contains("**Amount paid:** INR 4,811")
                .contains("**Cancellation fee:** INR 2,000")
                .contains("**Estimated refund:** INR 2,811")
                .contains("**Refund timing:** 7-10 business days")
                .contains("[T2]")
                .doesNotContain(";")
                .doesNotContain("Booking is refundable");
    }

    @Test
    void prefersCheckInEligibilityOverTheBookingLookup() {
        ToolDtos.CheckInEligibility eligibility = new ToolDtos.CheckInEligibility(
                "T7QW4Z", true, "Check-in is open", 8,
                Instant.parse("2026-07-26T10:00:00Z"),
                Instant.parse("2026-07-27T05:00:00Z"),
                false, null, "A2", List.of("WEB", "AIRPORT"));
        ToolDtos.ToolOutcome checkIn = ToolDtos.ToolOutcome.ok(
                "CheckInStatusTool", eligibility,
                "Check-in is open through web or airport channels.", 10,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of("operation", "checkInEligibility"));
        ToolDtos.ToolOutcome booking = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", "booking", "Booking is confirmed.", 10,
                Instant.parse("2026-07-26T12:00:01Z"), Map.of());

        assertThat(composer.compose(List.of(checkIn, booking)).orElseThrow())
                .contains("Check-in is open")
                .contains("[T1]")
                .doesNotContain("Booking is confirmed");
    }

    @Test
    void combinesBookingCheckInAndFlightStatusWithoutExposingRawJson() {
        Instant retrievedAt = Instant.parse("2026-07-28T12:00:00Z");
        ToolDtos.CheckInEligibility eligibility = new ToolDtos.CheckInEligibility(
                "B6X9K2", false, "Check-in opens 48 hours before departure", 155,
                Instant.parse("2026-08-02T00:45:00Z"),
                Instant.parse("2026-08-03T23:45:00Z"),
                false, "14A", null, List.of("WEB", "MOBILE", "KIOSK", "COUNTER"));
        ToolDtos.BookingView booking = new ToolDtos.BookingView(
                "B6X9K2", "Ananya Rao", "CONFIRMED", "UA101",
                "BLR", "DEL", LocalDate.of(2026, 8, 4),
                LocalTime.of(6, 15), LocalTime.of(9, 5),
                "ON_TIME", 0, "T1", "A2", "ECONOMY", "M", "Value",
                true, true, new BigDecimal("2000"), new BigDecimal("2000"),
                new BigDecimal("4811"), null, "14A", 15, "GOLD",
                false, null, retrievedAt, retrievedAt);
        ToolDtos.FlightStatusView status = new ToolDtos.FlightStatusView(
                "UA101", LocalDate.of(2026, 8, 4), "BLR", "DEL",
                "ON_TIME", 0, LocalTime.of(6, 15), LocalTime.of(6, 15),
                LocalTime.of(9, 5), "T1", "A2", null, retrievedAt);

        List<ToolDtos.ToolOutcome> outcomes = List.of(
                ToolDtos.ToolOutcome.ok(
                        "CheckInStatusTool", eligibility,
                        "Check-in unavailable for B6X9K2: Check-in opens 48 hours before departure.",
                        5, retrievedAt, Map.of("operation", "GET_CHECKIN_ELIGIBILITY")),
                ToolDtos.ToolOutcome.ok(
                        "BookingManagementTool", booking,
                        "B6X9K2: UA101 BLR-DEL, status CONFIRMED.",
                        5, retrievedAt, Map.of("operation", "RETRIEVE_BOOKING")),
                ToolDtos.ToolOutcome.ok(
                        "CheckInStatusTool", status,
                        "UA101 is on time, terminal T1, gate A2.",
                        5, retrievedAt, Map.of("operation", "GET_FLIGHT_STATUS")));

        assertThat(composer.compose(outcomes, "Can I check in and is my flight on time?")
                .orElseThrow())
                .contains("booking B6X9K2", "status CONFIRMED", "[T2]")
                .contains("15 kg checked baggage")
                .contains("Check-in is not open", "opens 48 hours", "[T1]")
                .contains("UA101 BLR-DEL", "on time", "terminal T1", "gate A2", "[T3]")
                .doesNotContain("{", "}", "\"pnr\"");
    }

    @Test
    void anEveningFollowUpOnlyRendersEveningDepartures() {
        ToolDtos.ToolOutcome outcome = flightSearchOutcome();

        String answer = composer.compose(
                List.of(outcome),
                "Which one leaves in the evening?").orElseThrow();

        assertThat(answer)
                .contains("UA102", "18:40")
                .doesNotContain("UA101", "06:15");
    }

    @Test
    void cheapestRefundableQuestionSelectsARefundableFare() {
        ToolDtos.ToolOutcome outcome = flightSearchOutcome();

        String answer = composer.compose(
                List.of(outcome),
                "Is the cheapest one refundable?").orElseThrow();

        assertThat(answer)
                .containsIgnoringCase("cheapest refundable")
                .containsIgnoringCase("refundable")
                .contains("INR 4,811")
                .contains("[T1]");
    }

    @Test
    void seatAvailabilityQuestionReportsTheAvailableCount() {
        ToolDtos.ToolOutcome outcome = flightSearchOutcome();

        String answer = composer.compose(
                List.of(outcome),
                "Are any seats left on that route?").orElseThrow();

        assertThat(answer)
                .containsIgnoringCase("seats available")
                .contains("UA101", "6")
                .contains("[T1]");
    }

    @Test
    void fareAndBaggageFollowUpReturnsTheRequestedDetails() {
        String answer = composer.compose(
                List.of(flightSearchOutcome()),
                "Show the fare and baggage details for these flights.").orElseThrow();

        assertThat(answer)
                .contains("UA101", "Super Saver", "INR 3,446")
                .contains("15 kg checked", "7 kg cabin")
                .contains("[T1]");
    }

    @Test
    void failedToolOutcomeReturnsItsSafeValidationMessage() {
        ToolDtos.ToolOutcome failed = ToolDtos.ToolOutcome.failed(
                "BookingManagementTool",
                "No booking found for that reference. Please check the 6 characters and try again.",
                8,
                Instant.parse("2026-07-26T12:00:00Z"),
                Map.of("pnrProvided", true));

        assertThat(composer.composeFailure(List.of(failed)).orElseThrow())
                .isEqualTo(
                        "No booking found for that reference. "
                                + "Please check the 6 characters and try again.");
    }

    @Test
    void typedOperationalFailureUsesItsSafeMessageWithoutParsingExceptionText() {
        OperationalFailure failure = new OperationalFailure(
                FlightSearchTool.NAME,
                "searchFlights",
                OperationalFailureKind.UNSUPPORTED_AIRPORT,
                "Ayodhya is not a supported UnitedAir airport.",
                Map.of("field", "origin"));
        ToolDtos.ToolOutcome failed = ToolDtos.ToolOutcome.failed(
                FlightSearchTool.NAME,
                failure,
                8,
                Instant.parse("2026-07-26T12:00:00Z"),
                Map.of("origin", "Ayodhya"));

        assertThat(composer.composeFailure(List.of(failed)).orElseThrow())
                .isEqualTo("Ayodhya is not a supported UnitedAir airport.");
    }

    @Test
    void bookingViewAnswerIncludesStatusAndAssignedSeat() {
        ToolDtos.BookingView booking = new ToolDtos.BookingView(
                "T7QW4Z", "Rahul Sharma", "REFUND_PENDING",
                "UA301", "BOM", "DEL", LocalDate.of(2026, 7, 28),
                LocalTime.of(9, 0), LocalTime.of(11, 10),
                "ON_TIME", 0, "T1", "A8", "ECONOMY", "Q",
                "Super Saver", false, false,
                BigDecimal.ZERO, new BigDecimal("2200"), new BigDecimal("3446"),
                new BigDecimal("1246"), "22C", 15, null, false, null,
                Instant.parse("2026-07-20T12:00:00Z"),
                Instant.parse("2026-07-26T12:00:00Z"));
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", booking, "Booking found.", 8,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of());

        assertThat(composer.compose(List.of(outcome)).orElseThrow())
                .contains("REFUND_PENDING")
                .contains("seat 22C")
                .contains("[T1]");
    }

    @Test
    void laterDepartureSeatFollowUpReportsOnlyTheSelectedFlightAvailability() {
        String answer = composer.compose(
                List.of(flightSearchOutcome()),
                "Find a flight from Bengaluru to Delhi. "
                        + "Which one is the later departure? "
                        + "Are there business seats on that one?").orElseThrow();

        assertThat(answer)
                .contains("UA102")
                .containsIgnoringCase("seats available")
                .doesNotContain("UA101");
    }

    @Test
    void refundQuoteAndBookingLookupAreCombinedWithoutDroppingRequestedFacts() {
        ToolDtos.RefundQuote quote = new ToolDtos.RefundQuote(
                "H3PL8M", "Super Saver", false, 500, "More than 7 days",
                new BigDecimal("25000"), new BigDecimal("18480"),
                new BigDecimal("6520"), "7-10 business days", "KB-AIR-004");
        ToolDtos.ToolOutcome booking = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", bookingView(), "Booking found.", 8,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of());
        ToolDtos.ToolOutcome quoted = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", quote, "Estimated refund INR 6520.", 8,
                Instant.parse("2026-07-26T12:00:01Z"), Map.of());

        assertThat(composer.compose(List.of(booking, quoted)).orElseThrow())
                .containsIgnoringCase("not refundable")
                .containsIgnoringCase("estimated refund")
                .containsIgnoringCase("amount paid")
                .containsIgnoringCase("cancellation fee")
                .contains("UA301", "status CONFIRMED", "[T1]")
                .contains("[T2]")
                .doesNotContain(";");
    }

    @Test
    void bookingRefundAndFlightStatusFallbackKeepsAllThreeVerifiedResults() {
        Instant retrievedAt = Instant.parse("2026-07-28T12:00:00Z");
        ToolDtos.RefundQuote quote = new ToolDtos.RefundQuote(
                "T7QW4Z", "Super Saver", false, 2, "Within 3 days",
                new BigDecimal("3446"), new BigDecimal("2200"),
                new BigDecimal("1246"), "5-7 working days", "KB-AIR-004");
        ToolDtos.FlightStatusView status = new ToolDtos.FlightStatusView(
                "UA301", LocalDate.of(2026, 7, 28), "BOM", "DEL",
                "ON_TIME", 0, LocalTime.of(9, 0), LocalTime.of(9, 0),
                LocalTime.of(11, 10), "T1", "A8", null, retrievedAt);
        List<ToolDtos.ToolOutcome> outcomes = List.of(
                ToolDtos.ToolOutcome.ok(
                        "CheckInStatusTool", status, "UA301 is on time.", 8,
                        retrievedAt, Map.of("operation", "GET_FLIGHT_STATUS")),
                ToolDtos.ToolOutcome.ok(
                        "BookingManagementTool", bookingView(), "Booking found.", 8,
                        retrievedAt, Map.of("operation", "RETRIEVE_BOOKING")),
                ToolDtos.ToolOutcome.ok(
                        "BookingManagementTool", quote, "Cancellation quote.", 8,
                        retrievedAt, Map.of("operation", "QUOTE_CANCELLATION")));

        assertThat(composer.compose(
                outcomes,
                "What is my flight status and cancellation refund quote?").orElseThrow())
                .contains("UA301 BOM-DEL", "on time", "terminal T1", "gate A8", "[T1]")
                .contains("status CONFIRMED", "seat 22C", "[T2]")
                .contains("Cancellation fee", "INR 2,200", "INR 1,246", "[T3]");
    }

    @Test
    void refundStatusNamesTheBookingAndVerifiedSettlementState() {
        RefundDtos.RefundStatusView status = new RefundDtos.RefundStatusView(
                "case-1", "X2LTWZ", "COMPLETED",
                new BigDecimal("1211"),
                Instant.parse("2026-08-03T03:45:00Z"),
                Instant.parse("2026-07-28T03:45:00Z"),
                "No further action is required.");
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", status, "Refund completed.", 8,
                Instant.parse("2026-07-28T03:45:01Z"), Map.of());

        assertThat(composer.compose(List.of(outcome)).orElseThrow())
                .contains("X2LTWZ", "COMPLETED", "INR 1,211",
                        "2026-07-28T03:45:00Z", "[T1]")
                .doesNotContain("estimated refund", "Expected by");
    }

    @Test
    void refundQueueUsesTheCaseReferenceInsteadOfCallingItYourBooking() {
        RefundDtos.RefundStatusView status = new RefundDtos.RefundStatusView(
                "case-8677981e", "X2LTWZ", "PENDING",
                new BigDecimal("33806"),
                Instant.parse("2026-08-04T01:45:05Z"),
                Instant.parse("2026-07-28T01:45:05Z"),
                "Waiting for processing.");
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", List.of(status),
                "One pending refund.", 8,
                Instant.parse("2026-07-28T01:45:06Z"), Map.of());

        assertThat(composer.compose(List.of(outcome)).orElseThrow())
                .contains("Refund case case-8677981e", "pending", "INR 33,806")
                .doesNotContain("Your booking", "X2LTWZ");
    }

    @Test
    void operationalDecisionAuditIsGroupedInsteadOfDumpingEveryRow() {
        var refund = new OperationalDecisionDtos.DecisionView(
                "decision-1", "action-1",
                OperationalDecisionDtos.DecisionType.REFUND_APPROVAL,
                "APPROVED", 1L, "AIRLINE_STAFF", "[AIR-PNR-REDACTED]",
                null, "KB-AIR-004", "2.1", "trace-1", "session-1",
                Map.of(), Instant.parse("2026-07-28T03:45:00Z"),
                Instant.parse("2026-07-28T03:46:00Z"));
        var upgrade = new OperationalDecisionDtos.DecisionView(
                "decision-2", "action-2",
                OperationalDecisionDtos.DecisionType.UPGRADE_AUTHORIZATION,
                "PROPOSED", 1L, "AIRLINE_STAFF", "[AIR-PNR-REDACTED]",
                null, "KB-AIR-005", "3.2", "trace-2", "session-2",
                Map.of(), Instant.parse("2026-07-28T03:47:00Z"), null);
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", List.of(refund, refund, upgrade),
                "Three decisions.", 8,
                Instant.parse("2026-07-28T03:48:00Z"),
                Map.of("operation", "QUERY_OPERATIONAL_DECISIONS"));

        String answer = composer.compose(List.of(outcome)).orElseThrow();

        assertThat(answer)
                .contains("3 operational audit records")
                .contains("Refund approvals: 2")
                .contains("approved: 2")
                .contains("Upgrade authorizations: 1")
                .contains("proposed: 1")
                .contains("trace and session references")
                .doesNotContain("- refund approval:");
    }

    @Test
    void checkInEligibilityHasPriorityOverATypedBookingLookup() {
        ToolDtos.CheckInEligibility eligibility = new ToolDtos.CheckInEligibility(
                "T7QW4Z", true, "Check-in is open", 8,
                Instant.parse("2026-07-26T10:00:00Z"),
                Instant.parse("2026-07-27T05:00:00Z"),
                false, null, "A2", List.of("WEB", "AIRPORT"));
        ToolDtos.ToolOutcome checkIn = ToolDtos.ToolOutcome.ok(
                "CheckInStatusTool", eligibility, "Check-in is open.", 8,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of());
        ToolDtos.ToolOutcome booking = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", bookingView(), "Booking found.", 8,
                Instant.parse("2026-07-26T12:00:01Z"), Map.of());

        assertThat(composer.compose(List.of(checkIn, booking)).orElseThrow())
                .contains("Check-in is open")
                .contains("[T1]")
                .doesNotContain("status CONFIRMED");
    }

    private static ToolDtos.BookingView bookingView() {
        return new ToolDtos.BookingView(
                "T7QW4Z", "Rahul Sharma", "CONFIRMED",
                "UA301", "BOM", "DEL", LocalDate.of(2026, 7, 28),
                LocalTime.of(9, 0), LocalTime.of(11, 10),
                "ON_TIME", 0, "T1", "A8", "ECONOMY", "Q",
                "Super Saver", false, false,
                BigDecimal.ZERO, new BigDecimal("2200"), new BigDecimal("3446"),
                null, "22C", 15, null, false, null,
                Instant.parse("2026-07-20T12:00:00Z"),
                Instant.parse("2026-07-26T12:00:00Z"));
    }

    private static ToolDtos.ToolOutcome flightSearchOutcome() {
        ToolDtos.FareOption saver = new ToolDtos.FareOption(null,
                "Y", "Economy", "Super Saver",
                new BigDecimal("3000"), new BigDecimal("446"), new BigDecimal("3446"),
                false, true, new BigDecimal("2500"), new BigDecimal("3000"),
                15, 7, 6, 50);
        ToolDtos.FareOption value = new ToolDtos.FareOption(null,
                "M", "Economy", "Value",
                new BigDecimal("4300"), new BigDecimal("511"), new BigDecimal("4811"),
                true, true, new BigDecimal("2000"), new BigDecimal("2000"),
                15, 7, 4, 75);
        ToolDtos.FlightOption morning = new ToolDtos.FlightOption(
                101L, "UA101", "BLR", "Bengaluru", "DEL", "Delhi",
                LocalDate.of(2026, 7, 27), LocalTime.of(6, 15), LocalTime.of(9, 5),
                170, "A320", false, "SCHEDULED", 0, "1", "A4",
                List.of(saver, value));
        ToolDtos.FlightOption evening = new ToolDtos.FlightOption(
                102L, "UA102", "BLR", "Bengaluru", "DEL", "Delhi",
                LocalDate.of(2026, 7, 27), LocalTime.of(18, 40), LocalTime.of(21, 30),
                170, "A320", false, "SCHEDULED", 0, "1", "A6",
                List.of(saver, value));
        ToolDtos.FlightSearchResult result = new ToolDtos.FlightSearchResult(
                "BLR", "DEL", LocalDate.of(2026, 7, 27), null,
                2, List.of(morning, evening), Instant.parse("2026-07-26T12:00:00Z"));
        return ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME, result, "2 departures.", 20,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of());
    }
}
