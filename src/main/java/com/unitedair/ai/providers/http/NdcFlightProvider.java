package com.unitedair.ai.providers.http;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.providers.FlightProvider;
import com.unitedair.ai.providers.ProviderCapability;
import com.unitedair.ai.providers.StatusProvider;
import com.unitedair.ai.shared.UnitedAirProperties;
import com.unitedair.ai.tools.AirportOption;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(
        prefix = "unitedair.providers",
        name = "mode",
        havingValue = "real")
public class NdcFlightProvider implements FlightProvider, StatusProvider {

    private final RestClient client;

    @Autowired
    public NdcFlightProvider(UnitedAirProperties properties) {
        this(properties.getProviders().getNdc());
    }

    public NdcFlightProvider(UnitedAirProperties.Providers.Endpoint endpoint) {
        this.client = ProviderHttpSupport.client("NDC", endpoint);
    }

    @Override
    public ToolDtos.FlightSearchResult search(ToolDtos.FlightSearchRequest request) {
        try {
            return client.post()
                    .uri("/flights/search")
                    .header("X-Request-ID", requestId())
                    .body(request)
                    .retrieve()
                    .body(ToolDtos.FlightSearchResult.class);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("NDC", failure);
        }
    }

    @Override
    public List<ToolDtos.SeatOption> seatMap(String flightNo, LocalDate date) {
        try {
            ToolDtos.SeatOption[] response = client.get()
                    .uri("/flights/{flightNo}/{date}/seats", flightNo, date)
                    .header("X-Request-ID", requestId())
                    .retrieve()
                    .body(ToolDtos.SeatOption[].class);
            return response == null ? List.of() : List.of(response);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("NDC", failure);
        }
    }

    @Override
    public List<AirportOption> supportedAirports() {
        try {
            AirportOption[] response = client.get()
                    .uri("/airports")
                    .header("X-Request-ID", requestId())
                    .retrieve()
                    .body(AirportOption[].class);
            return response == null ? List.of() : Arrays.asList(response);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("NDC", failure);
        }
    }

    @Override
    public String resolveAirport(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = input.trim().toUpperCase(Locale.ROOT);
        return supportedAirports().stream()
                .filter(airport -> airport.code().equalsIgnoreCase(normalized)
                        || airport.city().equalsIgnoreCase(input.trim()))
                .map(AirportOption::code)
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean routeExists(String origin, String destination) {
        try {
            RouteAvailability response = client.get()
                    .uri("/routes/{origin}/{destination}", origin, destination)
                    .header("X-Request-ID", requestId())
                    .retrieve()
                    .body(RouteAvailability.class);
            return response != null && response.operated();
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("NDC", failure);
        }
    }

    @Override
    public Optional<Long> findFlightInstanceId(String flightNo, LocalDate date) {
        try {
            InstanceReference response = client.get()
                    .uri("/flights/{flightNo}/{date}/instance", flightNo, date)
                    .header("X-Request-ID", requestId())
                    .retrieve()
                    .body(InstanceReference.class);
            return response == null ? Optional.empty() : Optional.ofNullable(response.id());
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("NDC", failure);
        }
    }

    @Override
    public Optional<ToolDtos.MealAvailability> mealAvailability(
            String flightNo, LocalDate date, String mealCode) {
        try {
            ToolDtos.MealAvailability response = client.get()
                    .uri(builder -> builder.path("/flights/{flightNo}/{date}/meals")
                            .queryParamIfPresent("code", Optional.ofNullable(mealCode))
                            .build(flightNo, date))
                    .header("X-Request-ID", requestId())
                    .retrieve()
                    .body(ToolDtos.MealAvailability.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("NDC", failure);
        }
    }

    @Override
    public Optional<ToolDtos.ExcessBaggageQuote> excessBaggageQuote(
            String routeType, String cabin, int excessKg) {
        try {
            ToolDtos.ExcessBaggageQuote response = client.post()
                    .uri("/baggage/excess/quote")
                    .header("X-Request-ID", requestId())
                    .body(Map.of(
                            "routeType", routeType,
                            "cabin", cabin,
                            "excessKg", excessKg))
                    .retrieve()
                    .body(ToolDtos.ExcessBaggageQuote.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("NDC", failure);
        }
    }

    @Override
    public ToolDtos.FlightStatusView status(String flightNo, LocalDate date) {
        try {
            return client.get()
                    .uri("/flights/{flightNo}/{date}/status", flightNo, date)
                    .header("X-Request-ID", requestId())
                    .retrieve()
                    .body(ToolDtos.FlightStatusView.class);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("NDC", failure);
        }
    }

    @Override
    public ProviderCapability capability() {
        return new ProviderCapability("real", "NDC_SANDBOX", true, false);
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    record RouteAvailability(boolean operated) { }
    record InstanceReference(Long id) { }
}
