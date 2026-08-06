package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.actions.ActionService;
import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.tools.FlightSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CommerceControllerTest {

    @Test
    void bookingLookupAlwaysUsesAuthenticatedPassengerId() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        TravelCatalogRepository catalog = mock(TravelCatalogRepository.class);
        SeatInventoryWorker seats = mock(SeatInventoryWorker.class);
        SimulatedPaymentService payments = mock(SimulatedPaymentService.class);
        BookingCreationWorker creation = mock(BookingCreationWorker.class);
        OwnedBookingRepository bookings = mock(OwnedBookingRepository.class);
        TicketDocumentService documents = mock(TicketDocumentService.class);
        FlightSearchTool flights = mock(FlightSearchTool.class);
        ActionService actions = mock(ActionService.class);
        CallbackService callbacks = mock(CallbackService.class);
        CancellationDocumentService cancellations =
                mock(CancellationDocumentService.class);
        CurrentUser current = mock(CurrentUser.class);
        when(current.require()).thenReturn(new CurrentUser.Authenticated(
                17L, "a@example.com", "A", Role.PASSENGER));
        when(bookings.detail(17L, "ABC123"))
                .thenReturn(Optional.of(BookingCreationWorkerTest.ticket("ABC123")));

        CommerceController controller = new CommerceController(
                current, drafts, catalog, seats, payments, creation,
                bookings, documents, flights, actions, callbacks, cancellations);

        assertThat(controller.booking("ABC123").pnr()).isEqualTo("ABC123");
        verify(bookings).detail(17L, "ABC123");
    }

    @Test
    void ticketDownloadIsPrivateAndUsesSafeFilename() {
        BookingDraftService drafts = mock(BookingDraftService.class);
        TravelCatalogRepository catalog = mock(TravelCatalogRepository.class);
        SeatInventoryWorker seats = mock(SeatInventoryWorker.class);
        SimulatedPaymentService payments = mock(SimulatedPaymentService.class);
        BookingCreationWorker creation = mock(BookingCreationWorker.class);
        OwnedBookingRepository bookings = mock(OwnedBookingRepository.class);
        TicketDocumentService documents = mock(TicketDocumentService.class);
        FlightSearchTool flights = mock(FlightSearchTool.class);
        ActionService actions = mock(ActionService.class);
        CallbackService callbacks = mock(CallbackService.class);
        CancellationDocumentService cancellations =
                mock(CancellationDocumentService.class);
        CurrentUser current = mock(CurrentUser.class);
        when(current.require()).thenReturn(new CurrentUser.Authenticated(
                17L, "a@example.com", "A", Role.PASSENGER));
        var ticket = BookingCreationWorkerTest.ticket("ABC123");
        when(bookings.detail(17L, "ABC123")).thenReturn(Optional.of(ticket));
        when(documents.ticketPdf(ticket)).thenReturn("%PDF-demo".getBytes());

        var response = new CommerceController(
                current, drafts, catalog, seats, payments, creation,
                bookings, documents, flights, actions, callbacks, cancellations)
                .ticketPdf("ABC123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("UnitedAir-UA101-ABC123-ticket.pdf");
    }
}
