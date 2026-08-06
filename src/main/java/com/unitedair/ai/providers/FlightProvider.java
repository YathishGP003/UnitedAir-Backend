package com.unitedair.ai.providers;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.unitedair.ai.tools.AirportOption;
import com.unitedair.ai.tools.ToolDtos;

public interface FlightProvider {

    ToolDtos.FlightSearchResult search(ToolDtos.FlightSearchRequest request);

    List<ToolDtos.SeatOption> seatMap(String flightNo, LocalDate date);

    List<AirportOption> supportedAirports();

    String resolveAirport(String input);

    boolean routeExists(String origin, String destination);

    Optional<Long> findFlightInstanceId(String flightNo, LocalDate date);

    Optional<ToolDtos.MealAvailability> mealAvailability(
            String flightNo, LocalDate date, String mealCode);

    Optional<ToolDtos.ExcessBaggageQuote> excessBaggageQuote(
            String routeType, String cabin, int excessKg);

    ProviderCapability capability();
}
