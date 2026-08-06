package com.unitedair.ai.providers.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.unitedair.ai.providers.BookingProvider;
import com.unitedair.ai.shared.UnitedAirProperties;
import com.unitedair.ai.tools.BookingAccess;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NdcFlightProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> observed = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsSearchSeatStatusBookingRefundAndIdempotentConfirmation() {
        UnitedAirProperties.Providers.Endpoint endpoint =
                new UnitedAirProperties.Providers.Endpoint();
        endpoint.setBaseUrl(baseUrl);
        endpoint.setApiKey("sandbox-secret");
        endpoint.setConnectTimeoutMs(1000);
        endpoint.setReadTimeoutMs(2000);
        NdcFlightProvider ndc = new NdcFlightProvider(endpoint);
        ReservationBookingProvider reservations =
                new ReservationBookingProvider(endpoint);
        LocalDate date = LocalDate.of(2026, 8, 1);

        ToolDtos.FlightSearchResult search = ndc.search(
                new ToolDtos.FlightSearchRequest(
                        "BLR", "GOI", date, "ECONOMY", 1, 0, 0));
        assertThat(search.origin()).isEqualTo("BLR");
        assertThat(search.flights())
                .singleElement()
                .satisfies(flight -> assertThat(flight.fares())
                        .singleElement()
                        .extracting(ToolDtos.FareOption::totalFare)
                        .isEqualTo(new java.math.BigDecimal("3446")));
        assertThat(ndc.seatMap("UA704", date))
                .singleElement()
                .extracting(ToolDtos.SeatOption::seatNumber)
                .isEqualTo("12A");
        assertThat(ndc.status("UA704", date).gate()).isEqualTo("A4");

        BookingAccess access = new BookingAccess(Role.PASSENGER, 7L);
        ToolDtos.BookingView booking =
                reservations.retrieve("ABC123", access).orElseThrow();
        assertThat(booking.pnr()).isEqualTo("ABC123");
        assertThat(reservations.quoteRefund(booking).estimatedRefund())
                .isEqualByComparingTo("3000");
        BookingProvider.ProviderActionResult confirmed = reservations.confirm(
                new BookingProvider.ProviderActionCommand(
                        "CANCEL_BOOKING", "ABC123", "idem-123", Map.of()),
                access);
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");

        assertThat(observed.get("authorization")).isEqualTo("Bearer sandbox-secret");
        assertThat(observed.get("requestId")).isNotBlank();
        assertThat(observed.get("actor")).isEqualTo("7");
        assertThat(observed.get("idempotency")).isEqualTo("idem-123");
        assertThat(ndc.capability().provider()).isEqualTo("NDC_SANDBOX");
        assertThat(reservations.capability().live()).isTrue();
    }

    private void handle(HttpExchange exchange) throws IOException {
        observed.put("authorization", exchange.getRequestHeaders()
                .getFirst("Authorization"));
        observed.put("requestId", exchange.getRequestHeaders()
                .getFirst("X-Request-ID"));
        if (exchange.getRequestHeaders().getFirst("X-Actor-User-ID") != null) {
            observed.put("actor", exchange.getRequestHeaders()
                    .getFirst("X-Actor-User-ID"));
        }
        if (exchange.getRequestHeaders().getFirst("Idempotency-Key") != null) {
            observed.put("idempotency", exchange.getRequestHeaders()
                    .getFirst("Idempotency-Key"));
        }
        String path = exchange.getRequestURI().getPath();
        String json;
        if (path.equals("/flights/search")) {
            json = """
                    {"origin":"BLR","destination":"GOI","departureDate":"2026-08-01",
                     "cabinFilter":"ECONOMY","resultCount":1,
                     "flights":[{
                       "flightInstanceId":704,"flightNo":"UA704","origin":"BLR",
                       "originCity":"Bengaluru","destination":"GOI",
                       "destinationCity":"Goa","flightDate":"2026-08-01",
                       "departureTime":"09:30:00","arrivalTime":"10:45:00",
                       "durationMinutes":75,"aircraft":"A320","international":false,
                       "status":"ON_TIME","delayMinutes":0,"terminal":"T1","gate":"A4",
                       "fares":[{"fareId":1,"fareClass":"Q","cabin":"ECONOMY",
                         "fareBrand":"Super Saver","baseFare":3000,"taxes":446,
                         "totalFare":3446,"refundable":false,"changeable":false,
                         "changeFee":0,"cancelFee":2200,"checkedBaggageKg":15,
                         "cabinBaggageKg":7,"seatsAvailable":8,"ffpAccrualPct":25}]
                     }],
                     "retrievedAt":"2026-07-27T12:00:00Z"}
                    """;
        } else if (path.endsWith("/seats")) {
            json = """
                    [{"seatNumber":"12A","cabin":"ECONOMY","seatType":"WINDOW",
                      "extraLegroom":false,"exitRow":false,"feeInr":0,"available":true}]
                    """;
        } else if (path.endsWith("/status")) {
            json = """
                    {"flightNo":"UA704","flightDate":"2026-08-01","origin":"BLR",
                     "destination":"GOI","status":"ON_TIME","delayMinutes":0,
                     "scheduledDeparture":"09:30:00","estimatedDeparture":"09:30:00",
                     "scheduledArrival":"10:45:00","terminal":"T1","gate":"A4",
                     "belt":null,"retrievedAt":"2026-07-27T12:00:00Z"}
                    """;
        } else if (path.equals("/bookings/ABC123")) {
            json = """
                    {"pnr":"ABC123","passengerName":"Ananya Rao","status":"CONFIRMED",
                     "flightNo":"UA704","origin":"BLR","destination":"GOI",
                     "flightDate":"2026-08-01","departureTime":"09:30:00",
                     "arrivalTime":"10:45:00","flightStatus":"ON_TIME",
                     "cabin":"ECONOMY","fareClass":"Q","fareBrand":"Super Saver",
                     "amountPaid":5000,"bookedAt":"2026-07-20T12:00:00Z",
                     "retrievedAt":"2026-07-27T12:00:00Z"}
                    """;
        } else if (path.endsWith("/refund-quote")) {
            json = """
                    {"pnr":"ABC123","fareBrand":"Super Saver","refundable":false,
                     "hoursToDeparture":100,"timingBand":"Non-refundable",
                     "amountPaid":5000,"cancellationFee":2000,"estimatedRefund":3000,
                     "refundTimeline":"5 to 7 working days","basis":"sandbox",
                     "calculatedAt":"2026-07-27T12:00:00Z"}
                    """;
        } else if (path.equals("/actions/confirm")) {
            json = """
                    {"actionId":"ACT-1","status":"CONFIRMED",
                     "message":"Confirmed in sandbox","data":{"pnr":"ABC123"}}
                    """;
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
