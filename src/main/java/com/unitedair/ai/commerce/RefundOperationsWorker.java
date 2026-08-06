package com.unitedair.ai.commerce;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.providers.BookingProvider;
import com.unitedair.ai.tools.BookingAccess;
import com.unitedair.ai.tools.BookingManagementTool;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.tools.ToolInvocationLogger;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Authorized read-only refund operations exposed through the canonical
 * {@link BookingManagementTool} family.
 */
@Component
public class RefundOperationsWorker {

    public static final String LIST_OPERATION = "LIST_REFUND_CASES";
    public static final String STATUS_OPERATION = "GET_REFUND_STATUS";

    private final RefundWorkItemService refunds;
    private final ToolInvocationLogger logger;
    private final BookingProvider bookingProvider;

    @Autowired
    public RefundOperationsWorker(
            RefundWorkItemService refunds,
            ToolInvocationLogger logger,
            BookingProvider bookingProvider) {
        this.refunds = refunds;
        this.logger = logger;
        this.bookingProvider = bookingProvider;
    }

    RefundOperationsWorker(
            RefundWorkItemService refunds,
            ToolInvocationLogger logger) {
        this(refunds, logger, null);
    }

    public ToolDtos.ToolOutcome listCases(
            RefundDtos.RefundCaseQuery query,
            BookingAccess access) {
        requirePrivileged(access);
        RefundDtos.RefundCaseQuery safe = query == null
                ? new RefundDtos.RefundCaseQuery(null, null, 50)
                : query;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", LIST_OPERATION);
        request.put("status", safe.status() == null ? "" : safe.status());
        request.put("dueBefore", safe.dueBefore() == null ? "" : safe.dueBefore().toString());
        request.put("limit", safe.limit());

        return logger.invoke(
                BookingManagementTool.NAME,
                access.role().name(),
                "ORCHESTRATOR_WORKER",
                request,
                () -> {
                    List<RefundDtos.RefundStatusView> rows = refunds.listForStaff(safe)
                            .stream()
                            .map(RefundOperationsWorker::toStatus)
                            .toList();
                    String summary = rows.isEmpty()
                            ? "No refund cases match those filters."
                            : rows.size() + " refund case"
                                    + (rows.size() == 1 ? "" : "s")
                                    + " match those filters.";
                    return new ToolInvocationLogger.ToolResult(rows, summary);
                });
    }

    public ToolDtos.ToolOutcome status(String pnr, BookingAccess access) {
        requireAuthenticated(access);
        Map<String, Object> request = Map.of(
                "operation", STATUS_OPERATION,
                "pnrProvided", pnr != null && !pnr.isBlank());
        return logger.invoke(
                BookingManagementTool.NAME,
                access.role().name(),
                "ORCHESTRATOR_WORKER",
                request,
                () -> {
                    RefundDtos.RefundStatusView view;
                    try {
                        RefundDtos.RefundCaseView refundCase = access.privileged()
                                ? refunds.statusForStaff(pnr)
                                : refunds.statusForPassenger(access.userId(), pnr);
                        view = toStatus(refundCase);
                    } catch (ApiExceptions.NotFound noWorkItem) {
                        view = legacyBookingStatus(pnr, access, noWorkItem);
                    }
                    return new ToolInvocationLogger.ToolResult(
                            view,
                            "Refund " + view.status().toLowerCase().replace('_', ' ')
                                    + "; INR " + view.amount().stripTrailingZeros().toPlainString()
                                    + "; due " + view.dueAt() + ".");
                });
    }

    private RefundDtos.RefundStatusView legacyBookingStatus(
            String pnr,
            BookingAccess access,
            ApiExceptions.NotFound noWorkItem) {
        if (bookingProvider == null) {
            throw noWorkItem;
        }
        ToolDtos.BookingView booking = bookingProvider.retrieve(pnr, access)
                .orElseThrow(() -> noWorkItem);
        if (booking.status() == null
                || !booking.status().toUpperCase().startsWith("REFUND_")) {
            throw noWorkItem;
        }
        return new RefundDtos.RefundStatusView(
                "legacy-" + booking.pnr(),
                booking.pnr(),
                booking.status().toUpperCase(),
                booking.refundAmount() == null
                        ? java.math.BigDecimal.ZERO : booking.refundAmount(),
                null,
                booking.retrievedAt(),
                "The legacy refund is awaiting settlement processing.");
    }

    private static RefundDtos.RefundStatusView toStatus(
            RefundDtos.RefundCaseView value) {
        return new RefundDtos.RefundStatusView(
                value.caseUuid().toString(),
                value.pnr(),
                value.status().name(),
                value.refundAmount(),
                value.dueAt(),
                value.updatedAt(),
                nextAction(value.status()));
    }

    private static String nextAction(RefundDtos.RefundStatus status) {
        return switch (status) {
            case PENDING -> "Waiting for refund processing to begin.";
            case PROCESSING -> "Payment settlement is in progress.";
            case CONTACT_NEEDED -> "Airline staff must contact the passenger.";
            case COMPLETED -> "No further action is required.";
            case FAILED -> "Airline staff must review and retry the settlement.";
        };
    }

    private static void requirePrivileged(BookingAccess access) {
        requireAuthenticated(access);
        if (!access.privileged()) {
            throw new ApiExceptions.Forbidden(
                    "Passengers cannot list other passengers' refund cases.");
        }
    }

    private static void requireAuthenticated(BookingAccess access) {
        if (access == null || access.userId() == null) {
            throw new ApiExceptions.Unauthorized(
                    "Authenticated refund access is required.");
        }
    }
}
