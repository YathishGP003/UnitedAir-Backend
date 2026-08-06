package com.unitedair.ai.providers.simulator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.unitedair.ai.providers.FlightProvider;
import com.unitedair.ai.providers.ProviderCapability;
import com.unitedair.ai.tools.AirportOption;
import com.unitedair.ai.tools.BookingRepository;
import com.unitedair.ai.tools.SimulatorRepository;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "unitedair.providers",
        name = "mode",
        havingValue = "simulator",
        matchIfMissing = true)
public class SimulatorFlightProvider implements FlightProvider {

    private final SimulatorRepository simulator;
    private final BookingRepository bookings;

    public SimulatorFlightProvider(
            SimulatorRepository simulator, BookingRepository bookings) {
        this.simulator = simulator;
        this.bookings = bookings;
    }

    @Override
    public ToolDtos.FlightSearchResult search(ToolDtos.FlightSearchRequest request) {
        String origin = simulator.resolveAirport(request.origin());
        String destination = simulator.resolveAirport(request.destination());
        List<ToolDtos.FlightOption> flights = simulator.searchFlights(
                origin, destination, request.departureDate(), request.cabin());
        return new ToolDtos.FlightSearchResult(
                origin,
                destination,
                request.departureDate(),
                request.cabin(),
                flights.size(),
                flights,
                Instant.now());
    }

    @Override
    public List<ToolDtos.SeatOption> seatMap(String flightNo, LocalDate date) {
        Long instance = simulator.findFlightInstanceId(flightNo, date)
                .orElse(null);
        return instance == null ? List.of() : bookings.seatMap(instance);
    }

    @Override
    public List<AirportOption> supportedAirports() {
        return simulator.supportedAirports();
    }

    @Override
    public String resolveAirport(String input) {
        return simulator.resolveAirport(input);
    }

    @Override
    public boolean routeExists(String origin, String destination) {
        return simulator.routeExists(origin, destination);
    }

    @Override
    public Optional<Long> findFlightInstanceId(String flightNo, LocalDate date) {
        return simulator.findFlightInstanceId(flightNo, date);
    }

    @Override
    public Optional<ToolDtos.MealAvailability> mealAvailability(
            String flightNo, LocalDate date, String mealCode) {
        return simulator.mealAvailability(flightNo, date, mealCode);
    }

    @Override
    public Optional<ToolDtos.ExcessBaggageQuote> excessBaggageQuote(
            String routeType, String cabin, int excessKg) {
        return simulator.excessBaggageQuote(routeType, cabin, excessKg);
    }

    @Override
    public ProviderCapability capability() {
        return ProviderCapability.simulator();
    }
}
