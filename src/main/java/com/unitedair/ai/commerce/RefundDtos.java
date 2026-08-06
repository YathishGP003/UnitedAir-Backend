package com.unitedair.ai.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RefundDtos {

    private RefundDtos() { }

    public enum RefundStatus {
        PENDING,
        PROCESSING,
        CONTACT_NEEDED,
        COMPLETED,
        FAILED;

        public static RefundStatus parse(String value) {
            return RefundStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record RefundHistoryView(
            RefundStatus fromStatus,
            RefundStatus toStatus,
            String note,
            String changedBy,
            Instant changedAt) { }

    public record RefundCaseView(
            UUID caseUuid,
            String pnr,
            long passengerUserId,
            String passengerName,
            String flightNo,
            String origin,
            String destination,
            LocalDate flightDate,
            String fareBrand,
            String cancellationBasis,
            BigDecimal amountPaid,
            BigDecimal cancellationFee,
            BigDecimal refundAmount,
            String paymentMethod,
            String maskedPayment,
            Instant dueAt,
            RefundStatus status,
            String callbackStatus,
            String staffNote,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            List<RefundHistoryView> history) { }

    public record RefundCaseQuery(
            String status,
            Instant dueBefore,
            int limit) {

        public RefundCaseQuery {
            limit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        }
    }

    public record RefundStatusView(
            String caseUuid,
            String bookingReferenceDisplay,
            String status,
            BigDecimal amount,
            Instant dueAt,
            Instant updatedAt,
            String nextAction) { }

    public record TransitionRequest(
            String status,
            String note) { }
}
