package com.unitedair.ai.conversation;

import java.time.LocalDate;
import java.util.Optional;

import com.unitedair.ai.identity.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Remembers which simulator booking a conversation is servicing without copying the PNR
 * into chat history, audit metadata, or a model prompt.
 *
 * <p>The database relationship stores only internal numeric foreign keys. The PNR is
 * resolved inside the current request when a follow-up such as "is it refundable?" needs
 * to call a verified booking tool.
 */
@Component
public class SessionBookingContext {

    public record BookingContext(
            String pnr,
            String flightNo,
            String origin,
            String destination,
            LocalDate travelDate) { }

    public record ContextView(
            String flightNo,
            String origin,
            String destination,
            LocalDate travelDate) { }

    public record ConversationReference(
            String pnr,
            String flightNo,
            LocalDate date,
            String origin,
            String destination,
            String refundCaseUuid) { }

    private final JdbcClient jdbc;

    public SessionBookingContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void remember(String sessionUuid, String pnr) {
        remember(sessionUuid, pnr, Role.AIRLINE_STAFF, null);
    }

    public boolean remember(String sessionUuid, String pnr, Role role, Long userId) {
        boolean privileged = role != null && role.atLeast(Role.AIRLINE_STAFF);
        if (!privileged && userId == null) {
            return false;
        }
        String ownership = privileged ? "" : " AND b.user_id = :userId";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                    INSERT INTO session_booking_context (session_id, booking_id)
                    SELECT s.id, b.id
                    FROM chat_session s
                    JOIN sim_booking b ON UPPER(b.pnr) = UPPER(:pnr)
                    WHERE s.session_uuid = :session
                """ + ownership + """
                    ON DUPLICATE KEY UPDATE
                        booking_id = VALUES(booking_id),
                        updated_at = CURRENT_TIMESTAMP
                """)
                .param("pnr", pnr)
                .param("session", sessionUuid);
        if (!privileged) {
            statement = statement.param("userId", userId);
        }
        return statement.update() > 0;
    }

    public Optional<ContextView> rememberUpcoming(String sessionUuid, Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        java.util.List<String> pnrs = jdbc.sql("""
                    SELECT b.pnr
                    FROM sim_booking b
                    JOIN sim_flight_instance i ON i.id = b.flight_instance_id
                    JOIN sim_flight f ON f.id = i.flight_id
                    WHERE b.user_id = :userId
                      AND b.status IN ('CONFIRMED', 'CHECKED_IN')
                      AND i.flight_date >= CURDATE()
                    ORDER BY i.flight_date, f.dep_time_local
                    LIMIT 2
                """)
                .param("userId", userId)
                .query(String.class)
                .list();
        if (pnrs.size() != 1
                || !remember(sessionUuid, pnrs.getFirst(), Role.PASSENGER, userId)) {
            return Optional.empty();
        }
        return view(sessionUuid);
    }

    public Optional<String> resolve(String sessionUuid) {
        return jdbc.sql("""
                    SELECT b.pnr
                    FROM session_booking_context c
                    JOIN chat_session s ON s.id = c.session_id
                    JOIN sim_booking b ON b.id = c.booking_id
                    WHERE s.session_uuid = :session
                      AND s.expired = FALSE
                """)
                .param("session", sessionUuid)
                .query(String.class)
                .optional();
    }

    public Optional<BookingContext> resolveDetails(String sessionUuid) {
        return jdbc.sql("""
                    SELECT b.pnr,
                           f.flight_no AS flightNo,
                           f.origin,
                           f.destination,
                           i.flight_date AS travelDate
                    FROM session_booking_context c
                    JOIN chat_session s ON s.id = c.session_id
                    JOIN sim_booking b ON b.id = c.booking_id
                    JOIN sim_flight_instance i ON i.id = b.flight_instance_id
                    JOIN sim_flight f ON f.id = i.flight_id
                    WHERE s.session_uuid = :session
                      AND s.expired = FALSE
                """)
                .param("session", sessionUuid)
                .query(BookingContext.class)
                .optional();
    }

    public Optional<ContextView> view(String sessionUuid) {
        return resolveDetails(sessionUuid)
                .map(context -> new ContextView(
                        context.flightNo(),
                        context.origin(),
                        context.destination(),
                        context.travelDate()));
    }

    public Optional<ConversationReference> resolveReference(String sessionUuid) {
        return jdbc.sql("""
                    SELECT b.pnr,
                           f.flight_no AS flightNo,
                           i.flight_date AS date,
                           f.origin,
                           f.destination,
                           COALESCE(
                               (SELECT r.case_uuid
                                  FROM refund_work_item r
                                 WHERE r.booking_id = b.id
                                 ORDER BY r.created_at DESC
                                 LIMIT 1),
                               CASE
                                   WHEN LEFT(UPPER(b.status), 7) = 'REFUND_'
                                   THEN CONCAT('legacy-', b.pnr)
                               END
                           ) AS refundCaseUuid
                    FROM session_booking_context c
                    JOIN chat_session s ON s.id = c.session_id
                    JOIN sim_booking b ON b.id = c.booking_id
                    JOIN sim_flight_instance i ON i.id = b.flight_instance_id
                    JOIN sim_flight f ON f.id = i.flight_id
                    WHERE s.session_uuid = :session
                      AND s.expired = FALSE
                """)
                .param("session", sessionUuid)
                .query(ConversationReference.class)
                .optional();
    }

    public void clear(String sessionUuid) {
        jdbc.sql("""
                    DELETE FROM session_booking_context
                    WHERE session_id = (
                        SELECT id FROM chat_session WHERE session_uuid = :session
                    )
                """)
                .param("session", sessionUuid)
                .update();
    }

    public void clearForUser(long userId) {
        jdbc.sql("""
                    DELETE c
                    FROM session_booking_context c
                    JOIN chat_session s ON s.id = c.session_id
                    WHERE s.user_id = :userId
                """)
                .param("userId", userId)
                .update();
    }
}
