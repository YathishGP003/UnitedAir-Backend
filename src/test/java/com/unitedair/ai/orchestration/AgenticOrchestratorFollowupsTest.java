package com.unitedair.ai.orchestration;

import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.commerce.RefundDtos;
import com.unitedair.ai.conversation.SessionBookingContext;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.privacy.PiiType;
import com.unitedair.ai.privacy.RedactionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgenticOrchestratorFollowupsTest {

    @Test
    void noFlightResultSuggestsChangingTheSearchInsteadOfInspectingMissingFlights() {
        ToolDtos.FlightSearchResult result = new ToolDtos.FlightSearchResult(
                "BLR", "BOM", LocalDate.of(2026, 7, 27), null,
                0, List.of(), Instant.parse("2026-07-26T12:00:00Z"));
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME, result, "No departures.", 20,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of());

        assertThat(AgenticOrchestrator.deriveToolFollowups(List.of(outcome)))
                .containsExactly(
                        "Search this route on a different date.",
                        "Search a different origin or destination.")
                .noneMatch(suggestion -> suggestion.contains("these flights"));
    }

    @Test
    void bookingResultGetsBookingSpecificFollowups() {
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", "booking", "Booking found.", 10,
                Instant.parse("2026-07-26T12:00:00Z"), Map.of());

        assertThat(AgenticOrchestrator.deriveToolFollowups(List.of(outcome)))
                .allMatch(suggestion -> suggestion.contains("booking"));
    }

    @Test
    void refundQueueGetsRefundSpecificFollowups() {
        RefundDtos.RefundStatusView refund = new RefundDtos.RefundStatusView(
                "case-1", "5MUMP8", "PENDING", new BigDecimal("33806"),
                Instant.parse("2026-08-04T01:45:05Z"),
                Instant.parse("2026-07-28T01:45:05Z"),
                "Waiting for processing.");
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", List.of(refund),
                "One pending refund.", 5, Instant.now(), Map.of());

        assertThat(AgenticOrchestrator.deriveToolFollowups(List.of(outcome)))
                .allMatch(suggestion -> suggestion.toLowerCase().contains("refund"))
                .noneMatch(suggestion -> suggestion.toLowerCase().contains("baggage"));
    }

    @Test
    void duplicateFollowupsCollapseWithoutLosingTheNextDistinctSuggestion() {
        assertThat(AgenticOrchestrator.distinctFollowups(List.of(
                "Explain passenger-Initiated Cancellation Policy.",
                "Explain passenger-Initiated Cancellation Policy.",
                "Explain refund processing timelines."), 2))
                .containsExactly(
                        "Explain passenger-Initiated Cancellation Policy.",
                        "Explain refund processing timelines.");
    }

    @Test
    void aPnrOnlyReplyDoesNotOverrideThePendingCancellationIntent() {
        IntentClassifier classifier = new IntentClassifier();
        RedactionResult redaction = new RedactionResult(
                "[AIR-PNR-REDACTED]",
                Map.of(PiiType.PNR, List.of("XBHJDM")),
                1);
        OrchestrationDtos.Classification current =
                classifier.classify(redaction.redacted(), redaction);

        assertThat(current.tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
        assertThat(AgenticOrchestrator.shouldPreserveCurrentRoute(
                current, "[AIR-PNR-REDACTED]")).isFalse();
    }

    @Test
    void routeOnlyBookingReplyKeepsItsExtractedCitiesWhileWaitingForTheDate() {
        IntentClassifier classifier = new IntentClassifier();
        String query = "Agra to Paris";
        OrchestrationDtos.Classification current = classifier.classify(
                query, new RedactionResult(query, Map.of(), 0));

        assertThat(current.tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH);
        assertThat(current.origin()).isEqualTo("Agra");
        assertThat(current.destination()).isEqualTo("Paris");
        assertThat(current.missingParameters()).containsExactly("travelDate");
        assertThat(AgenticOrchestrator.shouldPreserveCurrentRoute(
                current, query)).isTrue();
    }

    @Test
    void activePassengerDraftKeepsRouteClarificationsInsideCommerce() {
        IntentClassifier classifier = new IntentClassifier();
        String query = "Agra to Paris";
        OrchestrationDtos.Classification route = classifier.classify(
                query, new RedactionResult(query, Map.of(), 0));
        TrustedConversationState activeDraft = new TrustedConversationState(
                false, null, null, null, null, null, null, null,
                false, true, "COLLECTING");

        assertThat(AgenticOrchestrator.shouldDeliverCommerce(
                Role.PASSENGER, activeDraft, route)).isTrue();
        assertThat(AgenticOrchestrator.shouldDeliverCommerce(
                Role.PASSENGER, TrustedConversationState.empty(), route)).isFalse();
        assertThat(AgenticOrchestrator.shouldDeliverCommerce(
                Role.AIRLINE_STAFF, activeDraft, route)).isFalse();
    }

    @Test
    void failedPrivateBookingLookupBlocksUnrelatedKnowledgeBaseSubstitution() {
        IntentClassifier classifier = new IntentClassifier();
        RedactionResult redaction = new RedactionResult(
                "Show my booking for PNR [AIR-PNR-REDACTED]",
                Map.of(PiiType.PNR, List.of("A7R2JN")),
                1);
        OrchestrationDtos.Classification booking =
                classifier.classify(redaction.redacted(), redaction);
        ToolDtos.ToolOutcome failed = ToolDtos.ToolOutcome.failed(
                "BookingManagementTool",
                "No booking found for that reference.",
                8,
                Instant.parse("2026-07-27T12:00:00Z"),
                Map.of("pnrProvided", true));

        assertThat(AgenticOrchestrator.hasBlockingToolFailure(
                booking, List.of(failed))).isTrue();

        OrchestrationDtos.Classification flightSearch =
                classifier.classify("Flights from BLR to DEL tomorrow",
                        new RedactionResult(
                                "Flights from BLR to DEL tomorrow",
                                Map.of(),
                                0));
        assertThat(AgenticOrchestrator.hasBlockingToolFailure(
                flightSearch, List.of(failed))).isTrue();
    }

    @Test
    void myFlightUsesThePassengerCurrentBookingContext() throws Exception {
        Method method = AgenticOrchestrator.class
                .getDeclaredMethod("referencesCurrentBooking", String.class);
        method.setAccessible(true);

        assertThat((boolean) method.invoke(null, "I want to cancel my flight.")).isTrue();
        assertThat((boolean) method.invoke(null, "Is my flight refundable?")).isTrue();
        assertThat((boolean) method.invoke(
                null, "what is the status on the booking refernce")).isTrue();
    }

    @Test
    void oneRefundCaseBecomesTheActiveStaffBookingContext() throws Exception {
        Method method = Arrays.stream(AgenticOrchestrator.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName()
                        .equals("rememberUniqueRefundCase"))
                .findFirst()
                .orElse(null);
        assertThat(method)
                .as("the orchestrator must retain the sole refund case for follow-ups")
                .isNotNull();
        method.setAccessible(true);
        RefundDtos.RefundStatusView refund = new RefundDtos.RefundStatusView(
                "case-1", "5MUMP8", "PENDING", new BigDecimal("33806"),
                Instant.parse("2026-08-04T01:45:05Z"),
                Instant.parse("2026-07-28T01:45:05Z"),
                "Waiting for processing.");
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                "BookingManagementTool", List.of(refund),
                "One pending refund.", 5, Instant.now(), Map.of());
        SessionBookingContext context = mock(SessionBookingContext.class);

        method.invoke(
                null,
                List.of(outcome),
                context,
                "staff-session",
                Role.AIRLINE_STAFF,
                2L);

        verify(context).remember(
                "staff-session", "5MUMP8", Role.AIRLINE_STAFF, 2L);
    }

    @Test
    void genericBookingStatusUsesRefundContextInsteadOfFlightStatus() throws Exception {
        Method method = Arrays.stream(AgenticOrchestrator.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName()
                        .equals("routingQueryWithBookingContext"))
                .findFirst()
                .orElse(null);
        assertThat(method)
                .as("generic booking status must be enriched from the active refund case")
                .isNotNull();
        method.setAccessible(true);
        var reference = new SessionBookingContext.ConversationReference(
                "5MUMP8", "UA102", LocalDate.of(2026, 7, 29),
                "BLR", "DEL", "case-1");
        var details = new SessionBookingContext.BookingContext(
                "5MUMP8", "UA102", "BLR", "DEL", LocalDate.of(2026, 7, 29));

        String result = (String) method.invoke(
                null,
                "what is the status on the booking reference",
                reference,
                details);

        assertThat(result)
                .containsIgnoringCase("refund")
                .doesNotContain("Flight UA102");
    }

    @Test
    void explicitFlightStatusStillUsesTheActiveFlight() throws Exception {
        Method method = Arrays.stream(AgenticOrchestrator.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName()
                        .equals("routingQueryWithBookingContext"))
                .findFirst()
                .orElse(null);
        assertThat(method).isNotNull();
        method.setAccessible(true);
        var reference = new SessionBookingContext.ConversationReference(
                "5MUMP8", "UA102", LocalDate.of(2026, 7, 29),
                "BLR", "DEL", "case-1");
        var details = new SessionBookingContext.BookingContext(
                "5MUMP8", "UA102", "BLR", "DEL", LocalDate.of(2026, 7, 29));

        String result = (String) method.invoke(
                null,
                "is that flight on time?",
                reference,
                details);

        assertThat(result)
                .contains("Flight UA102")
                .doesNotContainIgnoringCase("refund status");
    }
}
