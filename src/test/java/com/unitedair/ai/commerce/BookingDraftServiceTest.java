package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import org.junit.jupiter.api.Test;

class BookingDraftServiceTest {

    @Test
    void supplyingRouteAndFutureDateMovesDraftToFlightOptions() {
        BookingDraftRepository repository = mock(BookingDraftRepository.class);
        UUID id = UUID.randomUUID();
        CommerceDtos.BookingDraftView initial = new CommerceDtos.BookingDraftView(
                id, CommerceDtos.DraftState.COLLECTING, null, null, null, null,
                null, null, null, null, null, 0,
                Instant.now().plusSeconds(1800));
        when(repository.findOwned(id, 7L)).thenReturn(Optional.of(initial));
        when(repository.update(eq(id), eq(7L), eq(0), any())).thenAnswer(invocation ->
                invocation.getArgument(3));

        BookingDraftService service = new BookingDraftService(repository);
        CommerceDtos.BookingDraftView updated = service.applySlots(
                7L, id,
                new CommerceDtos.DraftPatch(
                        "BLR", "DEL", LocalDate.now().plusDays(1), "ECONOMY",
                        null, null, null, null, null),
                0);

        assertThat(updated.state()).isEqualTo(CommerceDtos.DraftState.FLIGHTS_SHOWN);
        assertThat(updated.origin()).isEqualTo("BLR");
        assertThat(updated.destination()).isEqualTo("DEL");
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void pastTravelDateIsRejectedWithoutAdvancingDraft() {
        BookingDraftRepository repository = mock(BookingDraftRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.findOwned(id, 7L)).thenReturn(Optional.of(
                new CommerceDtos.BookingDraftView(
                        id, CommerceDtos.DraftState.COLLECTING, null, null, null, null,
                        null, null, null, null, null, 0,
                        Instant.now().plusSeconds(1800))));
        BookingDraftService service = new BookingDraftService(repository);

        assertThatThrownBy(() -> service.applySlots(
                7L, id,
                new CommerceDtos.DraftPatch(
                        "BLR", "DEL", LocalDate.now().minusDays(1), null,
                        null, null, null, null, null),
                0))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("past");
    }
}
