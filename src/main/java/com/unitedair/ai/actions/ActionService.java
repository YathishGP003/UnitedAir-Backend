package com.unitedair.ai.actions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.audit.OperationalDecisionService;
import com.unitedair.ai.commerce.RefundDtos;
import com.unitedair.ai.commerce.RefundWorkItemService;
import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.Json;
import com.unitedair.ai.shared.TraceContext;
import com.unitedair.ai.tools.BookingRepository;
import com.unitedair.ai.tools.BookingManagementTool;
import com.unitedair.ai.tools.BookingAccess;
import com.unitedair.ai.tools.ToolDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-phase mutations.
 *
 * <p>SRS 4.2.3 forbids confirming a booking or cancellation without verified tool-call
 * confirmation, so nothing here changes state on the strength of a conversational turn.
 * Every mutation is first <em>proposed</em>: the system computes exactly what will happen,
 * quotes the fees with the KB citations that justify them, and returns a pending action.
 * Only an explicit confirmation against that action's id carries it out.
 *
 * <p>Proposals expire. A refund quote calculated three hours ago may sit in a different fee
 * band by the time it is confirmed, and executing it at the stale figure would be wrong.
 */
@Service
public class ActionService {

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);

    /** How long a quoted action stays valid. Long enough to read, short enough to stay true. */
    private static final long EXPIRY_MINUTES = 15;

    private final JdbcClient jdbc;
    private final BookingManagementTool bookingTool;
    private final BookingRepository bookings;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final RefundWorkItemService refunds;
    private final OperationalDecisionService decisions;

    public ActionService(JdbcClient jdbc,
                         BookingManagementTool bookingTool,
                         BookingRepository bookings,
                         AuditService audit,
                         CurrentUser currentUser,
                         RefundWorkItemService refunds,
                         OperationalDecisionService decisions) {
        this.jdbc = jdbc;
        this.bookingTool = bookingTool;
        this.bookings = bookings;
        this.audit = audit;
        this.currentUser = currentUser;
        this.refunds = refunds;
        this.decisions = decisions;
    }

    // ------------------------------------------------------------------ propose ---

    public ActionDtos.ActionView propose(ActionDtos.ProposeRequest request, String sessionUuid) {
        CurrentUser.Authenticated user = currentUser.require();
        return propose(request, sessionUuid, user.id(), user.role());
    }

    public ActionDtos.ActionView propose(ActionDtos.ProposeRequest request,
                                         String sessionUuid,
                                         Long userId) {
        return propose(request, sessionUuid, userId, Role.PASSENGER);
    }

    public ActionDtos.ActionView propose(ActionDtos.ProposeRequest request,
                                         String sessionUuid,
                                         Long userId,
                                         Role actorRole) {
        ActionDtos.ActionType type = ActionDtos.ActionType.parse(request.type());
        BookingAccess access = new BookingAccess(actorRole, userId);
        ToolDtos.BookingView booking = bookingTool.find(request.pnr(), access)
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "No booking found for that reference."));

        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, String>> citations;

        switch (type) {
            case CANCEL_BOOKING -> {
                if (!"CONFIRMED".equals(booking.status())) {
                    throw new ApiExceptions.Conflict(
                            "This booking is already " + booking.status().toLowerCase() + ".");
                }
                ToolDtos.RefundQuote quote = bookings.quoteRefund(booking);
                summary.put("action", "Cancel booking " + booking.pnr());
                summary.put("flight", booking.flightNo() + " " + booking.origin()
                        + "-" + booking.destination() + " on " + booking.flightDate());
                summary.put("fareBrand", quote.fareBrand());
                summary.put("timingBand", quote.timingBand());
                summary.put("amountPaid", quote.amountPaid());
                summary.put("cancellationFee", quote.cancellationFee());
                summary.put("estimatedRefund", quote.estimatedRefund());
                summary.put("refundTimeline", quote.refundTimeline());
                summary.put("policyDocumentCode", quote.policyDocumentCode());
                summary.put("policySection", quote.policySection());
                summary.put("calculatedAt", quote.calculatedAt());
                summary.put("warning", "This cannot be undone. The seat is released immediately.");
                citations = List.of(Map.of(
                        "documentCode", "KB-AIR-004",
                        "section", "2.1 Cancellation Fee Matrix by Fare Type"));
            }
            case CHECK_IN -> {
                ToolDtos.CheckInEligibility eligibility = bookings.checkInEligibility(booking);
                if (!eligibility.eligible()) {
                    throw new ApiExceptions.Conflict(eligibility.reason());
                }
                summary.put("action", "Check in for booking " + booking.pnr());
                summary.put("flight", booking.flightNo() + " " + booking.origin()
                        + "-" + booking.destination() + " on " + booking.flightDate());
                summary.put("passenger", booking.passengerName());
                summary.put("seat", booking.seatNumber() == null
                        ? "Assigned automatically" : booking.seatNumber());
                summary.put("terminal", booking.terminal());
                summary.put("note", "A boarding pass will be issued and the seat confirmed.");
                summary.put("policyDocumentCode", "KB-AIR-002");
                summary.put("policySection", "Check-in windows and channels");
                citations = List.of(Map.of(
                        "documentCode", "KB-AIR-002",
                        "section", "Check-in windows and channels"));
            }
            case RESCHEDULE_BOOKING -> {
                BookingRepository.RescheduleQuote quote = bookings.quoteReschedule(
                        booking, request.targetFlightInstanceId(), request.targetFareClass());
                summary.put("action", "Reschedule booking " + booking.pnr());
                summary.put("currentFlight", booking.flightNo() + " on " + booking.flightDate());
                summary.put("targetFlightInstanceId", quote.targetFlightInstanceId());
                summary.put("targetFareClass", quote.fareClass());
                summary.put("newFlight", quote.flightNo() + " " + quote.origin() + "-"
                        + quote.destination() + " on " + quote.flightDate()
                        + " at " + quote.departureTime());
                summary.put("fareBrand", quote.fareBrand());
                summary.put("targetFare", quote.targetFare());
                summary.put("fareDifference", quote.fareDifference());
                summary.put("changeFee", quote.changeFee());
                summary.put("totalDue", quote.totalDue());
                summary.put("creditIfLowerFare", quote.credit());
                summary.put("policyDocumentCode", "KB-AIR-004");
                summary.put("policySection",
                        "4.1 Change Fee Matrix and 4.2 Rescheduling Process");
                summary.put("calculatedAt", Instant.now());
                summary.put("warning", "Your current seat and check-in are released. "
                        + "Any amount due is shown for servicing; this demo does not take payment.");
                citations = List.of(Map.of(
                        "documentCode", "KB-AIR-004",
                        "section", "4.1 Change Fee Matrix and 4.2 Rescheduling Process"));
            }
            case SEAT_CHANGE -> {
                BookingRepository.SeatChangeQuote quote =
                        bookings.quoteSeatChange(booking, request.seatNumber());
                ToolDtos.SeatOption seat = quote.targetSeat();
                summary.put("action", "Change seat for booking " + booking.pnr());
                summary.put("flightInstanceId", quote.flightInstanceId());
                summary.put("currentSeat", quote.currentSeat() == null
                        ? "Unassigned" : quote.currentSeat());
                summary.put("seatNumber", seat.seatNumber());
                summary.put("seatType", seat.seatType());
                summary.put("cabin", seat.cabin());
                summary.put("extraLegroom", seat.extraLegroom());
                summary.put("exitRow", seat.exitRow());
                BigDecimal charge = seat.feeInr() == null ? BigDecimal.ZERO : seat.feeInr();
                summary.put("additionalCharge", charge);
                summary.put("includedWithFare", charge.signum() == 0);
                summary.put("currentAmountPaid", booking.amountPaid());
                summary.put("updatedAmountPaid", booking.amountPaid().add(charge));
                summary.put("policyDocumentCode", "KB-AIR-005");
                summary.put("policySection", "3.1 Seat Categories and Fees");
                summary.put("warning", seat.exitRow()
                        ? "Exit-row eligibility must be confirmed at check-in."
                        : "Your previous seat will be released.");
                citations = List.of(Map.of(
                        "documentCode", "KB-AIR-005",
                        "section", "3.1 Seat Categories and Fees"));
            }
            default -> throw new ApiExceptions.BadRequest(
                    "Action type " + type + " is not available in this build.");
        }

        String actionUuid = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(EXPIRY_MINUTES, ChronoUnit.MINUTES);

        jdbc.sql("""
                    INSERT INTO action_request
                        (action_uuid, session_uuid, trace_id, user_id, type, status,
                         subject_pnr, summary_json, citations_json, expires_at)
                    VALUES
                        (:uuid, :session, :trace, :userId, :type, 'PENDING',
                         :pnr, :summary, :citations, :expires)
                """)
                .param("uuid", actionUuid)
                .param("session", sessionUuid)
                .param("trace", TraceContext.traceId())
                .param("userId", userId, java.sql.Types.BIGINT)
                .param("type", type.name())
                .param("pnr", booking.pnr())
                .param("summary", Json.write(summary))
                .param("citations", Json.write(citations))
                .param("expires", java.sql.Timestamp.from(expiresAt))
                .update();

        audit.record("ACTION_PROPOSED", null, null,
                Map.of("actionUuid", actionUuid, "type", type.name(), "pnrProvided", true));
        decisions.recordProposal(
                actionUuid, type,
                new CurrentUser.Authenticated(userId, null, null, actorRole),
                booking.pnr(), sessionUuid, summary);

        return new ActionDtos.ActionView(actionUuid, type.name(), "PENDING", booking.pnr(),
                summary, citations, null, Instant.now(), expiresAt, null);
    }

    // ------------------------------------------------------------------ confirm ---

    @Transactional
    public ActionDtos.ActionView confirm(String actionUuid) {
        ActionDtos.ActionRow row = require(actionUuid);

        if ("CONFIRMED".equals(row.status())) {
            return view(row);
        }
        if (!"PENDING".equals(row.status())) {
            throw new ApiExceptions.Conflict("This action is already " + row.status().toLowerCase() + ".");
        }
        if (row.expiresAt() != null && Instant.now().isAfter(row.expiresAt())) {
            settle(actionUuid, "EXPIRED", null, "The quote expired before it was confirmed.");
            throw new ApiExceptions.Conflict(
                    "This quote has expired. Please ask again so the fees can be recalculated.");
        }

        ActionDtos.ActionType type = ActionDtos.ActionType.parse(row.type());
        CurrentUser.Authenticated authenticated = currentUser.require();
        BookingAccess access = BookingAccess.from(authenticated);
        ToolDtos.BookingView booking = bookingTool.find(row.subjectPnr(), access)
                .orElseThrow(() -> new ApiExceptions.NotFound("The booking no longer exists."));

        Map<String, Object> result = new LinkedHashMap<>();

        switch (type) {
            case CANCEL_BOOKING -> {
                ToolDtos.RefundQuote quote = bookings.quoteRefund(booking);
                Map<String, Object> proposal = Json.readMap(row.summaryJson());
                ensureRefundQuoteUnchanged(proposal, quote);
                bookings.cancelBooking(booking.pnr(), quote.estimatedRefund());
                RefundDtos.RefundCaseView refundCase = refunds.createForCancellation(
                        authenticated.id(), booking.pnr(), quote);
                result.putAll(cancellationResult(quote, refundCase));
            }
            case CHECK_IN -> {
                ToolDtos.CheckInResult checkIn = bookings.performCheckIn(booking, "WEB");
                result.put("seat", checkIn.seat());
                result.put("boardingGate", checkIn.boardingGate());
                result.put("sequenceNumber", checkIn.sequenceNumber());
                result.put("message", checkIn.message());
            }
            case RESCHEDULE_BOOKING -> {
                Map<String, Object> proposal = Json.readMap(row.summaryJson());
                Long targetInstance = number(proposal.get("targetFlightInstanceId")).longValue();
                String targetFareClass = String.valueOf(proposal.get("targetFareClass"));
                BookingRepository.RescheduleQuote quote =
                        bookings.quoteReschedule(booking, targetInstance, targetFareClass);
                ensureRescheduleQuoteUnchanged(proposal, quote);
                bookings.reschedule(booking.pnr(), targetInstance, targetFareClass);
                result.put("flightNo", quote.flightNo());
                result.put("flightDate", quote.flightDate());
                result.put("departureTime", quote.departureTime());
                result.put("fareClass", quote.fareClass());
                result.put("totalDue", quote.totalDue());
                result.put("creditIfLowerFare", quote.credit());
                result.put("message", "Booking " + booking.pnr() + " is rescheduled to "
                        + quote.flightNo() + " on " + quote.flightDate()
                        + ". Choose a new seat before check-in.");
            }
            case SEAT_CHANGE -> {
                Map<String, Object> proposal = Json.readMap(row.summaryJson());
                String seat = String.valueOf(proposal.get("seatNumber"));
                bookings.quoteSeatChange(booking, seat);
                result.putAll(seatChangeResult(bookings.changeSeat(booking.pnr(), seat)));
            }
            default -> throw new ApiExceptions.BadRequest("Unsupported action type.");
        }

        settle(actionUuid, "CONFIRMED", result, null);
        decisions.recordOutcome(
                actionUuid, "APPROVED", null, authenticated, result);
        audit.record("ACTION_SETTLED", null, null,
                Map.of("actionUuid", actionUuid, "type", type.name(), "status", "CONFIRMED"));

        log.info("Action {} ({}) confirmed for {}", actionUuid, type, booking.pnr());
        return view(require(actionUuid));
    }

    static Map<String, Object> seatChangeResult(
            BookingRepository.SeatChangeResult seatChange) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean included = seatChange.additionalCharge() == null
                || seatChange.additionalCharge().signum() == 0;
        result.put("previousSeat", seatChange.previousSeat());
        result.put("seatNumber", seatChange.selectedSeat());
        result.put("seatType", seatChange.seatType());
        result.put("additionalCharge", seatChange.additionalCharge());
        result.put("includedWithFare", included);
        result.put("updatedAmountPaid", seatChange.updatedAmountPaid());
        if (included) {
            result.put("message", "Seat changed to " + seatChange.selectedSeat()
                    + ". This seat is included with this fare; no additional charge was added.");
        } else {
            result.put("message", "Seat changed to " + seatChange.selectedSeat()
                    + ". INR " + money(seatChange.additionalCharge())
                    + " was added; the new booking total is INR "
                    + money(seatChange.updatedAmountPaid()) + ".");
        }
        return result;
    }

    static Map<String, Object> cancellationResult(
            ToolDtos.RefundQuote quote,
            RefundDtos.RefundCaseView refundCase) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", quote.estimatedRefund().signum() > 0
                ? "REFUND_PENDING" : "CANCELLED");
        result.put("refundAmount", quote.estimatedRefund());
        result.put("refundCaseUuid", refundCase.caseUuid().toString());
        result.put("refundStatus", refundCase.status().name());
        result.put("refundDueAt", refundCase.dueAt());
        result.put("message", "Booking " + quote.pnr()
                + " is cancelled. Refund tracking case " + refundCase.caseUuid()
                + " is " + refundCase.status().name().toLowerCase(Locale.ROOT)
                + " for INR " + money(quote.estimatedRefund())
                + " to the original payment method.");
        return result;
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    public ActionDtos.ActionView cancel(String actionUuid) {
        ActionDtos.ActionRow row = require(actionUuid);
        if (!"PENDING".equals(row.status())) {
            throw new ApiExceptions.Conflict("This action is already " + row.status().toLowerCase() + ".");
        }
        settle(actionUuid, "CANCELLED", null, "Cancelled by the user.");
        decisions.recordOutcome(
                actionUuid,
                "CANCELLED",
                "Cancelled by the user.",
                currentUser.find().orElse(null),
                Map.of());
        audit.record("ACTION_SETTLED", null, null,
                Map.of("actionUuid", actionUuid, "status", "CANCELLED"));
        return view(require(actionUuid));
    }

    public ActionDtos.ActionView get(String actionUuid) {
        return view(require(actionUuid));
    }

    // ------------------------------------------------------------------ internals ---

    private void settle(String actionUuid, String status, Map<String, Object> result, String reason) {
        jdbc.sql("""
                    UPDATE action_request
                       SET status = :status, result_json = :result, failure_reason = :reason,
                           settled_at = CURRENT_TIMESTAMP
                     WHERE action_uuid = :uuid
                """)
                .param("status", status)
                .param("result", result == null ? null : Json.write(result))
                .param("reason", reason)
                .param("uuid", actionUuid)
                .update();
    }

    private static Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException invalid) {
            throw new ApiExceptions.Conflict(
                    "The stored action quote is incomplete. Please create a new proposal.");
        }
    }

    static void ensureRefundQuoteUnchanged(
            Map<String, Object> proposal,
            ToolDtos.RefundQuote current) {
        if (!sameMoney(proposal.get("amountPaid"), current.amountPaid())
                || !sameMoney(proposal.get("cancellationFee"), current.cancellationFee())
                || !sameMoney(proposal.get("estimatedRefund"), current.estimatedRefund())
                || !java.util.Objects.equals(
                        String.valueOf(proposal.get("timingBand")),
                        current.timingBand())) {
            throw new ApiExceptions.Conflict(
                    "The cancellation quote changed before confirmation. "
                            + "No change was made; review the fresh fee and refund quote.");
        }
    }

    static void ensureRescheduleQuoteUnchanged(
            Map<String, Object> proposal,
            BookingRepository.RescheduleQuote current) {
        if (!sameMoney(proposal.get("targetFare"), current.targetFare())
                || !sameMoney(proposal.get("fareDifference"), current.fareDifference())
                || !sameMoney(proposal.get("changeFee"), current.changeFee())
                || !sameMoney(proposal.get("totalDue"), current.totalDue())) {
            throw new ApiExceptions.Conflict(
                    "The reschedule fare changed before confirmation. "
                            + "No change was made; review the fresh quote.");
        }
    }

    private static boolean sameMoney(Object stored, BigDecimal current) {
        if (stored == null || current == null) {
            return stored == null && current == null;
        }
        try {
            return new BigDecimal(String.valueOf(stored))
                    .compareTo(current) == 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private ActionDtos.ActionRow require(String actionUuid) {
        ActionDtos.ActionRow row = jdbc.sql("""
                    SELECT action_uuid, session_uuid, user_id, type, status, subject_pnr,
                           summary_json, citations_json, result_json, failure_reason,
                           created_at, expires_at, settled_at
                    FROM action_request WHERE action_uuid = :uuid
                """)
                .param("uuid", actionUuid)
                .query((rs, n) -> new ActionDtos.ActionRow(
                        rs.getString("action_uuid"),
                        rs.getString("session_uuid"),
                        (Long) rs.getObject("user_id"),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getString("subject_pnr"),
                        rs.getString("summary_json"),
                        rs.getString("citations_json"),
                        rs.getString("result_json"),
                        rs.getString("failure_reason"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("settled_at") == null ? null : rs.getTimestamp("settled_at").toInstant()))
                .optional()
                .orElseThrow(() -> new ApiExceptions.NotFound("No such action."));

        // An action belongs to the user who was quoted it.
        Long callerId = currentUser.find().map(CurrentUser.Authenticated::id).orElse(null);
        if (row.userId() != null && callerId != null && !row.userId().equals(callerId)) {
            throw new ApiExceptions.Forbidden("This action belongs to another user.");
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private ActionDtos.ActionView view(ActionDtos.ActionRow row) {
        return new ActionDtos.ActionView(
                row.actionUuid(),
                row.type(),
                row.status(),
                row.subjectPnr(),
                Json.readMap(row.summaryJson()),
                (List<Map<String, String>>) (List<?>) Json.read(row.citationsJson(), List.class),
                Json.readMap(row.resultJson()),
                row.createdAt(),
                row.expiresAt(),
                row.failureReason());
    }

    /** Sweeps quotes nobody acted on, so the pending list reflects reality. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void expireStaleProposals() {
        int expired = jdbc.sql("""
                    UPDATE action_request
                       SET status = 'EXPIRED',
                           failure_reason = 'Quote expired before confirmation.',
                           settled_at = CURRENT_TIMESTAMP
                     WHERE status = 'PENDING' AND expires_at < NOW()
                """).update();
        if (expired > 0) {
            log.debug("Expired {} stale action proposal(s)", expired);
        }
    }

    /** Convenience for the UI: the caller's pending proposals in a session. */
    public List<ActionDtos.ActionView> pendingFor(String sessionUuid) {
        return jdbc.sql("""
                    SELECT action_uuid FROM action_request
                    WHERE session_uuid = :s AND status = 'PENDING'
                    ORDER BY created_at DESC
                """)
                .param("s", sessionUuid)
                .query(String.class)
                .list()
                .stream()
                .map(this::get)
                .toList();
    }

    /** Refund arithmetic exposed for the UI card, without creating a proposal. */
    public ToolDtos.RefundQuote quote(String pnr) {
        BookingAccess access = BookingAccess.from(currentUser.require());
        ToolDtos.BookingView booking = bookingTool.find(pnr, access)
                .orElseThrow(() -> new ApiExceptions.NotFound("No booking found for that reference."));
        return bookings.quoteRefund(booking);
    }

    /** Used by tests to assert that nothing mutates without confirmation. */
    BigDecimal refundAmountFor(String pnr) {
        ToolDtos.BookingView booking = bookingTool.find(
                        pnr, new BookingAccess(Role.AIRLINE_STAFF, null))
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "No booking found for that reference."));
        return bookings.quoteRefund(booking).estimatedRefund();
    }
}
