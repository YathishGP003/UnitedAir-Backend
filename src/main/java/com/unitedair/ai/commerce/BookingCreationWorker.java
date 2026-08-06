package com.unitedair.ai.commerce;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.Json;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingCreationWorker {

    private static final char[] PNR_ALPHABET =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] PNR_DIGITS = "23456789".toCharArray();
    private static final char[] PNR_LETTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OwnedBookingRepository repository;
    private final SeatInventoryWorker seats;

    public BookingCreationWorker(
            OwnedBookingRepository repository, SeatInventoryWorker seats) {
        this.repository = repository;
        this.seats = seats;
    }

    @Transactional
    public CommerceDtos.TicketView confirm(
            long userId,
            UUID draftId,
            UUID paymentId,
            String idempotencyKey) {
        if (draftId == null || paymentId == null
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiExceptions.BadRequest(
                    "Draft, payment and confirmation key are required.");
        }
        var existing = repository.findConfirmedByDraft(userId, draftId);
        if (existing.isPresent()) {
            return existing.get();
        }

        OwnedBookingRepository.ConfirmationContext context =
                repository.lockConfirmation(userId, draftId, paymentId);
        if ("CAPTURED".equals(context.paymentStatus())) {
            return repository.findConfirmedByDraft(userId, draftId)
                    .orElseThrow(() -> new ApiExceptions.Conflict(
                            "Payment was captured but the issued ticket could not be loaded."));
        }
        if (!"AUTHORIZED".equals(context.paymentStatus())) {
            throw new ApiExceptions.Conflict(
                    "Authorize a successful payment before confirming the booking.");
        }
        if (context.traveller() == null || context.contact() == null) {
            throw new ApiExceptions.Conflict(
                    "Traveller and contact details are required before confirmation.");
        }
        BigDecimal expectedTotal = context.currentFare().add(context.seatFee());
        if (context.paymentAmount().compareTo(expectedTotal) != 0) {
            throw new ApiExceptions.Conflict(
                    "The fare changed before confirmation. Review the new price; "
                            + "the authorization was not captured.");
        }
        if (context.seatNumber() == null || context.seatNumber().isBlank()) {
            throw new ApiExceptions.Conflict("Choose a seat before confirmation.");
        }
        if (!repository.decrementFare(context.fareId())) {
            throw new ApiExceptions.Conflict(
                    "That fare just sold out. Choose another available fare.");
        }
        seats.claim(context.flightInstanceId(), context.seatNumber(), context.cabin());

        String pnr = null;
        long bookingId = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            pnr = randomPnr();
            try {
                bookingId = repository.insertBooking(context, pnr);
                break;
            } catch (DuplicateKeyException duplicate) {
                if (attempt == 4) {
                    throw duplicate;
                }
            }
        }
        String ticketNumber = ticketNumber();
        CommerceDtos.TicketView issued = ticket(context, pnr, ticketNumber);
        repository.captureAndComplete(
                bookingId, userId, draftId, paymentId, ticketNumber, Json.write(issued));
        return repository.detailById(userId, bookingId).orElse(issued);
    }

    private static CommerceDtos.TicketView ticket(
            OwnedBookingRepository.ConfirmationContext context,
            String pnr,
            String ticketNumber) {
        return new CommerceDtos.TicketView(
                pnr, ticketNumber, "CONFIRMED",
                context.traveller().fullName(), context.traveller().dateOfBirth(),
                context.traveller().nationality(), context.contact().email(),
                context.contact().phone(), context.flightNo(), context.origin(),
                context.destination(), context.flightDate(), context.departureTime(),
                context.arrivalTime(), context.terminal(), context.gate(), context.cabin(),
                context.fareClass(), context.fareBrand(), context.seatNumber(),
                context.checkedBaggageKg(), context.cabinBaggageKg(), context.baseFare(),
                context.taxes(), context.seatFee(), context.paymentAmount(),
                context.paymentMethod(), context.maskedPayment(), context.paymentReference(),
                Instant.now(), null, null);
    }

    static String randomPnr() {
        char[] value = new char[6];
        for (int i = 0; i < value.length; i++) {
            value[i] = PNR_ALPHABET[RANDOM.nextInt(PNR_ALPHABET.length)];
        }
        int digitPosition = RANDOM.nextInt(value.length);
        int letterPosition;
        do {
            letterPosition = RANDOM.nextInt(value.length);
        } while (letterPosition == digitPosition);
        value[digitPosition] = PNR_DIGITS[RANDOM.nextInt(PNR_DIGITS.length)];
        value[letterPosition] = PNR_LETTERS[RANDOM.nextInt(PNR_LETTERS.length)];
        return new String(value);
    }

    private static String ticketNumber() {
        long value = Math.floorMod(RANDOM.nextLong(), 10_000_000_000L);
        return "016-" + String.format(Locale.ROOT, "%010d", value);
    }
}
