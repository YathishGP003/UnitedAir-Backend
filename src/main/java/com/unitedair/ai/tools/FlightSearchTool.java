package com.unitedair.ai.tools;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.providers.FlightProvider;
import com.unitedair.ai.providers.ProviderCapability;
import com.unitedair.ai.providers.simulator.SimulatorFlightProvider;
import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SRS 2.5 FlightSearchTool - live availability, pricing, cabin options and seat counts.
 *
 * <p>Serves FR-001, FR-002 and FR-009.
 *
 * <p>The method carries Spring AI's {@code @Tool} annotation so the model can request it
 * when the orchestrator delegates an ambiguous turn, but the normal path is a direct call
 * from the orchestrator after deterministic intent classification. Availability and pricing
 * are not decisions we want a token sampler making.
 */
@Component
public class FlightSearchTool {

    public static final String NAME = "FlightSearchTool";

    private final FlightProvider provider;
    private final BookingRepository bookings;
    private final ToolInvocationLogger logger;
    private final CurrentUser currentUser;

    @Autowired
    public FlightSearchTool(FlightProvider provider,
                            BookingRepository bookings,
                            ToolInvocationLogger logger,
                            CurrentUser currentUser) {
        this.provider = provider;
        this.bookings = bookings;
        this.logger = logger;
        this.currentUser = currentUser;
    }

    public FlightSearchTool(SimulatorRepository simulator,
                            BookingRepository bookings,
                            ToolInvocationLogger logger) {
        this(new SimulatorFlightProvider(simulator, bookings),
                bookings, logger, new CurrentUser());
    }

    public FlightSearchTool(SimulatorRepository simulator,
                            BookingRepository bookings,
                            ToolInvocationLogger logger,
                            CurrentUser currentUser) {
        this(new SimulatorFlightProvider(simulator, bookings),
                bookings, logger, currentUser);
    }

    @Tool(name = NAME, description = """
            Use for verified flight inventory, fares, seat maps, route meal availability,
            excess-baggage quotes, or supported-airport discovery. Do not use for PNR
            servicing or generic policy.
            """)
    public ToolDtos.GovernedToolResult invoke(ToolDtos.FlightSearchToolRequest request) {
        if (request == null || request.operation() == null) {
            throw new ApiExceptions.BadRequest("A flight-search operation is required.");
        }
        Map<String, Object> arguments = request.arguments();
        String role = currentUser.role().name();
        ToolDtos.ToolOutcome outcome = switch (request.operation()) {
            case SEARCH_FLIGHTS -> search(
                    new ToolDtos.FlightSearchRequest(
                            string(arguments, "origin"),
                            string(arguments, "destination"),
                            parseDate(string(arguments, "departureDate")),
                            string(arguments, "cabin"),
                            integer(arguments, "adults", 1),
                            integer(arguments, "children", 0),
                            integer(arguments, "infants", 0)),
                    role).envelope();
            case GET_SEAT_MAP -> seatMapForFlight(
                    string(arguments, "flightNo"),
                    parseDate(string(arguments, "date")),
                    role);
            case GET_MEAL_AVAILABILITY -> mealAvailabilityForFlight(
                    string(arguments, "flightNo"),
                    parseDate(string(arguments, "date")),
                    string(arguments, "mealCode"),
                    role);
            case QUOTE_EXCESS_BAGGAGE -> quoteExcessBaggage(
                    string(arguments, "routeType"),
                    string(arguments, "cabin"),
                    integer(arguments, "excessKg", 0),
                    role);
            case LIST_SUPPORTED_AIRPORTS -> logger.invoke(
                    NAME,
                    request.operation().name(),
                    role,
                    "MODEL",
                    providerRequest(request.operation().name()),
                    () -> new ToolInvocationLogger.ToolResult(
                            listSupportedAirports(),
                            listSupportedAirports().size()
                                    + " UnitedAir airports are currently supported."));
        };
        return ToolDtos.GovernedToolResult.from(
                NAME, request.operation().name(), outcome);
    }

    /** Direct entry point used by the orchestrator, with invocation logging. */
    public Outcome search(ToolDtos.FlightSearchRequest request, String actorRole) {
        String origin = provider.resolveAirport(request.origin());
        String destination = provider.resolveAirport(request.destination());
        LocalDate date = request.departureDate();

        Map<String, Object> logged = new LinkedHashMap<>();
        logged.put("operation", "SEARCH_FLIGHTS");
        logged.put("origin", origin == null ? request.origin() : origin);
        logged.put("destination", destination == null ? request.destination() : destination);
        logged.put("departureDate", date == null ? "" : date.toString());
        logged.put("cabin", request.cabin());
        disclose(logged);

        ToolDtos.ToolOutcome outcome = logger.invoke(NAME, actorRole, "ORCHESTRATOR_WORKER", logged, () -> {
            if (origin == null || destination == null) {
                String unrecognised = origin == null ? request.origin() : request.destination();
                throw failure(
                        "searchFlights",
                        OperationalFailureKind.UNSUPPORTED_AIRPORT,
                        "I could not recognise " + unrecognised
                                + " as a UnitedAir airport. Supported airports are "
                                + supportedAirportSummary() + ".",
                        Map.of(
                                "field", origin == null ? "origin" : "destination",
                                "value", unrecognised == null ? "" : unrecognised,
                                "supportedAirports", listSupportedAirports()));
            }
            if (date == null) {
                throw failure(
                        "searchFlights",
                        OperationalFailureKind.VALIDATION,
                        "Please provide a travel date before I search for flights.",
                        Map.of("missingParameter", "travelDate"));
            }
            if (origin.equals(destination)) {
                throw failure(
                        "searchFlights",
                        OperationalFailureKind.VALIDATION,
                        "Origin and destination must be different airports.",
                        Map.of("origin", origin, "destination", destination));
            }
            if (date.isBefore(LocalDate.now())) {
                throw failure(
                        "searchFlights",
                        OperationalFailureKind.VALIDATION,
                        "That departure date is in the past.",
                        Map.of("departureDate", date));
            }
            if (!provider.routeExists(origin, destination)) {
                throw failure(
                        "searchFlights",
                        OperationalFailureKind.NO_ROUTE,
                        "UnitedAir does not currently operate a direct route from "
                                + origin + " to " + destination + ".",
                        Map.of(
                                "origin", origin,
                                "destination", destination,
                                "supportedAirports", listSupportedAirports()));
            }

            ToolDtos.FlightSearchResult result = provider.search(
                    new ToolDtos.FlightSearchRequest(
                            origin,
                            destination,
                            date,
                            request.cabin(),
                            request.adults(),
                            request.children(),
                            request.infants()));
            if (result == null || result.flights().isEmpty()) {
                throw failure(
                        "searchFlights",
                        OperationalFailureKind.NO_INVENTORY,
                        "No UnitedAir flight inventory is available from " + origin + " to "
                                + destination + " on " + date + ".",
                        Map.of(
                                "origin", origin,
                                "destination", destination,
                                "departureDate", date,
                                "cabin", request.cabin() == null ? "" : request.cabin()));
            }

            return new ToolInvocationLogger.ToolResult(result, summarise(result));
        });

        ToolDtos.FlightSearchResult data = outcome.data() instanceof ToolDtos.FlightSearchResult result
                ? result
                : null;
        return new Outcome(outcome, data);
    }

    /** Seat map for a specific departure, backing FR-009. */
    public List<ToolDtos.SeatOption> seatMap(Long flightInstanceId) {
        return bookings.seatMap(flightInstanceId);
    }

    /** Returns current database-backed airport choices for clarification and UI rendering. */
    public List<AirportOption> listSupportedAirports() {
        return provider.supportedAirports();
    }

    /** Logged seat-map lookup used by conversational tool routing. */
    public ToolDtos.ToolOutcome seatMapForFlight(
            String flightNo,
            LocalDate date,
            String actorRole) {
        LocalDate targetDate = date == null ? LocalDate.now().plusDays(1) : date;
        String normalisedFlight = flightNo == null
                ? null : flightNo.trim().toUpperCase().replace(" ", "");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", "GET_SEAT_MAP");
        request.put("flightNo", normalisedFlight == null ? "" : normalisedFlight);
        request.put("date", targetDate.toString());
        disclose(request);

        return logger.invoke(NAME, actorRole, "ORCHESTRATOR_WORKER", request, () -> {
            Long instanceId = provider.findFlightInstanceId(normalisedFlight, targetDate)
                    .orElseThrow(() -> new ApiExceptions.NotFound(
                            "No UnitedAir departure was found for " + normalisedFlight
                                    + " on " + targetDate + "."));
            List<ToolDtos.SeatOption> seats =
                    provider.seatMap(normalisedFlight, targetDate);
            long available = seats.stream().filter(ToolDtos.SeatOption::available).count();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("flightInstanceId", instanceId);
            result.put("flightNo", normalisedFlight);
            result.put("date", targetDate);
            result.put("availableSeats", available);
            result.put("totalSeats", seats.size());
            result.put("seats", seats);
            String summary = "Seat map for %s on %s has %d of %d seats available."
                    .formatted(normalisedFlight, targetDate, available, seats.size());
            return new ToolInvocationLogger.ToolResult(result, summary);
        });
    }

    /**
     * Verified route service lookup for FR-011.
     *
     * <p>This deliberately distinguishes a meal code documented in KB-AIR-006 from that
     * code being available on the selected dated departure.
     */
    public ToolDtos.ToolOutcome mealAvailabilityForFlight(
            String flightNo,
            LocalDate date,
            String mealCode,
            String actorRole) {
        LocalDate targetDate = date == null ? LocalDate.now().plusDays(1) : date;
        String normalisedFlight = flightNo == null
                ? null : flightNo.trim().toUpperCase().replace(" ", "");
        String normalisedCode = mealCode == null
                ? null : mealCode.trim().toUpperCase();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", "GET_MEAL_AVAILABILITY");
        request.put("flightNo", normalisedFlight == null ? "" : normalisedFlight);
        request.put("date", targetDate.toString());
        request.put("mealCode", normalisedCode == null ? "" : normalisedCode);
        disclose(request);

        return logger.invoke(NAME, actorRole, "ORCHESTRATOR_WORKER", request, () -> {
            ToolDtos.MealAvailability availability = provider
                    .mealAvailability(normalisedFlight, targetDate, normalisedCode)
                    .orElseThrow(() -> failure(
                            "GET_MEAL_AVAILABILITY",
                            OperationalFailureKind.NO_INVENTORY,
                            "No UnitedAir departure was found for " + normalisedFlight
                                    + " on " + targetDate + ".",
                            Map.of("flightNo", normalisedFlight == null ? "" : normalisedFlight,
                                    "date", targetDate)));

            boolean requestedAvailable = normalisedCode == null
                    || availability.availableCodes().contains(normalisedCode);
            String summary;
            if (!availability.mealService()) {
                summary = normalisedFlight + " on " + targetDate
                        + " does not have a scheduled meal service.";
            } else if (normalisedCode != null && !requestedAvailable) {
                summary = normalisedCode + " is documented as a meal code but is not available on "
                        + normalisedFlight + " on " + targetDate + ".";
            } else if (normalisedCode != null) {
                summary = normalisedCode + " is available on " + normalisedFlight + " on "
                        + targetDate + "; order by " + availability.orderDeadline() + ".";
            } else {
                summary = normalisedFlight + " on " + targetDate + " offers "
                        + availability.availableCodes().size() + " special meal code(s).";
            }
            return new ToolInvocationLogger.ToolResult(availability, summary);
        });
    }

    /** Calculates a concrete excess-baggage quote from the effective simulator tariff. */
    public ToolDtos.ToolOutcome quoteExcessBaggage(
            String routeType,
            String cabin,
            int excessKg,
            String actorRole) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", "QUOTE_EXCESS_BAGGAGE");
        request.put("routeType", routeType == null ? "" : routeType);
        request.put("cabin", cabin == null ? "" : cabin);
        request.put("excessKg", excessKg);
        disclose(request);

        return logger.invoke(NAME, actorRole, "ORCHESTRATOR_WORKER", request, () -> {
            if (excessKg <= 0) {
                throw failure(
                        "QUOTE_EXCESS_BAGGAGE",
                        OperationalFailureKind.VALIDATION,
                        "Please provide the number of kilograms above the free allowance.",
                        Map.of("excessKg", excessKg));
            }
            ToolDtos.ExcessBaggageQuote quote = provider
                    .excessBaggageQuote(routeType, cabin, excessKg)
                    .orElseThrow(() -> failure(
                            "QUOTE_EXCESS_BAGGAGE",
                            OperationalFailureKind.NO_INVENTORY,
                            "No effective excess-baggage tariff was found for that route type "
                                    + "and cabin.",
                            Map.of(
                                    "routeType", routeType == null ? "" : routeType,
                                    "cabin", cabin == null ? "" : cabin)));
            String summary = "%d kg excess: advance %s %s; airport %s %s."
                    .formatted(
                            quote.excessKg(),
                            quote.currency(),
                            quote.advanceFee().stripTrailingZeros().toPlainString(),
                            quote.currency(),
                            quote.airportFee().stripTrailingZeros().toPlainString());
            return new ToolInvocationLogger.ToolResult(quote, summary);
        });
    }

    private static String summarise(ToolDtos.FlightSearchResult result) {
        if (result.flights().isEmpty()) {
            return "No UnitedAir departures found for " + result.origin() + " to "
                    + result.destination() + " on " + result.departureDate() + ".";
        }
        var cheapest = result.flights().stream()
                .flatMap(f -> f.fares().stream())
                .filter(f -> f.seatsAvailable() > 0)
                .min((a, b) -> a.totalFare().compareTo(b.totalFare()));

        return result.resultCount() + " departure(s) " + result.origin() + " to "
                + result.destination() + " on " + result.departureDate()
                + cheapest.map(f -> ", from INR " + f.totalFare().stripTrailingZeros().toPlainString()
                        + " (" + f.fareBrand() + ")").orElse(", no seats currently available")
                + ".";
    }

    private static LocalDate parseDate(String value) {
        try {
            if (value == null || value.isBlank()) {
                throw new ApiExceptions.BadRequest("Please provide a departure date.");
            }
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            if (e instanceof ApiExceptions.BadRequest badRequest) {
                throw badRequest;
            }
            throw new ApiExceptions.BadRequest(
                    "'" + value + "' is not a valid date. Please use the form 2026-08-14.");
        }
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : value.toString().trim();
    }

    private static int integer(
            Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        try {
            return value instanceof Number number
                    ? number.intValue() : Integer.parseInt(value.toString());
        } catch (NumberFormatException invalid) {
            throw new ApiExceptions.BadRequest(key + " must be a whole number.");
        }
    }

    private String supportedAirportSummary() {
        return listSupportedAirports().stream()
                .map(airport -> airport.city() + " (" + airport.code() + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("the airports shown in Find a flight");
    }

    public ProviderCapability capability() {
        return provider.capability();
    }

    private Map<String, Object> providerRequest(String operation) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", operation);
        disclose(request);
        return request;
    }

    private void disclose(Map<String, Object> request) {
        ProviderCapability capability = provider.capability();
        request.put("provider", capability.provider());
        request.put("providerLive", capability.live());
    }

    private static OperationalFailureException failure(
            String operation,
            OperationalFailureKind kind,
            String userMessage,
            Map<String, Object> details) {
        return new OperationalFailureException(
                new OperationalFailure(NAME, operation, kind, userMessage, details));
    }

    /** Pairs the audit envelope with the typed payload so callers get both. */
    public record Outcome(ToolDtos.ToolOutcome envelope, ToolDtos.FlightSearchResult data) { }
}
