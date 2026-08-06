package com.unitedair.ai.tools;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data access for the airline simulator.
 *
 * <p>Departures are seeded for 45 days. Beyond that, {@link #ensureInstancesFor} materialises
 * the requested date on demand, using the same fare ladder the seed migration uses. Without
 * this a passenger searching four months ahead would be told there are no flights, which
 * looks like a data problem rather than the boundary of a demonstration data set.
 */
@Repository
public class SimulatorRepository {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRepository.class);

    /** Mirrors the fare ladder in V7__seed_simulator.sql; see KB_05 section 2.2. */
    private static final List<FareTemplate> FARE_LADDER = List.of(
            new FareTemplate("Q", "ECONOMY", "Super Saver", 2200, false, false, 0, -1, 9, 10),
            new FareTemplate("K", "ECONOMY", "Saver", 2600, false, false, 0, -1, 14, 25),
            new FareTemplate("M", "ECONOMY", "Value", 3500, true, true, 2000, 2000, 22, 50),
            new FareTemplate("B", "ECONOMY", "Flex", 4200, true, true, 1000, 1000, 17, 75),
            new FareTemplate("Y", "ECONOMY", "Full Flex", 5000, true, true, 0, 0, 11, 100),
            new FareTemplate("D", "BUSINESS", "Business Saver", 9500, true, true, 3000, 3000, 6, 125),
            new FareTemplate("C", "BUSINESS", "Business Flex", 12000, true, true, 0, 0, 4, 150));

    private final JdbcClient jdbc;

    public SimulatorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ flight search ---

    public List<ToolDtos.FlightOption> searchFlights(String origin,
                                                     String destination,
                                                     LocalDate date,
                                                     String cabin) {
        ensureInstancesFor(origin, destination, date);

        String cabinFilter = normaliseCabin(cabin);

        List<Row> rows = jdbc.sql("""
                    SELECT i.id AS instance_id, f.flight_no, f.origin, f.destination,
                           ao.city AS origin_city, ad.city AS destination_city,
                           i.flight_date, f.dep_time_local, f.arr_time_local, f.duration_min,
                           f.aircraft, f.international, i.status, i.delay_minutes,
                           i.terminal, i.gate,
                           sf.id AS fare_id,sf.fare_class, sf.cabin, sf.fare_brand, sf.base_fare_inr, sf.taxes_inr,
                           sf.price_inr, sf.refundable, sf.changeable, sf.change_fee_inr,
                           sf.cancel_fee_inr, sf.checked_baggage_kg, sf.cabin_baggage_kg,
                           sf.seats_available, sf.ffp_accrual_pct
                    FROM sim_flight_instance i
                    JOIN sim_flight f  ON f.id = i.flight_id
                    JOIN sim_airport ao ON ao.code = f.origin
                    JOIN sim_airport ad ON ad.code = f.destination
                    JOIN sim_fare sf   ON sf.flight_instance_id = i.id
                    WHERE f.origin = :origin
                      AND f.destination = :destination
                      AND i.flight_date = :date
                      AND (:cabin IS NULL OR sf.cabin = :cabin)
                    ORDER BY f.dep_time_local, sf.price_inr
                """)
                .param("origin", origin)
                .param("destination", destination)
                .param("date", java.sql.Date.valueOf(date))
                .param("cabin", cabinFilter)
                .query(SimulatorRepository::mapRow)
                .list();

        // Collapse the fare-level rows into one entry per departure.
        Map<Long, List<Row>> byInstance = new LinkedHashMap<>();
        for (Row row : rows) {
            byInstance.computeIfAbsent(row.instanceId(), k -> new ArrayList<>()).add(row);
        }

        List<ToolDtos.FlightOption> flights = new ArrayList<>();
        byInstance.forEach((instanceId, group) -> {
            Row first = group.get(0);
            List<ToolDtos.FareOption> fares = group.stream()
                    .map(r -> new ToolDtos.FareOption(
                            r.fareId(), r.fareClass(), r.cabin(), r.fareBrand(),
                            r.baseFare(), r.taxes(), r.totalFare(),
                            r.refundable(), r.changeable(), r.changeFee(), r.cancelFee(),
                            r.checkedBaggageKg(), r.cabinBaggageKg(),
                            r.seatsAvailable(), r.ffpAccrualPct()))
                    .toList();

            flights.add(new ToolDtos.FlightOption(
                    instanceId, first.flightNo(), first.origin(), first.originCity(),
                    first.destination(), first.destinationCity(), first.flightDate(),
                    first.departureTime(), first.arrivalTime(), first.durationMinutes(),
                    first.aircraft(), first.international(), first.status(), first.delayMinutes(),
                    first.terminal(), first.gate(), fares,
                    mealAvailability(first.flightNo(), first.flightDate(), null)
                            .orElse(new ToolDtos.MealAvailability(
                                    first.flightNo(), first.flightDate(), false,
                                    Set.of(), null))));
        });

        return flights;
    }

    /** Resolves a scheduled flight number and date to the simulator departure instance. */
    public Optional<Long> findFlightInstanceId(String flightNo, LocalDate date) {
        if (flightNo == null || flightNo.isBlank() || date == null) {
            return Optional.empty();
        }
        String normalised = flightNo.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        Optional<Long> existing = flightInstanceId(normalised, date);
        if (existing.isPresent()) {
            return existing;
        }

        Map<String, Object> route = jdbc.sql("""
                    SELECT origin, destination
                    FROM sim_flight
                    WHERE REPLACE(UPPER(flight_no), ' ', '') = :flightNo
                    LIMIT 1
                """)
                .param("flightNo", normalised)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null);
        if (route == null) {
            return Optional.empty();
        }
        ensureInstancesFor(
                String.valueOf(route.get("origin")),
                String.valueOf(route.get("destination")),
                date);
        return flightInstanceId(normalised, date);
    }

    private Optional<Long> flightInstanceId(String flightNo, LocalDate date) {
        return jdbc.sql("""
                    SELECT i.id
                    FROM sim_flight_instance i
                    JOIN sim_flight f ON f.id = i.flight_id
                    WHERE REPLACE(UPPER(f.flight_no), ' ', '') = :flightNo
                      AND i.flight_date = :date
                    LIMIT 1
                """)
                .param("flightNo", flightNo)
                .param("date", java.sql.Date.valueOf(date))
                .query(Long.class)
                .optional();
    }

    /** Creates the departure and its fare ladder if the requested date was never seeded. */
    private void ensureInstancesFor(String origin, String destination, LocalDate date) {
        List<Long> missing = jdbc.sql("""
                    SELECT f.id
                    FROM sim_flight f
                    WHERE f.origin = :origin AND f.destination = :destination
                      AND NOT EXISTS (
                          SELECT 1 FROM sim_flight_instance i
                          WHERE i.flight_id = f.id AND i.flight_date = :date)
                """)
                .param("origin", origin)
                .param("destination", destination)
                .param("date", java.sql.Date.valueOf(date))
                .query(Long.class)
                .list();

        if (missing.isEmpty()) {
            return;
        }

        for (Long flightId : missing) {
            Boolean international = jdbc.sql("SELECT international FROM sim_flight WHERE id = :id")
                    .param("id", flightId).query(Boolean.class).single();
            boolean intl = Boolean.TRUE.equals(international);
            Integer durationMinutes = jdbc.sql(
                            "SELECT duration_min FROM sim_flight WHERE id = :id")
                    .param("id", flightId).query(Integer.class).single();

            jdbc.sql("""
                        INSERT INTO sim_flight_instance (flight_id, flight_date, status, seats_total, terminal, gate)
                        VALUES (:flightId, :date, 'ON_TIME', :seats, :terminal, :gate)
                    """)
                    .param("flightId", flightId)
                    .param("date", java.sql.Date.valueOf(date))
                    .param("seats", intl ? 256 : 180)
                    .param("terminal", intl ? "T3" : "T1")
                    .param("gate", (intl ? "B" : "A") + (1 + (flightId % 18)))
                    .update();

            Long instanceId = jdbc.sql("""
                        SELECT id FROM sim_flight_instance
                        WHERE flight_id = :flightId AND flight_date = :date
                    """)
                    .param("flightId", flightId)
                    .param("date", java.sql.Date.valueOf(date))
                    .query(Long.class).single();

            double multiplier = routeMultiplier(destination, origin);
            int surcharge = routeSurcharge(destination, origin);

            for (FareTemplate template : FARE_LADDER) {
                long base = Math.round(template.baseInr() * multiplier);
                long taxes = Math.round(1136 + 0.05 * base + surcharge);
                long cancelFee = template.cancelFeeInr() < 0 ? base : template.cancelFeeInr();
                int checked = intl && "ECONOMY".equals(template.cabin()) ? 25
                        : "BUSINESS".equals(template.cabin()) ? 35 : 15;

                jdbc.sql("""
                            INSERT INTO sim_fare (flight_instance_id, fare_class, cabin, fare_brand,
                                base_fare_inr, taxes_inr, price_inr, refundable, changeable,
                                change_fee_inr, cancel_fee_inr, checked_baggage_kg, cabin_baggage_kg,
                                seats_available, ffp_accrual_pct)
                            VALUES (:instanceId, :fareClass, :cabin, :brand,
                                :base, :taxes, :total, :refundable, :changeable,
                                :changeFee, :cancelFee, :checked, :cabinBag,
                                :seats, :ffp)
                        """)
                        .param("instanceId", instanceId)
                        .param("fareClass", template.fareClass())
                        .param("cabin", template.cabin())
                        .param("brand", template.brand())
                        .param("base", base)
                        .param("taxes", taxes)
                        .param("total", base + taxes)
                        .param("refundable", template.refundable())
                        .param("changeable", template.changeable())
                        .param("changeFee", template.changeFeeInr())
                        .param("cancelFee", cancelFee)
                        .param("checked", checked)
                        .param("cabinBag", "BUSINESS".equals(template.cabin()) ? 10 : 7)
                        .param("seats", template.seats())
                        .param("ffp", template.ffpPct())
                        .update();
            }

            seedMealService(instanceId, intl, durationMinutes == null ? 0 : durationMinutes);
        }
        log.debug("Materialised {} departure(s) for {}-{} on {}", missing.size(), origin, destination, date);
    }

    private static double routeMultiplier(String destination, String origin) {
        if ("DXB".equals(destination) || "DXB".equals(origin)) {
            return 4.2;
        }
        if ("SIN".equals(destination) || "SIN".equals(origin)) {
            return 3.6;
        }
        if ("LHR".equals(destination) || "LHR".equals(origin)) {
            return 8.4;
        }
        return 1.0;
    }

    private static int routeSurcharge(String destination, String origin) {
        if ("DXB".equals(destination) || "DXB".equals(origin)) {
            return 2600;
        }
        if ("SIN".equals(destination) || "SIN".equals(origin)) {
            return 2200;
        }
        if ("LHR".equals(destination) || "LHR".equals(origin)) {
            return 5400;
        }
        return 0;
    }

    // -------------------------------------------------------------- lookups ---

    /**
     * Common names for the airports we serve.
     *
     * <p>Passengers do not use the name in the database. They write "Bangalore", not
     * "Bengaluru"; "Bombay", not "Mumbai"; "Delhi", not "New Delhi". Without this map,
     * "flights from Bangalore to Delhi" fails to resolve and the whole turn falls through
     * to "no matching policy found" - which reads like the Knowledge Base is broken rather
     * than like a city name was not recognised.
     *
     * <p>Frequent misspellings are included deliberately. They are what people actually
     * type, and refusing them helps nobody.
     */
    private static final Map<String, String> AIRPORT_ALIASES = Map.ofEntries(
            Map.entry("bangalore", "BLR"), Map.entry("banglore", "BLR"),
            Map.entry("bengaluru", "BLR"), Map.entry("bengalooru", "BLR"),
            Map.entry("bombay", "BOM"), Map.entry("mumbai", "BOM"),
            Map.entry("delhi", "DEL"), Map.entry("new delhi", "DEL"),
            Map.entry("dilli", "DEL"), Map.entry("ncr", "DEL"),
            Map.entry("madras", "MAA"), Map.entry("chennai", "MAA"),
            Map.entry("calcutta", "CCU"), Map.entry("kolkata", "CCU"),
            Map.entry("hyderabad", "HYD"), Map.entry("hyd", "HYD"),
            Map.entry("dubai", "DXB"),
            Map.entry("london", "LHR"), Map.entry("heathrow", "LHR"),
            Map.entry("singapore", "SIN"));

    /** Resolves an IATA code, a city name, a common alias or a near-miss to an airport code. */
    public String resolveAirport(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String probe = input.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();

        String alias = AIRPORT_ALIASES.get(probe);
        if (alias != null) {
            return alias;
        }

        if (probe.length() == 3) {
            String code = probe.toUpperCase(Locale.ROOT);
            Long exists = jdbc.sql("SELECT COUNT(*) FROM sim_airport WHERE code = :c")
                    .param("c", code).query(Long.class).single();
            if (exists != null && exists > 0) {
                return code;
            }
        }

        String matched = jdbc.sql("""
                    SELECT code FROM sim_airport
                    WHERE LOWER(city) = :p
                       OR LOWER(city) LIKE CONCAT('%', :p, '%')
                       OR LOWER(name) LIKE CONCAT('%', :p, '%')
                    ORDER BY CHAR_LENGTH(city)
                    LIMIT 1
                """).param("p", probe).query(String.class).optional().orElse(null);
        if (matched != null) {
            return matched;
        }

        // Last resort: tolerate a single typo against the known aliases, so "banglor" or
        // "mumbay" still reach the right airport instead of failing the turn.
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, String> entry : AIRPORT_ALIASES.entrySet()) {
            int distance = editDistance(probe, entry.getKey());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getValue();
            }
        }
        return bestDistance <= 2 && probe.length() >= 4 ? best : null;
    }

    /** Levenshtein distance, capped by the caller at 2. */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    public List<Map<String, Object>> listAirports() {
        return jdbc.sql("SELECT code, city, name, country, domestic FROM sim_airport ORDER BY city")
                .query().listOfRows();
    }

    /** Typed airport discovery used by conversational failures and the structured UI. */
    public List<AirportOption> supportedAirports() {
        return jdbc.sql("""
                    SELECT code, city, name, country, domestic
                    FROM sim_airport
                    ORDER BY city, code
                """)
                .query((rs, rowNum) -> new AirportOption(
                        rs.getString("code"),
                        rs.getString("city"),
                        rs.getString("name"),
                        rs.getString("country"),
                        rs.getBoolean("domestic")))
                .list();
    }

    /** Whether the simulator has a scheduled route independently of dated inventory. */
    public boolean routeExists(String origin, String destination) {
        Long count = jdbc.sql("""
                    SELECT COUNT(*)
                    FROM sim_flight
                    WHERE origin = :origin AND destination = :destination
                """)
                .param("origin", origin)
                .param("destination", destination)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    public List<Map<String, Object>> listRoutes() {
        return jdbc.sql("""
                    SELECT f.flight_no, f.origin, f.destination, f.dep_time_local, f.arr_time_local,
                           f.duration_min, f.aircraft, f.international,
                           ao.city AS origin_city, ad.city AS destination_city
                    FROM sim_flight f
                    JOIN sim_airport ao ON ao.code = f.origin
                    JOIN sim_airport ad ON ad.code = f.destination
                    ORDER BY f.flight_no
                """).query().listOfRows();
    }

    // ----------------------------------------------------- route services ---

    /**
     * Reads meal availability for a specific dated departure.
     *
     * <p>The returned codes come from simulator rows for this flight instance. A code being
     * documented in KB-AIR-006 is not enough to make it available on every flight.
     */
    public Optional<ToolDtos.MealAvailability> mealAvailability(
            String flightNo,
            LocalDate date,
            String requestedCode) {
        Optional<Long> instanceId = findFlightInstanceId(flightNo, date);
        if (instanceId.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = jdbc.sql("""
                    SELECT f.flight_no, i.flight_date, s.meal_service, s.available_codes,
                           f.dep_time_local
                    FROM sim_flight_instance i
                    JOIN sim_flight f ON f.id = i.flight_id
                    LEFT JOIN sim_flight_meal_service s ON s.flight_instance_id = i.id
                    WHERE i.id = :instanceId
                """)
                .param("instanceId", instanceId.get())
                .query()
                .singleRow();

        boolean mealService = booleanValue(row.get("meal_service"));
        Set<String> codes = parseCodes(row.get("available_codes"));
        String code = normaliseMealCode(requestedCode);
        int deadlineHours = "KSML".equals(code) || "BBML".equals(code) ? 48 : 24;
        Instant deadline = null;
        if (mealService) {
            java.time.LocalTime departure = row.get("dep_time_local") instanceof java.sql.Time time
                    ? time.toLocalTime()
                    : java.time.LocalTime.parse(String.valueOf(row.get("dep_time_local")));
            deadline = date.atTime(departure)
                    .atZone(ZoneId.of("Asia/Kolkata"))
                    .toInstant()
                    .minusSeconds(deadlineHours * 3600L);
        }

        return Optional.of(new ToolDtos.MealAvailability(
                String.valueOf(row.get("flight_no")),
                date,
                mealService,
                codes,
                deadline));
    }

    /** Calculates channel-specific excess-baggage fees from the effective tariff rows. */
    public Optional<ToolDtos.ExcessBaggageQuote> excessBaggageQuote(
            String routeType,
            String cabin,
            int excessKg) {
        String normalisedRoute = normaliseRouteType(routeType);
        String normalisedCabin = normaliseCabin(cabin);
        if (normalisedRoute == null || normalisedCabin == null || excessKg <= 0) {
            return Optional.empty();
        }

        Map<String, Object> row = jdbc.sql("""
                    SELECT currency,
                           MAX(CASE WHEN purchase_channel = 'ADVANCE'
                                    THEN fee_per_kg END) AS advance_rate,
                           MAX(CASE WHEN purchase_channel = 'AIRPORT'
                                    THEN fee_per_kg END) AS airport_rate
                    FROM sim_excess_baggage_rate
                    WHERE route_type = :routeType
                      AND cabin = :cabin
                      AND effective_from <= CURRENT_DATE
                      AND (effective_to IS NULL OR effective_to >= CURRENT_DATE)
                    GROUP BY currency
                """)
                .param("routeType", normalisedRoute)
                .param("cabin", normalisedCabin)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null);
        if (row == null || row.get("advance_rate") == null || row.get("airport_rate") == null) {
            return Optional.empty();
        }

        BigDecimal kilograms = BigDecimal.valueOf(excessKg);
        BigDecimal advance = decimal(row.get("advance_rate")).multiply(kilograms);
        BigDecimal airport = decimal(row.get("airport_rate")).multiply(kilograms);
        return Optional.of(new ToolDtos.ExcessBaggageQuote(
                normalisedRoute,
                normalisedCabin,
                excessKg,
                advance,
                airport,
                String.valueOf(row.get("currency")),
                Instant.now()));
    }

    private void seedMealService(Long instanceId, boolean international, int durationMinutes) {
        boolean service = durationMinutes >= 120;
        String codes = !service ? ""
                : international
                    ? "VGML,VJML,VLML,KSML,MOML,HNML,DBML,BLML,LCML,LFML,CHML,BBML,SFML"
                    : "VGML,VJML,VLML,HNML,DBML,BLML,LCML,LFML,CHML";
        jdbc.sql("""
                    INSERT INTO sim_flight_meal_service
                        (flight_instance_id, meal_service, available_codes, source_document_code)
                    VALUES (:instanceId, :service, :codes, 'KB-AIR-006')
                    ON DUPLICATE KEY UPDATE
                        meal_service = VALUES(meal_service),
                        available_codes = VALUES(available_codes),
                        source_document_code = VALUES(source_document_code)
                """)
                .param("instanceId", instanceId)
                .param("service", service)
                .param("codes", codes)
                .update();
    }

    private static Set<String> parseCodes(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Set.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim)
                .map(code -> code.toUpperCase(Locale.ROOT))
                .filter(code -> !code.isBlank())
                .forEach(codes::add);
        return Collections.unmodifiableSet(codes);
    }

    private static String normaliseMealCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normaliseRouteType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "DOMESTIC", "DOM" -> "DOMESTIC";
            case "INTERNATIONAL", "INTL", "INT" -> "INTERNATIONAL";
            default -> null;
        };
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal
                ? decimal
                : new BigDecimal(String.valueOf(value));
    }

    private static String normaliseCabin(String cabin) {
        if (cabin == null || cabin.isBlank()) {
            return null;
        }
        String c = cabin.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (c) {
            case "ECONOMY", "ECO", "Y" -> "ECONOMY";
            case "BUSINESS", "BIZ", "C", "J" -> "BUSINESS";
            case "PREMIUM_ECONOMY", "PREMIUM" -> "PREMIUM_ECONOMY";
            case "FIRST", "F" -> "FIRST";
            default -> null;
        };
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getLong("instance_id"),
                rs.getString("flight_no"),
                rs.getString("origin"),
                rs.getString("origin_city"),
                rs.getString("destination"),
                rs.getString("destination_city"),
                rs.getDate("flight_date").toLocalDate(),
                rs.getTime("dep_time_local").toLocalTime(),
                rs.getTime("arr_time_local").toLocalTime(),
                rs.getInt("duration_min"),
                rs.getString("aircraft"),
                rs.getBoolean("international"),
                rs.getString("status"),
                rs.getInt("delay_minutes"),
                rs.getString("terminal"),
                rs.getString("gate"),
                rs.getLong("fare_id"),
                rs.getString("fare_class"),
                rs.getString("cabin"),
                rs.getString("fare_brand"),
                rs.getBigDecimal("base_fare_inr"),
                rs.getBigDecimal("taxes_inr"),
                rs.getBigDecimal("price_inr"),
                rs.getBoolean("refundable"),
                rs.getBoolean("changeable"),
                rs.getBigDecimal("change_fee_inr"),
                rs.getBigDecimal("cancel_fee_inr"),
                rs.getInt("checked_baggage_kg"),
                rs.getInt("cabin_baggage_kg"),
                rs.getInt("seats_available"),
                rs.getInt("ffp_accrual_pct"));
    }

    private record Row(
            Long instanceId, String flightNo, String origin, String originCity,
            String destination, String destinationCity, LocalDate flightDate,
            java.time.LocalTime departureTime, java.time.LocalTime arrivalTime,
            int durationMinutes, String aircraft, boolean international, String status,
            int delayMinutes, String terminal, String gate, Long fareId,
            String fareClass, String cabin, String fareBrand,
            BigDecimal baseFare, BigDecimal taxes, BigDecimal totalFare,
            boolean refundable, boolean changeable, BigDecimal changeFee, BigDecimal cancelFee,
            int checkedBaggageKg, int cabinBaggageKg, int seatsAvailable, int ffpAccrualPct) { }

    private record FareTemplate(
            String fareClass, String cabin, String brand, int baseInr,
            boolean refundable, boolean changeable, int changeFeeInr, int cancelFeeInr,
            int seats, int ffpPct) { }
}
