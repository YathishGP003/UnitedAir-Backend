package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.unitedair.ai.orchestration.OrchestrationDtos;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.OperationalFailure;
import com.unitedair.ai.tools.OperationalFailureKind;
import com.unitedair.ai.tools.SimulatorRepository;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;

class BookingConversationCoordinatorTest {

    @Test
    void explicitBookingRequestStartsDraftAndAsksOnlyForMissingRoute() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        UUID draftId = UUID.randomUUID();
        when(drafts.findActive(7L, "session-1")).thenReturn(Optional.empty());
        when(drafts.startOrResume(7L, "session-1")).thenReturn(draft(draftId));

        var coordinator = new BookingConversationCoordinator(
                drafts, mock(SimulatorRepository.class),
                mock(FlightSearchTool.class), mock(TravelCatalogRepository.class));

        var turn = coordinator.handlePlanned(
                "Please book a ticket",
                bookingClassification(null, null, null),
                7L,
                "session-1");

        assertThat(turn.answer()).contains("origin", "destination");
        assertThat(turn.payload().type())
                .isEqualTo(CommerceDtos.CommerceType.BOOKING_DETAILS_REQUIRED);
        assertThat(turn.payload().draft().draftUuid()).isEqualTo(draftId);
    }

    @Test
    void routeAndDateProduceSelectableLiveFlightCards() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        SimulatorRepository simulator = mock(SimulatorRepository.class);
        FlightSearchTool flights = mock(FlightSearchTool.class);
        UUID draftId = UUID.randomUUID();
        var initial = draft(draftId);
        var updated = new CommerceDtos.BookingDraftView(
                draftId, CommerceDtos.DraftState.FLIGHTS_SHOWN, "BLR", "DEL",
                LocalDate.now().plusDays(1), "ECONOMY", null, null, null,
                null, null, 1, Instant.now().plusSeconds(1800));
        when(drafts.findActive(7L, "session-1")).thenReturn(Optional.empty());
        when(drafts.startOrResume(7L, "session-1")).thenReturn(initial);
        when(simulator.resolveAirport("Bangalore")).thenReturn("BLR");
        when(simulator.resolveAirport("Delhi")).thenReturn("DEL");
        when(drafts.applySlots(eq(7L), eq(draftId), any(), eq(0))).thenReturn(updated);
        var result = new ToolDtos.FlightSearchResult(
                "BLR", "DEL", LocalDate.now().plusDays(1), "ECONOMY",
                1, List.of(), Instant.now());
        when(flights.search(any(), eq("PASSENGER")))
                .thenReturn(new FlightSearchTool.Outcome(null, result));

        var coordinator = new BookingConversationCoordinator(
                drafts, simulator, flights,
                mock(TravelCatalogRepository.class));
        var turn = coordinator.handlePlanned(
                "Book a ticket from Bangalore to Delhi tomorrow",
                bookingClassification(
                        "Bangalore", "Delhi", LocalDate.now().plusDays(1)),
                7L,
                "session-1");

        assertThat(turn.payload().type())
                .isEqualTo(CommerceDtos.CommerceType.FLIGHT_OPTIONS);
        assertThat(turn.payload().draft().origin()).isEqualTo("BLR");
        assertThat(turn.toolsUsed()).contains("FlightSearchTool");
    }

    @Test
    void unsupportedRouteReturnsAvailableDestinationsInsteadOfCrashing() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        SimulatorRepository simulator = mock(SimulatorRepository.class);
        FlightSearchTool flights = mock(FlightSearchTool.class);
        TravelCatalogRepository catalog = mock(TravelCatalogRepository.class);
        UUID draftId = UUID.randomUUID();
        var initial = new CommerceDtos.BookingDraftView(
                draftId, CommerceDtos.DraftState.COLLECTING, "MAA", "GOI",
                null, "ECONOMY", null, null, null, null, null, 0,
                Instant.now().plusSeconds(1800));
        var updated = new CommerceDtos.BookingDraftView(
                draftId, CommerceDtos.DraftState.FLIGHTS_SHOWN, "MAA", "GOI",
                LocalDate.now(), "ECONOMY", null, null, null, null, null, 1,
                Instant.now().plusSeconds(1800));
        var hyderabad = new CommerceDtos.AirportView(
                "HYD", "Hyderabad", "Rajiv Gandhi International Airport",
                "India", true);
        var failure = new OperationalFailure(
                "flight-search", "searchFlights", OperationalFailureKind.NO_ROUTE,
                "UnitedAir does not currently operate a direct route from MAA to GOI.",
                Map.of("origin", "MAA", "destination", "GOI"));
        var envelope = new ToolDtos.ToolOutcome(
                "FlightSearchTool", false, failure, null, failure.userMessage(),
                12L, Instant.now(), Map.of());

        when(drafts.findActive(7L, "session-1"))
                .thenReturn(Optional.of(initial));
        when(drafts.applySlots(eq(7L), eq(draftId), any(), eq(0)))
                .thenReturn(updated);
        when(flights.search(any(), eq("PASSENGER")))
                .thenReturn(new FlightSearchTool.Outcome(envelope, null));
        when(catalog.destinationsFrom("MAA")).thenReturn(List.of(hyderabad));

        var coordinator = new BookingConversationCoordinator(
                drafts, simulator, flights, catalog);
        var turn = coordinator.handlePlanned(
                "today",
                bookingClassification(null, null, LocalDate.now()),
                7L,
                "session-1");

        assertThat(turn.answer()).contains(
                "does not currently operate a direct route from MAA to GOI",
                "available destination");
        assertThat(turn.payload().type())
                .isEqualTo(CommerceDtos.CommerceType.BOOKING_DETAILS_REQUIRED);
        assertThat(turn.payload().alternatives()).containsExactly(hyderabad);
        assertThat(turn.toolsUsed()).containsExactly(
                "FlightSearchTool", "TravelCatalogTool");
    }

    @Test
    void unsupportedAirportNamesTheUnavailableCityAndOffersValidDestinations() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        SimulatorRepository simulator = mock(SimulatorRepository.class);
        TravelCatalogRepository catalog = mock(TravelCatalogRepository.class);
        UUID draftId = UUID.randomUUID();
        var initial = draft(draftId);
        var dubai = new CommerceDtos.AirportView(
                "DXB", "Dubai", "Dubai International Airport",
                "United Arab Emirates", true);

        when(drafts.findActive(7L, "session-1"))
                .thenReturn(Optional.of(initial));
        when(simulator.resolveAirport("London")).thenReturn("LHR");
        when(simulator.resolveAirport("Paris")).thenReturn(null);
        when(catalog.destinationsFrom("LHR")).thenReturn(List.of(dubai));

        var coordinator = new BookingConversationCoordinator(
                drafts, simulator, mock(FlightSearchTool.class), catalog);
        var turn = coordinator.handlePlanned(
                "London to Paris",
                bookingClassification("London", "Paris", null),
                7L,
                "session-1");

        assertThat(turn.answer())
                .contains("Paris", "UnitedAir network", "available destination")
                .doesNotContain("Which origin and destination");
        assertThat(turn.payload().alternatives()).containsExactly(dubai);
    }

    @Test
    void rejectsAValidatedRouteThatIsNotForBooking() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        UUID draftId = UUID.randomUUID();
        when(drafts.findActive(7L, "session-1"))
                .thenReturn(Optional.of(draft(draftId)));
        var coordinator = new BookingConversationCoordinator(
                drafts, mock(SimulatorRepository.class),
                mock(FlightSearchTool.class), mock(TravelCatalogRepository.class));

        var policyRoute = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE,
                0.98,
                "Cancellation policy",
                null, null, null, null, null, null, null,
                List.of(), List.of(), Set.of());

        assertThatThrownBy(() -> coordinator.handlePlanned(
                "If I cancel a Value fare more than 7 days before departure, "
                        + "what fee applies and how much do I get back?",
                policyRoute,
                7L,
                "session-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activeUnconfirmedDraftExplainsThatNoPnrExistsYet() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        UUID draftId = UUID.randomUUID();
        when(drafts.findActive(7L, "session-1"))
                .thenReturn(Optional.of(draftWithRoute(draftId)));
        var coordinator = new BookingConversationCoordinator(
                drafts, mock(SimulatorRepository.class),
                mock(FlightSearchTool.class), mock(TravelCatalogRepository.class));

        var turn = coordinator.handlePlanned(
                "What is the PNR?",
                bookingClassification(null, null, null),
                7L,
                "session-1");

        assertThat(turn.answer())
                .contains("BLR", "GOI", "not confirmed", "PNR")
                .doesNotContain("BOM");
    }

    @Test
    void activeUnconfirmedDraftDoesNotCancelAnOlderConfirmedBooking() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        UUID draftId = UUID.randomUUID();
        when(drafts.findActive(7L, "session-1"))
                .thenReturn(Optional.of(draftWithRoute(draftId)));
        var coordinator = new BookingConversationCoordinator(
                drafts, mock(SimulatorRepository.class),
                mock(FlightSearchTool.class), mock(TravelCatalogRepository.class));

        var turn = coordinator.handlePlanned(
                "I want to cancel my flight",
                bookingClassification(null, null, null),
                7L,
                "session-1");

        assertThat(turn.answer())
                .contains("BLR", "GOI", "not confirmed", "booking reference")
                .doesNotContain("cancelled", "BOM");
    }

    @Test
    void activeUnconfirmedDraftKeepsBaggageQuestionAttachedToTheDraft() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        UUID draftId = UUID.randomUUID();
        when(drafts.findActive(7L, "session-1"))
                .thenReturn(Optional.of(draftWithRoute(draftId)));
        var coordinator = new BookingConversationCoordinator(
                drafts, mock(SimulatorRepository.class),
                mock(FlightSearchTool.class), mock(TravelCatalogRepository.class));

        var turn = coordinator.handlePlanned(
                "What is my baggage allowance for this booking?",
                bookingClassification(null, null, null),
                7L,
                "session-1");

        assertThat(turn.answer())
                .contains("BLR", "GOI", "fare")
                .doesNotContain("BOM", "15 kg");
    }

    @Test
    void selectedFareBaggageFollowupDoesNotClaimThatNoFareWasChosen() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        UUID draftId = UUID.randomUUID();
        var selectedFareDraft = new CommerceDtos.BookingDraftView(
                draftId, CommerceDtos.DraftState.CHECKOUT, "BLR", "GOI",
                LocalDate.now().plusDays(2), "ECONOMY",
                704L, 42L, null, null, null, 2,
                Instant.now().plusSeconds(1800));
        when(drafts.findActive(7L, "session-1"))
                .thenReturn(Optional.of(selectedFareDraft));
        var coordinator = new BookingConversationCoordinator(
                drafts, mock(SimulatorRepository.class),
                mock(FlightSearchTool.class), mock(TravelCatalogRepository.class));

        var turn = coordinator.handlePlanned(
                "What is my baggage allowance for this booking?",
                bookingClassification(null, null, null),
                7L,
                "session-1");

        assertThat(turn.answer())
                .contains("BLR", "GOI", "selected fare")
                .doesNotContain("no fare has been chosen");
    }

    private static CommerceDtos.BookingDraftView draft(UUID id) {
        return new CommerceDtos.BookingDraftView(
                id, CommerceDtos.DraftState.COLLECTING, null, null, null, "ECONOMY",
                null, null, null, null, null, 0,
                Instant.now().plusSeconds(1800));
    }

    private static OrchestrationDtos.Classification bookingClassification(
            String origin,
            String destination,
            LocalDate date) {
        return new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.BOOK_FLIGHT,
                OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                0.98,
                "Validated hosted booking route",
                origin,
                destination,
                date,
                "ECONOMY",
                null,
                null,
                null,
                List.of(),
                List.of(),
                Set.of());
    }

    private static CommerceDtos.BookingDraftView draftWithRoute(UUID id) {
        return new CommerceDtos.BookingDraftView(
                id, CommerceDtos.DraftState.FLIGHTS_SHOWN, "BLR", "GOI",
                LocalDate.now().plusDays(2), "ECONOMY",
                null, null, null, null, null, 1,
                Instant.now().plusSeconds(1800));
    }
}
