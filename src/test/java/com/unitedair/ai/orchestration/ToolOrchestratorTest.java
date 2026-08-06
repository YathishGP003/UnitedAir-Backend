package com.unitedair.ai.orchestration;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.shared.UnitedAirProperties;
import com.unitedair.ai.tools.BookingManagementTool;
import com.unitedair.ai.tools.CheckInStatusTool;
import com.unitedair.ai.tools.DisruptionRecoveryWorker;
import com.unitedair.ai.tools.EscalationTool;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.commerce.RefundOperationsWorker;
import com.unitedair.ai.commerce.RefundDtos;
import com.unitedair.ai.audit.OperationalDecisionService;
import com.unitedair.ai.tools.BookingAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolOrchestratorTest {

    private final FlightSearchTool flights = mock(FlightSearchTool.class);
    private final RefundOperationsWorker refunds = mock(RefundOperationsWorker.class);
    private final OperationalDecisionService decisions = mock(OperationalDecisionService.class);
    private final ToolOrchestrator orchestrator = new ToolOrchestrator(
            flights,
            mock(BookingManagementTool.class),
            mock(CheckInStatusTool.class),
            mock(EscalationTool.class),
            mock(DisruptionRecoveryWorker.class),
            refunds,
            decisions,
            mock(com.unitedair.ai.operations.OperationalDataAgent.class),
            new UnitedAirProperties());

    @Test
    void retriesATransientToolFailureOnceWithinTheBudget() {
        ToolDtos.ToolOutcome failed = ToolDtos.ToolOutcome.failed(
                FlightSearchTool.NAME, "Connection temporarily unavailable",
                20, Instant.now(), Map.of());
        ToolDtos.FlightSearchResult data = new ToolDtos.FlightSearchResult(
                "BLR", "DEL", LocalDate.now().plusDays(1), null,
                0, List.of(), Instant.now());
        ToolDtos.ToolOutcome succeeded = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME, data, "No departures.",
                15, Instant.now(), Map.of());
        when(flights.search(any(), anyString()))
                .thenReturn(new FlightSearchTool.Outcome(failed, null))
                .thenReturn(new FlightSearchTool.Outcome(succeeded, data));

        List<ToolDtos.ToolOutcome> outcomes = orchestrator.execute(
                flightSearch(), Role.PASSENGER, "session", "trace");

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(1).success()).isTrue();
        verify(flights, times(2)).search(any(), anyString());
    }

    @Test
    void doesNotRetryAValidationFailure() {
        ToolDtos.ToolOutcome failed = ToolDtos.ToolOutcome.failed(
                FlightSearchTool.NAME, "I could not recognise Atlantis as a UnitedAir destination.",
                10, Instant.now(), Map.of());
        when(flights.search(any(), anyString()))
                .thenReturn(new FlightSearchTool.Outcome(failed, null));

        assertThat(orchestrator.execute(
                flightSearch(), Role.PASSENGER, "session", "trace")).hasSize(1);
        verify(flights).search(any(), anyString());
    }

    @Test
    void seatMapTargetUsesTheFlightSearchToolWithFlightNumberAndDate() {
        LocalDate date = LocalDate.now().plusDays(1);
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME,
                Map.of("flightNo", "UA101", "availableSeats", 42),
                "UA101 has 42 available seats.",
                15,
                Instant.now(),
                Map.of("flightNo", "UA101", "date", date.toString(), "operation", "seatMap"));
        when(flights.seatMapForFlight("UA101", date, "PASSENGER")).thenReturn(outcome);
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.SEAT_MAP,
                0.92,
                "seat map",
                null, null, date, null, null, "UA101", null,
                List.of(), List.of(), Set.of());

        assertThat(orchestrator.execute(
                classification, Role.PASSENGER, "session", "trace"))
                .containsExactly(outcome);
        verify(flights).seatMapForFlight("UA101", date, "PASSENGER");
    }

    @Test
    void missingFlightDateNeverSilentlySearchesTomorrow() {
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                0.9,
                "route without date",
                "BLR", "DEL", null,
                null, null, null, null,
                List.of("travelDate"), List.of(), Set.of());

        List<ToolDtos.ToolOutcome> outcomes = orchestrator.execute(
                classification, Role.PASSENGER, "session", "trace");

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.success()).isFalse();
            assertThat(outcome.errorMessage()).containsIgnoringCase("travel date");
        });
        verifyNoInteractions(flights);
    }

    @Test
    void refundStatusDispatchesThroughTheAuthorizedRefundWorker() {
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                BookingManagementTool.NAME,
                "status",
                "Refund is processing.",
                5,
                Instant.now(),
                Map.of("operation", RefundOperationsWorker.STATUS_OPERATION));
        when(refunds.status(
                "B6X9K2",
                BookingAccess.of(Role.PASSENGER, 42L))).thenReturn(outcome);
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.REFUND_STATUS,
                0.98,
                "refund status",
                null, null, null, null, "B6X9K2", null, null,
                List.of(), List.of(), Set.of());

        assertThat(orchestrator.execute(
                classification, Role.PASSENGER, 42L, "session", "trace"))
                .containsExactly(outcome);
        verify(refunds).status(
                "B6X9K2",
                BookingAccess.of(Role.PASSENGER, 42L));
    }

    @Test
    void refundCaseQueuePassesTheDueBeforeFilterToTheWorker() {
        LocalDate dueDate = LocalDate.now().plusDays(2);
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                BookingManagementTool.NAME,
                List.of(),
                "No refund cases match.",
                5,
                Instant.now(),
                Map.of("operation", RefundOperationsWorker.LIST_OPERATION));
        when(refunds.listCases(any(), any())).thenReturn(outcome);
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.REFUND_CASES,
                0.98,
                "refund cases",
                null, null, dueDate, null, null, null, null,
                List.of(), List.of(), Set.of());

        assertThat(orchestrator.execute(
                classification, Role.AIRLINE_STAFF, 7L, "session", "trace"))
                .containsExactly(outcome);
        verify(refunds).listCases(
                org.mockito.ArgumentMatchers.argThat(
                        (RefundDtos.RefundCaseQuery query) ->
                                query.dueBefore() != null && query.limit() == 50),
                org.mockito.ArgumentMatchers.eq(
                        BookingAccess.of(Role.AIRLINE_STAFF, 7L)));
    }

    @Test
    void pendingRefundQueuePassesTheRequestedStatusToTheWorker() {
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                BookingManagementTool.NAME,
                List.of(),
                "No pending refund cases match.",
                5,
                Instant.now(),
                Map.of("operation", RefundOperationsWorker.LIST_OPERATION));
        when(refunds.listCases(any(), any())).thenReturn(outcome);
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.REFUND_CASES,
                0.98,
                "pending refunds",
                null, null, null, null, null, null, null,
                List.of("status:PENDING"), List.of(), Set.of());

        assertThat(orchestrator.execute(
                classification, Role.AIRLINE_STAFF, 7L, "session", "trace"))
                .containsExactly(outcome);
        verify(refunds).listCases(
                org.mockito.ArgumentMatchers.argThat(
                        (RefundDtos.RefundCaseQuery query) ->
                                "PENDING".equals(query.status())
                                        && query.dueBefore() == null
                                        && query.limit() == 50),
                org.mockito.ArgumentMatchers.eq(
                        BookingAccess.of(Role.AIRLINE_STAFF, 7L)));
    }

    @Test
    void mealAvailabilityDispatchesThroughFlightSearchFamily() {
        LocalDate date = LocalDate.of(2026, 8, 17);
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME,
                new ToolDtos.MealAvailability(
                        "UA404", date, true, Set.of("VGML"), Instant.now()),
                "VGML is available.",
                5, Instant.now(), Map.of("operation", "GET_MEAL_AVAILABILITY"));
        when(flights.mealAvailabilityForFlight(
                "UA404", date, "VGML", "PASSENGER")).thenReturn(outcome);
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.MEAL_AVAILABILITY,
                0.94, "meal", null, null, date, null, null, "UA404", null,
                List.of("sop"), List.of(), Set.of("KB-AIR-006"),
                "VGML", null, null);

        assertThat(orchestrator.execute(
                classification, Role.PASSENGER, "session", "trace"))
                .containsExactly(outcome);
        verify(flights).mealAvailabilityForFlight(
                "UA404", date, "VGML", "PASSENGER");
    }

    @Test
    void excessBaggageQuoteDispatchesWithTypedDimensions() {
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME,
                new ToolDtos.ExcessBaggageQuote(
                        "DOMESTIC", "ECONOMY", 5,
                        new java.math.BigDecimal("2500"),
                        new java.math.BigDecimal("3250"),
                        "INR", Instant.now()),
                "5 kg excess quote.",
                5, Instant.now(), Map.of("operation", "QUOTE_EXCESS_BAGGAGE"));
        when(flights.quoteExcessBaggage(
                "DOMESTIC", "ECONOMY", 5, "PASSENGER")).thenReturn(outcome);
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.EXCESS_BAGGAGE_QUOTE,
                0.94, "baggage", null, null, null, "ECONOMY", null, null, null,
                List.of("fare-rule"), List.of(), Set.of("KB-AIR-003"),
                null, 5, "DOMESTIC");

        assertThat(orchestrator.execute(
                classification, Role.PASSENGER, "session", "trace"))
                .containsExactly(outcome);
        verify(flights).quoteExcessBaggage(
                "DOMESTIC", "ECONOMY", 5, "PASSENGER");
    }

    private OrchestrationDtos.Classification flightSearch() {
        return new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                0.9,
                "route",
                "BLR", "DEL", LocalDate.now().plusDays(1),
                null, null, null, null,
                List.of(), List.of(), Set.of());
    }
}
