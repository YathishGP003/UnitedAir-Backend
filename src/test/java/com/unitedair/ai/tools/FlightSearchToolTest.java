package com.unitedair.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class FlightSearchToolTest {

    private final SimulatorRepository simulator = mock(SimulatorRepository.class);
    private final FlightSearchTool tool = new FlightSearchTool(
            simulator,
            mock(BookingRepository.class),
            new ToolInvocationLogger(mock(JdbcClient.class)));

    @Test
    void unsupportedAirportReturnsTypedFailureAndDatabaseBackedSuggestions() {
        List<AirportOption> airports = List.of(
                new AirportOption("BLR", "Bengaluru", "Kempegowda", "India", true),
                new AirportOption("GOI", "Goa", "Dabolim", "India", true));
        when(simulator.resolveAirport("Ayodhya")).thenReturn(null);
        when(simulator.resolveAirport("Ghaziabad")).thenReturn(null);
        when(simulator.supportedAirports()).thenReturn(airports);

        FlightSearchTool.Outcome result = tool.search(
                new ToolDtos.FlightSearchRequest(
                        "Ayodhya",
                        "Ghaziabad",
                        LocalDate.now().plusDays(1),
                        null,
                        1,
                        0,
                        0),
                "PASSENGER");

        assertThat(result.envelope().success()).isFalse();
        assertThat(result.envelope().data()).isInstanceOf(OperationalFailure.class);
        OperationalFailure failure = (OperationalFailure) result.envelope().data();
        assertThat(failure.kind()).isEqualTo(OperationalFailureKind.UNSUPPORTED_AIRPORT);
        assertThat(failure.userMessage())
                .contains("Ayodhya", "Bengaluru (BLR)", "Goa (GOI)")
                .doesNotContain("parameter");
        assertThat(failure.details().get("supportedAirports")).isEqualTo(airports);
    }

    @Test
    void unsupportedRouteIsAuthoritativeInsteadOfReturningAnEmptySuccess() {
        when(simulator.resolveAirport("BLR")).thenReturn("BLR");
        when(simulator.resolveAirport("GOI")).thenReturn("GOI");
        when(simulator.routeExists("BLR", "GOI")).thenReturn(false);

        FlightSearchTool.Outcome result = tool.search(
                new ToolDtos.FlightSearchRequest(
                        "BLR",
                        "GOI",
                        LocalDate.now().plusDays(1),
                        null,
                        1,
                        0,
                        0),
                "PASSENGER");

        assertThat(result.envelope().success()).isFalse();
        assertThat(result.envelope().data())
                .extracting("kind")
                .isEqualTo(OperationalFailureKind.NO_ROUTE);
    }

    @Test
    void supportedAirportDiscoveryDelegatesToCurrentSimulatorRows() {
        List<AirportOption> airports = List.of(
                new AirportOption("GOI", "Goa", "Dabolim", "India", true));
        when(simulator.supportedAirports()).thenReturn(airports);

        assertThat(tool.listSupportedAirports()).isSameAs(airports);
        verify(simulator).supportedAirports();
    }

    @Test
    void mealLookupReportsFlightSpecificAvailabilityInsteadOfOnlyPolicyCodes() {
        LocalDate date = LocalDate.now().plusDays(4);
        ToolDtos.MealAvailability availability = new ToolDtos.MealAvailability(
                "UA404", date, true, Set.of("VGML", "KSML"),
                Instant.parse("2026-08-10T02:35:00Z"));
        when(simulator.mealAvailability("UA404", date, "VGML"))
                .thenReturn(Optional.of(availability));

        ToolDtos.ToolOutcome outcome = tool.mealAvailabilityForFlight(
                "ua 404", date, "vgml", "PASSENGER");

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.data()).isEqualTo(availability);
        assertThat(outcome.summary()).contains("VGML is available", "UA404");
    }

    @Test
    void documentedMealCodeCanBeUnavailableOnTheSelectedFlight() {
        LocalDate date = LocalDate.now().plusDays(4);
        when(simulator.mealAvailability("UA202", date, "VGML"))
                .thenReturn(Optional.of(new ToolDtos.MealAvailability(
                        "UA202", date, false, Set.of(), null)));

        ToolDtos.ToolOutcome outcome = tool.mealAvailabilityForFlight(
                "UA202", date, "VGML", "PASSENGER");

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.summary()).contains("does not have a scheduled meal service");
    }

    @Test
    void excessBaggageQuoteUsesTheEffectiveSimulatorSchedule() {
        ToolDtos.ExcessBaggageQuote quote = new ToolDtos.ExcessBaggageQuote(
                "DOMESTIC", "ECONOMY", 5,
                new BigDecimal("2500.00"), new BigDecimal("3250.00"),
                "INR", Instant.now());
        when(simulator.excessBaggageQuote("domestic", "economy", 5))
                .thenReturn(Optional.of(quote));

        ToolDtos.ToolOutcome outcome = tool.quoteExcessBaggage(
                "domestic", "economy", 5, "PASSENGER");

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.data()).isEqualTo(quote);
        assertThat(outcome.summary()).contains(
                "5 kg excess", "advance INR 2500", "airport INR 3250");
    }
}
