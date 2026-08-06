package com.unitedair.ai.providers.http;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.providers.BookingProvider;
import com.unitedair.ai.providers.CheckInProvider;
import com.unitedair.ai.providers.ProviderCapability;
import com.unitedair.ai.shared.UnitedAirProperties;
import com.unitedair.ai.tools.BookingAccess;
import com.unitedair.ai.tools.BookingRepository;
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
public class ReservationBookingProvider implements BookingProvider, CheckInProvider {

    private final RestClient client;

    @Autowired
    public ReservationBookingProvider(UnitedAirProperties properties) {
        this(properties.getProviders().getReservation());
    }

    public ReservationBookingProvider(
            UnitedAirProperties.Providers.Endpoint endpoint) {
        this.client = ProviderHttpSupport.client("reservation", endpoint);
    }

    @Override
    public Optional<ToolDtos.BookingView> retrieve(
            String pnr, BookingAccess access) {
        try {
            ToolDtos.BookingView response = client.get()
                    .uri("/bookings/{pnr}", pnr)
                    .headers(headers -> actor(headers, access))
                    .header("X-Request-ID", requestId())
                    .retrieve()
                    .body(ToolDtos.BookingView.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("reservation", failure);
        }
    }

    @Override
    public ToolDtos.RefundQuote quoteRefund(ToolDtos.BookingView booking) {
        try {
            return client.post()
                    .uri("/bookings/{pnr}/refund-quote", booking.pnr())
                    .header("X-Request-ID", requestId())
                    .body(Map.of("retrievedAt", booking.retrievedAt()))
                    .retrieve()
                    .body(ToolDtos.RefundQuote.class);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("reservation", failure);
        }
    }

    @Override
    public BookingRepository.RescheduleQuote quoteReschedule(
            ToolDtos.BookingView booking,
            Long targetFlightInstanceId,
            String targetFareClass) {
        try {
            return client.post()
                    .uri("/bookings/{pnr}/reschedule-quote", booking.pnr())
                    .header("X-Request-ID", requestId())
                    .body(Map.of(
                            "targetFlightInstanceId", targetFlightInstanceId,
                            "targetFareClass", targetFareClass == null ? "" : targetFareClass))
                    .retrieve()
                    .body(BookingRepository.RescheduleQuote.class);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("reservation", failure);
        }
    }

    @Override
    public ProviderActionResult confirm(
            ProviderActionCommand command, BookingAccess access) {
        if (command == null
                || command.idempotencyKey() == null
                || command.idempotencyKey().isBlank()) {
            throw new com.unitedair.ai.shared.ApiExceptions.BadRequest(
                    "A provider action idempotency key is required.");
        }
        try {
            return client.post()
                    .uri("/actions/confirm")
                    .headers(headers -> actor(headers, access))
                    .header("X-Request-ID", requestId())
                    .header("Idempotency-Key", command.idempotencyKey())
                    .body(command)
                    .retrieve()
                    .body(ProviderActionResult.class);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("reservation", failure);
        }
    }

    @Override
    public ToolDtos.CheckInEligibility eligibility(ToolDtos.BookingView booking) {
        try {
            return client.get()
                    .uri("/bookings/{pnr}/check-in-eligibility", booking.pnr())
                    .header("X-Request-ID", requestId())
                    .retrieve()
                    .body(ToolDtos.CheckInEligibility.class);
        } catch (RuntimeException failure) {
            throw ProviderHttpSupport.map("reservation", failure);
        }
    }

    @Override
    public ProviderCapability capability() {
        return new ProviderCapability(
                "real", "RESERVATION_SANDBOX", true, true);
    }

    private static void actor(
            org.springframework.http.HttpHeaders headers, BookingAccess access) {
        if (access != null) {
            headers.set("X-Actor-Role", access.role().name());
            if (access.userId() != null) {
                headers.set("X-Actor-User-ID", String.valueOf(access.userId()));
            }
        }
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }
}
