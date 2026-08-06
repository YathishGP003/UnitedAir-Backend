package com.unitedair.ai.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.unitedair.ai.tools.ToolDtos;

public final class CommerceDtos {

    private CommerceDtos() { }

    public enum DraftState {
        COLLECTING,
        FLIGHTS_SHOWN,
        CHECKOUT,
        PAYMENT_PENDING,
        CONFIRMED,
        ABANDONED,
        EXPIRED
    }

    public enum CommerceType {
        BOOKING_DETAILS_REQUIRED,
        FLIGHT_OPTIONS,
        CHECKOUT_READY,
        OWNED_BOOKING_OPTIONS,
        CANCELLATION_QUOTE,
        TICKET_CONFIRMED
    }

    public record Traveller(
            String fullName,
            LocalDate dateOfBirth,
            String nationality) { }

    public record Contact(
            String email,
            String phone) { }

    public record DraftPatch(
            String origin,
            String destination,
            LocalDate travelDate,
            String cabin,
            Long flightInstanceId,
            Long fareId,
            String seatNumber,
            Traveller traveller,
            Contact contact) { }

    public record BookingDraftView(
            UUID draftUuid,
            DraftState state,
            String origin,
            String destination,
            LocalDate travelDate,
            String cabin,
            Long flightInstanceId,
            Long fareId,
            String seatNumber,
            Traveller traveller,
            Contact contact,
            int version,
            Instant expiresAt) { }

    public record AirportView(
            String code,
            String city,
            String name,
            String country,
            boolean domestic) { }

    public record CommercePayload(
            CommerceType type,
            BookingDraftView draft,
            List<ToolDtos.FlightOption> flights,
            List<AirportView> alternatives,
            Object detail) { }

    public enum PaymentStatus {
        AUTHORIZED, CAPTURED, DECLINED, VOIDED, REFUNDED
    }

    public record PaymentRequest(
            UUID draftUuid,
            String method,
            String cardNumber,
            String cvv,
            String upi,
            BigDecimal amount,
            String idempotencyKey) { }

    public record PaymentView(
            UUID paymentUuid,
            PaymentStatus status,
            String method,
            String maskedAccount,
            String providerReference,
            BigDecimal amount,
            Instant createdAt,
            String statusMessage) { }

    public record OwnedBookingView(
            String pnr,
            String ticketNumber,
            String passengerName,
            String status,
            String flightNo,
            String origin,
            String destination,
            LocalDate flightDate,
            String seatNumber,
            String cabin,
            String fareBrand,
            BigDecimal amountPaid,
            Instant bookedAt) { }

    /**
     * Immutable issued-ticket snapshot. Downloaded documents render this record
     * rather than mutable fare tables, so historical totals never drift.
     */
    public record TicketView(
            String pnr,
            String ticketNumber,
            String status,
            String travellerName,
            LocalDate dateOfBirth,
            String nationality,
            String contactEmail,
            String contactPhone,
            String flightNo,
            String origin,
            String destination,
            LocalDate flightDate,
            java.time.LocalTime departureTime,
            java.time.LocalTime arrivalTime,
            String terminal,
            String gate,
            String cabin,
            String fareClass,
            String fareBrand,
            String seatNumber,
            int checkedBaggageKg,
            int cabinBaggageKg,
            BigDecimal baseFare,
            BigDecimal taxes,
            BigDecimal seatFee,
            BigDecimal totalPaid,
            String paymentMethod,
            String maskedPayment,
            String paymentReference,
            Instant issuedAt,
            Instant cancelledAt,
            BigDecimal refundAmount) { }

    public record CallbackView(
            UUID caseUuid,
            String pnr,
            String channel,
            String status,
            Instant createdAt) { }

    public record CancellationView(
            String pnr,
            String flightNo,
            String origin,
            String destination,
            LocalDate flightDate,
            String status,
            BigDecimal amountPaid,
            BigDecimal cancellationFee,
            BigDecimal refundAmount,
            String refundTimeline,
            Instant cancelledAt) { }
}
