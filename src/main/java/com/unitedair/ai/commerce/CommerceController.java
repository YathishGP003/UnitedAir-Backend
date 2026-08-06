package com.unitedair.ai.commerce;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.actions.ActionService;
import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.ToolDtos;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce")
@PreAuthorize("hasRole('PASSENGER')")
public class CommerceController {

    private final CurrentUser currentUser;
    private final BookingDraftService drafts;
    private final TravelCatalogRepository catalog;
    private final SeatInventoryWorker seats;
    private final SimulatedPaymentService payments;
    private final BookingCreationWorker creation;
    private final OwnedBookingRepository bookings;
    private final TicketDocumentService documents;
    private final FlightSearchTool flights;
    private final ActionService actions;
    private final CallbackService callbacks;
    private final CancellationDocumentService cancellationDocuments;

    public CommerceController(
            CurrentUser currentUser,
            BookingDraftService drafts,
            TravelCatalogRepository catalog,
            SeatInventoryWorker seats,
            SimulatedPaymentService payments,
            BookingCreationWorker creation,
            OwnedBookingRepository bookings,
            TicketDocumentService documents,
            FlightSearchTool flights,
            ActionService actions,
            CallbackService callbacks,
            CancellationDocumentService cancellationDocuments) {
        this.currentUser = currentUser;
        this.drafts = drafts;
        this.catalog = catalog;
        this.seats = seats;
        this.payments = payments;
        this.creation = creation;
        this.bookings = bookings;
        this.documents = documents;
        this.flights = flights;
        this.actions = actions;
        this.callbacks = callbacks;
        this.cancellationDocuments = cancellationDocuments;
    }

    @GetMapping("/airports")
    public List<CommerceDtos.AirportView> airports() {
        currentUser.require();
        return catalog.airports();
    }

    @GetMapping("/airports/{origin}/destinations")
    public List<CommerceDtos.AirportView> destinations(@PathVariable String origin) {
        currentUser.require();
        return catalog.destinationsFrom(origin.trim().toUpperCase(Locale.ROOT));
    }

    @GetMapping("/flights")
    public ToolDtos.FlightSearchResult flights(
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String cabin) {
        currentUser.require();
        return flights.search(
                new ToolDtos.FlightSearchRequest(
                        origin, destination, date, cabin, 1, 0, 0),
                "PASSENGER").data();
    }

    @PostMapping("/drafts")
    public CommerceDtos.BookingDraftView start(@RequestBody(required = false) DraftStart request) {
        CurrentUser.Authenticated user = passenger();
        return drafts.startOrResume(
                user.id(), request == null ? null : request.sessionUuid());
    }

    @GetMapping("/drafts/{draftId}")
    public CommerceDtos.BookingDraftView draft(@PathVariable UUID draftId) {
        return drafts.requireOwned(passenger().id(), draftId);
    }

    @PatchMapping("/drafts/{draftId}")
    public CommerceDtos.BookingDraftView patch(
            @PathVariable UUID draftId,
            @Valid @RequestBody DraftUpdate request) {
        return drafts.applySlots(
                passenger().id(), draftId, request.patch(), request.expectedVersion());
    }

    @DeleteMapping("/drafts/{draftId}")
    public ResponseEntity<Void> abandon(@PathVariable UUID draftId) {
        drafts.abandon(passenger().id(), draftId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/drafts/{draftId}/seats")
    public List<ToolDtos.SeatOption> seats(@PathVariable UUID draftId) {
        var draft = drafts.requireOwned(passenger().id(), draftId);
        if (draft.flightInstanceId() == null) {
            throw new ApiExceptions.Conflict("Choose a flight before selecting a seat.");
        }
        return seats.available(draft.flightInstanceId(), draft.cabin());
    }

    @PostMapping("/payments/authorize")
    public CommerceDtos.PaymentView authorize(
            @RequestBody CommerceDtos.PaymentRequest request) {
        return payments.authorize(passenger().id(), request);
    }

    @PostMapping("/bookings/confirm")
    public CommerceDtos.TicketView confirm(@RequestBody ConfirmRequest request) {
        return creation.confirm(
                passenger().id(), request.draftUuid(), request.paymentUuid(),
                request.idempotencyKey());
    }

    @GetMapping("/bookings")
    public List<CommerceDtos.OwnedBookingView> bookings() {
        return bookings.list(passenger().id());
    }

    @GetMapping("/bookings/{pnr}")
    public CommerceDtos.TicketView booking(@PathVariable String pnr) {
        return requireBooking(passenger().id(), pnr);
    }

    @GetMapping("/bookings/{pnr}/ticket.pdf")
    public ResponseEntity<byte[]> ticketPdf(@PathVariable String pnr) {
        var ticket = requireBooking(passenger().id(), pnr);
        return pdf(documents.ticketPdf(ticket),
                "UnitedAir-" + ticket.flightNo() + "-" + ticket.pnr() + "-ticket.pdf");
    }

    @GetMapping("/bookings/{pnr}/payment-receipt.pdf")
    public ResponseEntity<byte[]> paymentReceiptPdf(@PathVariable String pnr) {
        var ticket = requireBooking(passenger().id(), pnr);
        return pdf(documents.paymentReceiptPdf(ticket),
                "UnitedAir-" + ticket.pnr() + "-payment-receipt.pdf");
    }

    @GetMapping("/bookings/{pnr}/cancellation-quote")
    public ToolDtos.RefundQuote cancellationQuote(@PathVariable String pnr) {
        requireBooking(passenger().id(), pnr);
        return actions.quote(pnr.trim().toUpperCase(Locale.ROOT));
    }

    @PostMapping("/bookings/{pnr}/cancellation-proposal")
    public ActionDtos.ActionView proposeCancellation(
            @PathVariable String pnr,
            @RequestBody(required = false) CancellationRequest request) {
        String normalized = requireBooking(passenger().id(), pnr).pnr();
        String sessionId = request == null ? null : request.sessionId();
        return actions.propose(new ActionDtos.ProposeRequest(
                "CANCEL_BOOKING", normalized,
                sessionId, null, null, null), sessionId);
    }

    @PostMapping("/bookings/{pnr}/support-callback")
    public CommerceDtos.CallbackView callback(
            @PathVariable String pnr,
            @RequestBody(required = false) CallbackRequest request) {
        return callbacks.request(
                passenger().id(), pnr, request == null ? "PHONE" : request.channel());
    }

    @GetMapping("/bookings/{pnr}/cancellation-receipt.pdf")
    public ResponseEntity<byte[]> cancellationReceiptPdf(@PathVariable String pnr) {
        long userId = passenger().id();
        CommerceDtos.CancellationView cancellation = bookings.cancellation(
                        userId, pnr.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "No completed cancellation was found for this booking."));
        return pdf(cancellationDocuments.receiptPdf(cancellation),
                "UnitedAir-" + cancellation.pnr() + "-cancellation-receipt.pdf");
    }

    private CurrentUser.Authenticated passenger() {
        CurrentUser.Authenticated user = currentUser.require();
        if (user.id() == null) {
            throw new ApiExceptions.Unauthorized("Authentication required.");
        }
        return user;
    }

    private CommerceDtos.TicketView requireBooking(long userId, String pnr) {
        String normalized = pnr == null ? "" : pnr.trim().toUpperCase(Locale.ROOT);
        return bookings.detail(userId, normalized)
                .orElseThrow(() -> new ApiExceptions.NotFound("Booking not found."));
    }

    private static ResponseEntity<byte[]> pdf(byte[] body, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    public record DraftStart(String sessionUuid) { }

    public record DraftUpdate(CommerceDtos.DraftPatch patch, int expectedVersion) { }

    public record ConfirmRequest(
            UUID draftUuid, UUID paymentUuid, String idempotencyKey) { }

    public record CancellationRequest(String sessionId) { }

    public record CallbackRequest(String channel) { }
}
