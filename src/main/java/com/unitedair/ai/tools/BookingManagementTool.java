package com.unitedair.ai.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.unitedair.ai.commerce.RefundDtos;
import com.unitedair.ai.commerce.RefundOperationsWorker;
import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.providers.BookingProvider;
import com.unitedair.ai.providers.ProviderCapability;
import com.unitedair.ai.providers.simulator.SimulatorBookingProvider;
import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SRS 2.5 BookingManagementTool - PNR retrieval, itinerary detail and refund workflows.
 *
 * <p>Serves FR-004, FR-007, FR-008 and FR-015.
 *
 * <h2>Read here, mutate elsewhere</h2>
 * This tool retrieves and it quotes. It does not cancel, rebook or refund. SRS 4.2.3
 * forbids confirming a cancellation without verified tool-call confirmation, so anything
 * that changes a booking is raised as an {@code action_request} for explicit human
 * confirmation and executed by {@code ActionService}. A passenger asking "can you cancel
 * this?" gets an accurate quote and a confirm button, never a silent cancellation.
 *
 * <p>PNRs arrive here as the real value resolved from the redaction vault, not as the
 * {@code [AIR-PNR-REDACTED]} token; the token is what gets persisted and logged.
 */
@Component
public class BookingManagementTool {

    public static final String NAME = "BookingManagementTool";
    public static final String PROPOSE_RESCHEDULE = "PROPOSE_RESCHEDULE";

    private final BookingProvider provider;
    private final ToolInvocationLogger logger;
    private final CurrentUser currentUser;
    private final RefundOperationsWorker refundOperations;
    private final DisruptionRecoveryWorker recovery;

    @Autowired
    public BookingManagementTool(
            BookingProvider provider,
            ToolInvocationLogger logger,
            CurrentUser currentUser,
            RefundOperationsWorker refundOperations,
            DisruptionRecoveryWorker recovery) {
        this.provider = provider;
        this.logger = logger;
        this.currentUser = currentUser;
        this.refundOperations = refundOperations;
        this.recovery = recovery;
    }

    public BookingManagementTool(
            BookingRepository bookings,
            ToolInvocationLogger logger) {
        this(new SimulatorBookingProvider(bookings),
                logger, new CurrentUser(), null, null);
    }

    @Tool(name = NAME, description = """
            Use for an owned or authorised PNR, booking proposal, modification proposal,
            cancellation or refund quote, or refund case/status. Never mutate until the
            authenticated user explicitly confirms through the confirmation endpoint.
            """)
    public ToolDtos.GovernedToolResult invoke(
            ToolDtos.BookingManagementToolRequest request) {
        if (request == null || request.operation() == null) {
            throw new ApiExceptions.BadRequest(
                    "A booking-management operation is required.");
        }
        CurrentUser.Authenticated actor = currentUser.require();
        BookingAccess access = new BookingAccess(actor.role(), actor.id());
        Map<String, Object> arguments = request.arguments();
        String pnr = string(arguments, "pnr");
        String operation = request.operation().name();
        return switch (request.operation()) {
            case RETRIEVE_BOOKING -> ToolDtos.GovernedToolResult.from(
                    NAME, operation, retrieve(pnr, access).envelope());
            case QUOTE_CANCELLATION -> ToolDtos.GovernedToolResult.from(
                    NAME, operation, refundQuote(pnr, access).envelope());
            case GET_REFUND_STATUS -> {
                requireWorker(refundOperations, operation);
                yield ToolDtos.GovernedToolResult.from(
                        NAME, operation, refundOperations.status(pnr, access));
            }
            case LIST_REFUND_CASES -> {
                requireWorker(refundOperations, operation);
                Instant dueBefore = instant(arguments, "dueBefore");
                Integer limit = integer(arguments, "limit");
                yield ToolDtos.GovernedToolResult.from(
                        NAME,
                        operation,
                        refundOperations.listCases(
                                new RefundDtos.RefundCaseQuery(
                                        string(arguments, "status"),
                                        dueBefore,
                                        limit == null ? 50 : limit),
                                access));
            }
            case DISRUPTION_ALTERNATIVES -> {
                requireWorker(recovery, operation);
                yield ToolDtos.GovernedToolResult.from(
                        NAME, operation, recovery.invoke(pnr, access));
            }
            case PROPOSE_RESCHEDULE -> {
                Long flightInstanceId = longValue(arguments, "targetFlightInstanceId");
                if (flightInstanceId == null) {
                    throw new ApiExceptions.BadRequest(
                            "targetFlightInstanceId is required for a reschedule proposal.");
                }
                yield ToolDtos.GovernedToolResult.from(
                        NAME,
                        operation,
                        proposeReschedule(
                                pnr,
                                flightInstanceId,
                                string(arguments, "targetFareClass"),
                                access).envelope());
            }
            case PROPOSE_CANCELLATION -> {
                ToolDtos.RefundQuote quote = refundQuote(pnr, access).data();
                yield proposal(
                        operation,
                        Map.of(
                                "pnr", quote.pnr(),
                                "cancellationFee", quote.cancellationFee(),
                                "estimatedRefund", quote.estimatedRefund(),
                                "confirmationRequired", true),
                        "Cancellation has only been proposed. Nothing changes until "
                                + "the authenticated confirmation endpoint is used.");
            }
            case PROPOSE_SEAT_CHANGE -> {
                ToolDtos.BookingView booking = requireBooking(normalise(pnr), access);
                String targetSeat = string(arguments, "targetSeat");
                if (targetSeat == null || !targetSeat.matches("(?i)\\d{1,2}[A-F]")) {
                    throw new ApiExceptions.BadRequest(
                            "A valid targetSeat such as 12A is required.");
                }
                yield proposal(
                        operation,
                        Map.of(
                                "pnr", booking.pnr(),
                                "currentSeat", booking.seatNumber() == null
                                        ? "" : booking.seatNumber(),
                                "targetSeat", targetSeat.toUpperCase(Locale.ROOT),
                                "confirmationRequired", true),
                        "Seat change has only been proposed. Inventory changes require "
                                + "explicit authenticated confirmation.");
            }
            case CREATE_BOOKING_PROPOSAL -> {
                require(arguments, "flightInstanceId", "fareId");
                yield proposal(
                        operation,
                        Map.of(
                                "flightInstanceId", arguments.get("flightInstanceId"),
                                "fareId", arguments.get("fareId"),
                                "confirmationRequired", true),
                        "Booking draft proposed. Traveller, payment and explicit "
                                + "confirmation are still required; no seat was sold.");
            }
        };
    }

    /** Direct entry point used by the orchestrator, with invocation logging. */
    public Outcome<ToolDtos.BookingView> retrieve(String pnr, String actorRole) {
        return retrieve(pnr, new BookingAccess(Role.fromString(actorRole), null));
    }

    public Outcome<ToolDtos.BookingView> retrieve(String pnr, BookingAccess access) {
        String normalised = normalise(pnr);
        Map<String, Object> logged = new LinkedHashMap<>();
        // The PNR identifies a booking, so the log records that a lookup happened and its
        // outcome, not the record locator itself.
        logged.put("pnrProvided", normalised != null);
        logged.put("operation", "RETRIEVE_BOOKING");
        disclose(logged);

        ToolDtos.ToolOutcome outcome = logger.invoke(
                NAME, access.role().name(), "ORCHESTRATOR_WORKER", logged, () -> {
            ToolDtos.BookingView booking = requireBooking(normalised, access);
            return new ToolInvocationLogger.ToolResult(booking, summarise(booking));
        });

        ToolDtos.BookingView data =
                outcome.data() instanceof ToolDtos.BookingView view ? view : null;
        return new Outcome<>(outcome, data);
    }

    public Outcome<ToolDtos.RefundQuote> refundQuote(String pnr, String actorRole) {
        return refundQuote(pnr, new BookingAccess(Role.fromString(actorRole), null));
    }

    public Outcome<ToolDtos.RefundQuote> refundQuote(String pnr, BookingAccess access) {
        String normalised = normalise(pnr);
        Map<String, Object> logged = new LinkedHashMap<>();
        logged.put("pnrProvided", normalised != null);
        logged.put("operation", "QUOTE_CANCELLATION");
        disclose(logged);

        ToolDtos.ToolOutcome outcome = logger.invoke(
                NAME, access.role().name(), "ORCHESTRATOR_WORKER", logged, () -> {
            ToolDtos.BookingView booking = requireBooking(normalised, access);
            ToolDtos.RefundQuote quote = provider.quoteRefund(booking);
            String summary = "Cancelling %s (%s) now incurs INR %s under '%s'; estimated refund INR %s."
                    .formatted(quote.pnr(), quote.fareBrand(),
                            quote.cancellationFee().stripTrailingZeros().toPlainString(),
                            quote.timingBand(),
                            quote.estimatedRefund().stripTrailingZeros().toPlainString());
            return new ToolInvocationLogger.ToolResult(quote, summary);
        });

        ToolDtos.RefundQuote data =
                outcome.data() instanceof ToolDtos.RefundQuote quote ? quote : null;
        return new Outcome<>(outcome, data);
    }

    public Optional<ToolDtos.BookingView> find(String pnr) {
        return provider.retrieve(
                normalise(pnr), new BookingAccess(Role.ADMIN, null));
    }

    public Optional<ToolDtos.BookingView> find(String pnr, BookingAccess access) {
        return provider.retrieve(normalise(pnr), access);
    }

    /** Policy-backed reschedule quote; no inventory changes until ActionService confirms it. */
    public Outcome<ToolDtos.RescheduleQuote> proposeReschedule(
            String pnr,
            Long targetFlightInstanceId,
            String targetFareClass,
            BookingAccess access) {
        String normalised = normalise(pnr);
        Map<String, Object> logged = new LinkedHashMap<>();
        logged.put("pnrProvided", normalised != null);
        logged.put("operation", PROPOSE_RESCHEDULE);
        logged.put("targetFlightInstanceId", targetFlightInstanceId);
        logged.put("targetFareClass", targetFareClass == null ? "" : targetFareClass);
        disclose(logged);

        ToolDtos.ToolOutcome outcome = logger.invoke(
                NAME, access.role().name(), "ORCHESTRATOR_WORKER", logged, () -> {
            ToolDtos.BookingView booking = requireBooking(normalised, access);
            BookingRepository.RescheduleQuote quote = provider.quoteReschedule(
                    booking, targetFlightInstanceId, targetFareClass);
            ToolDtos.RescheduleQuote view = new ToolDtos.RescheduleQuote(
                    booking.pnr(),
                    booking.flightNo(),
                    quote.flightNo(),
                    quote.changeFee(),
                    quote.fareDifference(),
                    quote.totalDue(),
                    "KB-AIR-004",
                    "4.1 Change Fee Matrix and 4.2 Rescheduling Process",
                    java.time.Instant.now());
            return new ToolInvocationLogger.ToolResult(
                    view,
                    "Reschedule to " + quote.flightNo() + " for INR "
                            + quote.totalDue().stripTrailingZeros().toPlainString() + ".");
        });
        ToolDtos.RescheduleQuote data =
                outcome.data() instanceof ToolDtos.RescheduleQuote quote ? quote : null;
        return new Outcome<>(outcome, data);
    }

    // ------------------------------------------------------------------ helpers ---

    private ToolDtos.BookingView requireBooking(String pnr, BookingAccess access) {
        if (pnr == null) {
            throw new ApiExceptions.BadRequest(
                    "I need your 6-character booking reference (PNR) to look that up.");
        }
        return provider.retrieve(pnr, access).orElseThrow(() -> new ApiExceptions.NotFound(
                "No booking found for that reference. Please check the 6 characters and try again, "
                        + "or contact your UnitedAir Customer Support Manager."));
    }

    private static String normalise(String pnr) {
        if (pnr == null) {
            return null;
        }
        String cleaned = pnr.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return cleaned.length() == 6 ? cleaned : null;
    }

    private static String summarise(ToolDtos.BookingView b) {
        return "%s: %s %s-%s on %s, %s %s (%s), status %s%s."
                .formatted(b.pnr(), b.flightNo(), b.origin(), b.destination(), b.flightDate(),
                        b.cabin(), b.fareBrand(), b.refundable() ? "refundable" : "non-refundable",
                        b.status(), b.checkedIn() ? ", checked in seat " + b.seatNumber() : "");
    }

    private ToolDtos.GovernedToolResult proposal(
            String operation, Object data, String message) {
        return ToolDtos.GovernedToolResult.proposal(NAME, operation, data, message);
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null || value.toString().isBlank()
                ? null : value.toString().trim();
    }

    private static Integer integer(Map<String, Object> arguments, String key) {
        Long value = longValue(arguments, key);
        return value == null ? null : Math.toIntExact(value);
    }

    private static Long longValue(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return value instanceof Number number
                    ? number.longValue() : Long.parseLong(value.toString());
        } catch (NumberFormatException invalid) {
            throw new ApiExceptions.BadRequest(key + " must be a whole number.");
        }
    }

    private static Instant instant(Map<String, Object> arguments, String key) {
        String value = string(arguments, key);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw new ApiExceptions.BadRequest(key + " must be an ISO-8601 instant.");
        }
    }

    private static void require(Map<String, Object> arguments, String... keys) {
        for (String key : keys) {
            if (!arguments.containsKey(key) || arguments.get(key) == null) {
                throw new ApiExceptions.BadRequest(key + " is required.");
            }
        }
    }

    private static void requireWorker(Object worker, String operation) {
        if (worker == null) {
            throw new ApiExceptions.BadRequest(
                    operation + " is unavailable outside the application context.");
        }
    }

    public ProviderCapability capability() {
        return provider.capability();
    }

    private void disclose(Map<String, Object> request) {
        ProviderCapability capability = provider.capability();
        request.put("provider", capability.provider());
        request.put("providerLive", capability.live());
    }

    /** Pairs the audit envelope with the typed payload. */
    public record Outcome<T>(ToolDtos.ToolOutcome envelope, T data) { }
}
