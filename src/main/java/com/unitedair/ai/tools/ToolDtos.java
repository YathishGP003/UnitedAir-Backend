package com.unitedair.ai.tools;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.unitedair.ai.identity.Role;

/** Request and result shapes for the four registered tools of SRS 2.5. */
public final class ToolDtos {

    private ToolDtos() { }

    // ------------------------------------------- governed Spring AI registry ---

    public enum FlightSearchOperation {
        SEARCH_FLIGHTS,
        GET_SEAT_MAP,
        GET_MEAL_AVAILABILITY,
        QUOTE_EXCESS_BAGGAGE,
        LIST_SUPPORTED_AIRPORTS
    }

    public enum BookingManagementOperation {
        RETRIEVE_BOOKING,
        CREATE_BOOKING_PROPOSAL,
        QUOTE_CANCELLATION,
        GET_REFUND_STATUS,
        LIST_REFUND_CASES,
        PROPOSE_CANCELLATION,
        PROPOSE_RESCHEDULE,
        PROPOSE_SEAT_CHANGE,
        DISRUPTION_ALTERNATIVES
    }

    public enum CheckInStatusOperation {
        GET_FLIGHT_STATUS,
        GET_CHECKIN_ELIGIBILITY,
        PROPOSE_CHECK_IN,
        SUBSCRIBE_GATE_TERMINAL
    }

    public enum EscalationOperation {
        CREATE_ESCALATION
    }

    public record FlightSearchToolRequest(
            FlightSearchOperation operation,
            Map<String, Object> arguments) {
        public FlightSearchToolRequest {
            arguments = immutable(arguments);
        }
    }

    public record BookingManagementToolRequest(
            BookingManagementOperation operation,
            Map<String, Object> arguments) {
        public BookingManagementToolRequest {
            arguments = immutable(arguments);
        }
    }

    public record CheckInStatusToolRequest(
            CheckInStatusOperation operation,
            Map<String, Object> arguments) {
        public CheckInStatusToolRequest {
            arguments = immutable(arguments);
        }
    }

    public record EscalationToolRequest(
            EscalationOperation operation,
            Map<String, Object> arguments) {
        public EscalationToolRequest {
            arguments = immutable(arguments);
        }
    }

    public record ProposedToolCall(
            String toolFamily,
            String operation,
            Map<String, Object> arguments) {
        public ProposedToolCall {
            arguments = immutable(arguments);
        }
    }

    public record ValidatedToolCall(
            String toolFamily,
            String operation,
            Map<String, Object> arguments,
            Role actorRole,
            String sessionUuid,
            String traceId) {
        public ValidatedToolCall {
            arguments = immutable(arguments);
        }
    }

    public record GovernedToolResult(
            String toolFamily,
            String operation,
            String status,
            Object data,
            String message,
            boolean mutationPerformed) {

        public GovernedToolResult {
            if (mutationPerformed) {
                throw new IllegalArgumentException(
                        "Spring AI tools may read, quote or propose; they may not mutate.");
            }
        }

        public static GovernedToolResult from(
                String family,
                String operation,
                ToolOutcome outcome) {
            return new GovernedToolResult(
                    family,
                    operation,
                    outcome.success() ? "EXECUTED" : "REJECTED",
                    outcome.data(),
                    outcome.success() ? outcome.summary() : outcome.errorMessage(),
                    false);
        }

        public static GovernedToolResult proposal(
                String family,
                String operation,
                Object data,
                String message) {
            return new GovernedToolResult(
                    family, operation, "PROPOSED", data, message, false);
        }
    }

    private static Map<String, Object> immutable(Map<String, Object> arguments) {
        return arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    // ------------------------------------------------------ FlightSearchTool ---

    public record FlightSearchRequest(
            String origin,
            String destination,
            LocalDate departureDate,
            String cabin,
            Integer adults,
            Integer children,
            Integer infants) { }

    public record FareOption(
            Long fareId,
            String fareClass,
            String cabin,
            String fareBrand,
            BigDecimal baseFare,
            BigDecimal taxes,
            BigDecimal totalFare,
            boolean refundable,
            boolean changeable,
            BigDecimal changeFee,
            BigDecimal cancelFee,
            int checkedBaggageKg,
            int cabinBaggageKg,
            int seatsAvailable,
            int ffpAccrualPct) { }

    public record FlightOption(
            Long flightInstanceId,
            String flightNo,
            String origin,
            String originCity,
            String destination,
            String destinationCity,
            LocalDate flightDate,
            LocalTime departureTime,
            LocalTime arrivalTime,
            int durationMinutes,
            String aircraft,
            boolean international,
            String status,
            int delayMinutes,
            String terminal,
            String gate,
            List<FareOption> fares,
            MealAvailability mealAvailability) {

        /** Backward-compatible constructor for callers that do not request service data. */
        public FlightOption(
                Long flightInstanceId,
                String flightNo,
                String origin,
                String originCity,
                String destination,
                String destinationCity,
                LocalDate flightDate,
                LocalTime departureTime,
                LocalTime arrivalTime,
                int durationMinutes,
                String aircraft,
                boolean international,
                String status,
                int delayMinutes,
                String terminal,
                String gate,
                List<FareOption> fares) {
            this(
                    flightInstanceId, flightNo, origin, originCity, destination,
                    destinationCity, flightDate, departureTime, arrivalTime,
                    durationMinutes, aircraft, international, status, delayMinutes,
                    terminal, gate, fares, null);
        }
    }

    /** Verified service availability for one dated departure, not a generic meal-code list. */
    public record MealAvailability(
            String flightNo,
            LocalDate date,
            boolean mealService,
            Set<String> availableCodes,
            Instant orderDeadline) {

        public MealAvailability {
            availableCodes = availableCodes == null ? Set.of() : Set.copyOf(availableCodes);
        }
    }

    /** Simulator quote calculated from the effective baggage rate table. */
    public record ExcessBaggageQuote(
            String routeType,
            String cabin,
            int excessKg,
            BigDecimal advanceFee,
            BigDecimal airportFee,
            String currency,
            Instant retrievedAt) { }

    public record FlightSearchResult(
            String origin,
            String destination,
            LocalDate departureDate,
            String cabinFilter,
            int resultCount,
            List<FlightOption> flights,
            Instant retrievedAt) { }

    // -------------------------------------------------- BookingManagementTool ---

    public record BookingView(
            String pnr,
            String passengerName,
            String status,
            String flightNo,
            String origin,
            String destination,
            LocalDate flightDate,
            LocalTime departureTime,
            LocalTime arrivalTime,
            String flightStatus,
            int delayMinutes,
            String terminal,
            String gate,
            String cabin,
            String fareClass,
            String fareBrand,
            boolean refundable,
            boolean changeable,
            BigDecimal changeFee,
            BigDecimal cancelFee,
            BigDecimal amountPaid,
            BigDecimal refundAmount,
            String seatNumber,
            int checkedBaggageKg,
            String ffpTier,
            boolean checkedIn,
            String boardingGate,
            Instant bookedAt,
            Instant retrievedAt) { }

    /**
     * Refund eligibility computed from the booking's own fare rules and timing.
     *
     * <p>The KB fee matrix (KB_04 2.1) remains the authority the assistant must cite; this
     * gives the arithmetic for this specific booking so the answer can be concrete rather
     * than quoting a table and leaving the passenger to do the sums.
     */
    public record RefundQuote(
            String pnr,
            String fareBrand,
            boolean refundable,
            long hoursToDeparture,
            String timingBand,
            BigDecimal amountPaid,
            BigDecimal cancellationFee,
            BigDecimal estimatedRefund,
            String refundTimeline,
            String basis,
            Instant calculatedAt) {

        public RefundQuote {
            amountPaid = amountPaid == null ? BigDecimal.ZERO : amountPaid;
            cancellationFee = cancellationFee == null ? BigDecimal.ZERO : cancellationFee;
            estimatedRefund = estimatedRefund == null ? BigDecimal.ZERO : estimatedRefund;
            calculatedAt = calculatedAt == null ? Instant.now() : calculatedAt;
            if (amountPaid.subtract(cancellationFee)
                    .max(BigDecimal.ZERO)
                    .compareTo(estimatedRefund) != 0) {
                throw new IllegalArgumentException("Refund arithmetic is inconsistent");
            }
        }

        public RefundQuote(
                String pnr,
                String fareBrand,
                boolean refundable,
                long hoursToDeparture,
                String timingBand,
                BigDecimal amountPaid,
                BigDecimal cancellationFee,
                BigDecimal estimatedRefund,
                String refundTimeline,
                String basis) {
            this(
                    pnr, fareBrand, refundable, hoursToDeparture, timingBand,
                    amountPaid, cancellationFee, estimatedRefund, refundTimeline,
                    basis, Instant.now());
        }

        public BigDecimal appliedCancellationFee() {
            return cancellationFee;
        }

        public String policyDocumentCode() {
            return "KB-AIR-004";
        }

        public String policySection() {
            return "2.1 Cancellation Fee Matrix by Fare Type";
        }
    }

    public record RescheduleQuote(
            String pnr,
            String currentFlight,
            String replacementFlight,
            BigDecimal changeFee,
            BigDecimal fareDifference,
            BigDecimal totalDue,
            String policyDocumentCode,
            String policySection,
            Instant calculatedAt) {

        public RescheduleQuote {
            changeFee = changeFee == null ? BigDecimal.ZERO : changeFee;
            fareDifference = fareDifference == null ? BigDecimal.ZERO : fareDifference;
            totalDue = totalDue == null ? BigDecimal.ZERO : totalDue;
            calculatedAt = calculatedAt == null ? Instant.now() : calculatedAt;
            if (changeFee.add(fareDifference.max(BigDecimal.ZERO))
                    .compareTo(totalDue) != 0) {
                throw new IllegalArgumentException(
                        "Reschedule arithmetic is inconsistent");
            }
        }
    }

    // ------------------------------------------------------ CheckInStatusTool ---

    public record FlightStatusView(
            String flightNo,
            LocalDate flightDate,
            String origin,
            String destination,
            String status,
            int delayMinutes,
            LocalTime scheduledDeparture,
            LocalTime estimatedDeparture,
            LocalTime scheduledArrival,
            String terminal,
            String gate,
            String belt,
            Instant retrievedAt) { }

    public record CheckInEligibility(
            String pnr,
            boolean eligible,
            String reason,
            long hoursToDeparture,
            Instant windowOpensAt,
            Instant windowClosesAt,
            boolean alreadyCheckedIn,
            String seat,
            String boardingGate,
            List<String> availableChannels) { }

    public record CheckInResult(
            String pnr,
            boolean success,
            String seat,
            String boardingGate,
            String terminal,
            Integer sequenceNumber,
            String channel,
            Instant checkedInAt,
            String message) { }

    public record SeatOption(
            String seatNumber,
            String cabin,
            String seatType,
            boolean extraLegroom,
            boolean exitRow,
            BigDecimal feeInr,
            boolean available) { }

    // --------------------------------------------- DisruptionRecoveryWorker ---

    public record RecoveryAlternative(
            Long flightInstanceId,
            String flightNo,
            LocalDate flightDate,
            LocalTime departureTime,
            LocalTime arrivalTime,
            String cabin,
            String fareClass,
            String fareBrand,
            BigDecimal totalFare,
            BigDecimal fareDifference,
            int availableSeats) { }

    public record RecoveryPlan(
            BookingView original,
            String reason,
            List<RecoveryAlternative> alternatives,
            Instant generatedAt) { }

    // ---------------------------------------------------------- EscalationTool ---

    public record EscalationRequest(
            String reason,
            String summary,
            String pnr,
            Double confidence,
            String priority) { }

    public record EscalationResult(
            String caseUuid,
            String reason,
            String targetQueue,
            String priority,
            String status,
            String message,
            Instant createdAt) { }

    // --------------------------------------------------------------- envelope ---

    /**
     * Uniform wrapper so the orchestrator can treat every tool identically and the trace
     * panel can render any tool call without knowing its payload shape.
     */
    public record ToolOutcome(
            String toolName,
            boolean success,
            Object data,
            String summary,
            String errorMessage,
            long durationMs,
            Instant invokedAt,
            Map<String, Object> request) {

        public static ToolOutcome ok(String toolName, Object data, String summary,
                                     long durationMs, Instant invokedAt,
                                     Map<String, Object> request) {
            return new ToolOutcome(toolName, true, data, summary, null, durationMs, invokedAt, request);
        }

        public static ToolOutcome failed(String toolName, String error, long durationMs,
                                         Instant invokedAt, Map<String, Object> request) {
            return new ToolOutcome(toolName, false, null, null, error, durationMs, invokedAt, request);
        }

        public static ToolOutcome failed(String toolName, OperationalFailure failure,
                                         long durationMs, Instant invokedAt,
                                         Map<String, Object> request) {
            return new ToolOutcome(
                    toolName,
                    false,
                    failure,
                    null,
                    failure.userMessage(),
                    durationMs,
                    invokedAt,
                    request);
        }
    }
}
