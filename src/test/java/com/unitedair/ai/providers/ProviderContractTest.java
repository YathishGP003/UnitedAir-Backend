package com.unitedair.ai.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import com.unitedair.ai.providers.simulator.SimulatorBookingProvider;
import com.unitedair.ai.providers.simulator.SimulatorFlightProvider;
import com.unitedair.ai.shared.UnitedAirProperties;
import com.unitedair.ai.tools.BookingRepository;
import com.unitedair.ai.tools.SimulatorRepository;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;

class ProviderContractTest {

    @Test
    void simulatorProvidersDiscloseNonLiveModeAndPreserveRepositoryBehavior() {
        SimulatorRepository simulator = mock(SimulatorRepository.class);
        BookingRepository bookings = mock(BookingRepository.class);
        LocalDate date = LocalDate.now().plusDays(1);
        when(simulator.resolveAirport("Bengaluru")).thenReturn("BLR");
        when(simulator.resolveAirport("Goa")).thenReturn("GOI");
        when(simulator.searchFlights("BLR", "GOI", date, "ECONOMY"))
                .thenReturn(List.of());

        FlightProvider flights = new SimulatorFlightProvider(simulator, bookings);
        BookingProvider bookingProvider = new SimulatorBookingProvider(bookings);
        ToolDtos.FlightSearchResult result = flights.search(
                new ToolDtos.FlightSearchRequest(
                        "Bengaluru", "Goa", date, "ECONOMY", 1, 0, 0));

        assertThat(result.origin()).isEqualTo("BLR");
        assertThat(result.destination()).isEqualTo("GOI");
        assertThat(flights.capability())
                .isEqualTo(ProviderCapability.simulator());
        assertThat(bookingProvider.capability().live()).isFalse();
    }

    @Test
    void invalidModeAndMissingRealCredentialsFailFast() {
        UnitedAirProperties invalid = new UnitedAirProperties();
        invalid.getProviders().setMode("automatic-fallback");
        assertThatThrownBy(() -> new ProviderModeValidator(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulator", "real");

        UnitedAirProperties missing = new UnitedAirProperties();
        missing.getProviders().setMode("real");
        assertThatThrownBy(() ->
                new com.unitedair.ai.providers.http.NdcFlightProvider(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never falls back to simulator");
        assertThatThrownBy(() ->
                new com.unitedair.ai.providers.http.ReservationBookingProvider(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never falls back to simulator");
    }
}
