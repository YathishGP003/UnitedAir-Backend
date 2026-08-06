package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;

class SeatInventoryWorkerTest {

    @Test
    void listsOnlyAvailableSeatsInRequestedCabin() {
        SeatInventoryRepository repository = mock(SeatInventoryRepository.class);
        when(repository.seatMap(22L)).thenReturn(List.of(
                new ToolDtos.SeatOption("2A", "BUSINESS", "WINDOW", true, false,
                        BigDecimal.ZERO, true),
                new ToolDtos.SeatOption("8A", "ECONOMY", "WINDOW", false, false,
                        new BigDecimal("200"), true),
                new ToolDtos.SeatOption("8B", "ECONOMY", "MIDDLE", false, false,
                        new BigDecimal("200"), false)));

        List<ToolDtos.SeatOption> result =
                new SeatInventoryWorker(repository).available(22L, "economy");

        assertThat(result).extracting(ToolDtos.SeatOption::seatNumber)
                .containsExactly("8A");
    }

    @Test
    void unavailableSeatCannotBeClaimed() {
        SeatInventoryRepository repository = mock(SeatInventoryRepository.class);
        when(repository.seatMap(22L)).thenReturn(List.of(
                new ToolDtos.SeatOption("8A", "ECONOMY", "WINDOW", false, false,
                        new BigDecimal("200"), true)));
        when(repository.claim(22L, "8A", "ECONOMY")).thenReturn(false);

        assertThatThrownBy(() ->
                new SeatInventoryWorker(repository).claim(22L, "8a", "economy"))
                .isInstanceOf(ApiExceptions.Conflict.class)
                .hasMessageContaining("no longer available");
    }
}
