package com.unitedair.ai.actions;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.unitedair.ai.shared.ApiExceptions;

/** Types for the two-phase action flow. */
public final class ActionDtos {

    private ActionDtos() { }

    public enum ActionType {
        CANCEL_BOOKING,
        RESCHEDULE_BOOKING,
        SEAT_CHANGE,
        CHECK_IN,
        REFUND_REQUEST;

        public static ActionType parse(String value) {
            if (value == null) {
                throw new ApiExceptions.BadRequest("An action type is required.");
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new ApiExceptions.BadRequest("Unknown action type '" + value + "'.");
            }
        }
    }

    public record ProposeRequest(
            String type,
            String pnr,
            String sessionId,
            Long targetFlightInstanceId,
            String targetFareClass,
            String seatNumber) { }

    /** Internal row projection. */
    record ActionRow(
            String actionUuid,
            String sessionUuid,
            Long userId,
            String type,
            String status,
            String subjectPnr,
            String summaryJson,
            String citationsJson,
            String resultJson,
            String failureReason,
            Instant createdAt,
            Instant expiresAt,
            Instant settledAt) { }

    /**
     * What the UI renders in a confirmation card: exactly what will happen, the fees, and
     * the KB sections that justify them.
     */
    public record ActionView(
            String actionUuid,
            String type,
            String status,
            String pnr,
            Map<String, Object> summary,
            List<Map<String, String>> citations,
            Map<String, Object> result,
            Instant createdAt,
            Instant expiresAt,
            String failureReason) { }
}
