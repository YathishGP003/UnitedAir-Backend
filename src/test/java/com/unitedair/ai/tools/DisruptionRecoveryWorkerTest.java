package com.unitedair.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.Test;

class DisruptionRecoveryWorkerTest {

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final SimulatorRepository simulator = mock(SimulatorRepository.class);
    private final ToolInvocationLogger logger = mock(ToolInvocationLogger.class);

    @Test
    void ranksUpToThreeSameRouteAlternativesForDisruptedOwnedBooking() {
        BookingAccess access = new BookingAccess(Role.PASSENGER, 7L);
        ToolDtos.BookingView original = booking("CANCELLED");
        when(bookings.findByPnr("B6X9K2", access)).thenReturn(Optional.of(original));
        when(simulator.searchFlights("DEL", "LHR", original.flightDate(), "ECONOMY"))
                .thenReturn(List.of(
                        flight(21L, "UA405", LocalTime.of(16, 0), BigDecimal.valueOf(81000)),
                        flight(22L, "UA406", LocalTime.of(12, 0), BigDecimal.valueOf(76000))));
        when(simulator.searchFlights(
                "DEL", "LHR", original.flightDate().plusDays(1), "ECONOMY"))
                .thenReturn(List.of());

        DisruptionRecoveryWorker tool =
                new DisruptionRecoveryWorker(bookings, simulator, logger);
        ToolDtos.RecoveryPlan result = tool.findAlternatives("B6X9K2", access);

        assertThat(result.reason()).containsIgnoringCase("cancelled");
        assertThat(result.alternatives()).extracting(ToolDtos.RecoveryAlternative::flightNo)
                .containsExactly("UA406", "UA405");
        assertThat(result.alternatives()).allMatch(item -> item.availableSeats() > 0);
    }

    @Test
    void refusesRecoveryPlanForAnOnTimeFlight() {
        BookingAccess access = new BookingAccess(Role.PASSENGER, 7L);
        when(bookings.findByPnr("B6X9K2", access)).thenReturn(Optional.of(booking("ON_TIME")));

        DisruptionRecoveryWorker tool =
                new DisruptionRecoveryWorker(bookings, simulator, logger);

        assertThatThrownBy(() -> tool.findAlternatives("B6X9K2", access))
                .hasMessageContaining("not currently disrupted");
    }

    private ToolDtos.BookingView booking(String flightStatus) {
        return new ToolDtos.BookingView(
                "B6X9K2", "Ananya Rao", "CONFIRMED", "UA404", "DEL", "LHR",
                LocalDate.now().plusDays(2), LocalTime.of(9, 0), LocalTime.of(15, 0),
                flightStatus, "DELAYED".equals(flightStatus) ? 180 : 0, "T3", "B9",
                "ECONOMY", "M", "Value", true, true, BigDecimal.valueOf(2000),
                BigDecimal.valueOf(2000), BigDecimal.valueOf(75000), null, "18A", 25,
                null, false, null, Instant.now(), Instant.now());
    }

    private ToolDtos.FlightOption flight(
            Long id, String flightNo, LocalTime arrival, BigDecimal fare) {
        return new ToolDtos.FlightOption(
                id, flightNo, "DEL", "New Delhi", "LHR", "London",
                LocalDate.now().plusDays(2), arrival.minusHours(6), arrival, 360,
                "B787", true, "ON_TIME", 0, "T3", "B4",
                List.of(new ToolDtos.FareOption(null,
                        "M", "ECONOMY", "Value", fare.subtract(BigDecimal.valueOf(5000)),
                        BigDecimal.valueOf(5000), fare, true, true,
                        BigDecimal.valueOf(2000), BigDecimal.valueOf(2000),
                        25, 7, 5, 50)));
    }
}
