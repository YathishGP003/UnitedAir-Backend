package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BookingCreationWorkerTest {

    @Test
    void generatedPnrsAlwaysContainBothLettersAndDigits() {
        for (int attempt = 0; attempt < 500; attempt++) {
            assertThat(BookingCreationWorker.randomPnr())
                    .matches("(?=.*[A-Z])(?=.*\\d)[A-Z0-9]{6}");
        }
    }

    @Test
    void confirmsAuthorizedPaymentAndConsumesInventoryExactlyOnce() {
        OwnedBookingRepository repository = mock(OwnedBookingRepository.class);
        SeatInventoryWorker seats = mock(SeatInventoryWorker.class);
        UUID draftId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        OwnedBookingRepository.ConfirmationContext context = context(draftId, paymentId);
        when(repository.findConfirmedByDraft(7L, draftId)).thenReturn(Optional.empty());
        when(repository.lockConfirmation(7L, draftId, paymentId)).thenReturn(context);
        when(repository.decrementFare(102L)).thenReturn(true);
        when(repository.insertBooking(org.mockito.ArgumentMatchers.eq(context),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(501L);
        when(repository.detailById(7L, 501L)).thenReturn(Optional.of(ticket("ABC123")));

        CommerceDtos.TicketView result =
                new BookingCreationWorker(repository, seats)
                        .confirm(7L, draftId, paymentId, "confirm-once");

        assertThat(result.pnr()).isEqualTo("ABC123");
        verify(seats).claim(44L, "8A", "ECONOMY");
        verify(repository).decrementFare(102L);
        verify(repository).captureAndComplete(
                org.mockito.ArgumentMatchers.eq(501L),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(draftId),
                org.mockito.ArgumentMatchers.eq(paymentId),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicateConfirmationReturnsOriginalTicketWithoutTouchingInventory() {
        OwnedBookingRepository repository = mock(OwnedBookingRepository.class);
        SeatInventoryWorker seats = mock(SeatInventoryWorker.class);
        UUID draftId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        CommerceDtos.TicketView existing = ticket("Z8Q2LM");
        when(repository.findConfirmedByDraft(7L, draftId))
                .thenReturn(Optional.of(existing));

        CommerceDtos.TicketView result =
                new BookingCreationWorker(repository, seats)
                        .confirm(7L, draftId, paymentId, "same-confirmation");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).decrementFare(org.mockito.ArgumentMatchers.anyLong());
        verify(seats, never()).claim(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private static OwnedBookingRepository.ConfirmationContext context(
            UUID draftId, UUID paymentId) {
        return new OwnedBookingRepository.ConfirmationContext(
                draftId, 7L, 44L, 102L, paymentId, "AUTHORIZED",
                new BigDecimal("3646.00"), new BigDecimal("3446.00"),
                "BLR", "DEL", "UA101", LocalDate.now().plusDays(2),
                LocalTime.of(6, 15), LocalTime.of(9, 5), "T1", "A4",
                "ECONOMY", "Q", "Super Saver", new BigDecimal("2200.00"),
                new BigDecimal("1246.00"), new BigDecimal("200.00"), 15, 7, "8A",
                new CommerceDtos.Traveller(
                        "Maya Singh", LocalDate.of(1998, 5, 1), "Indian"),
                new CommerceDtos.Contact("maya@example.com", "+919000000000"),
                "CARD", "•••• 4242", "SIM-123");
    }

    static CommerceDtos.TicketView ticket(String pnr) {
        return new CommerceDtos.TicketView(
                pnr, "016-1234567890", "CONFIRMED", "Maya Singh",
                LocalDate.of(1998, 5, 1), "Indian", "maya@example.com",
                "+919000000000", "UA101", "BLR", "DEL",
                LocalDate.now().plusDays(2), LocalTime.of(6, 15),
                LocalTime.of(9, 5), "T1", "A4", "ECONOMY", "Q",
                "Super Saver", "8A", 15, 7, new BigDecimal("2200.00"),
                new BigDecimal("1246.00"), BigDecimal.ZERO,
                new BigDecimal("3446.00"), "CARD", "•••• 4242",
                "SIM-123", Instant.now(), null, null);
    }
}
