package com.unitedair.ai.notifications;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FlightNotificationRepository {

    private final JdbcClient jdbc;

    public FlightNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FlightState> flightState(String flightNo, LocalDate date) {
        return jdbc.sql("""
                    SELECT i.id, f.flight_no, i.flight_date, i.gate, i.terminal
                      FROM sim_flight_instance i
                      JOIN sim_flight f ON f.id = i.flight_id
                     WHERE f.flight_no = :flightNo AND i.flight_date = :date
                """)
                .param("flightNo", flightNo)
                .param("date", java.sql.Date.valueOf(date))
                .query((rs, row) -> new FlightState(
                        rs.getLong("id"),
                        rs.getString("flight_no"),
                        rs.getDate("flight_date").toLocalDate(),
                        rs.getString("gate"),
                        rs.getString("terminal")))
                .optional();
    }

    public boolean passengerOwnsFlight(Long userId, String flightNo, LocalDate date) {
        Integer count = jdbc.sql("""
                    SELECT COUNT(*)
                      FROM sim_booking b
                      JOIN sim_flight_instance i ON i.id = b.flight_instance_id
                      JOIN sim_flight f ON f.id = i.flight_id
                     WHERE b.user_id = :userId
                       AND f.flight_no = :flightNo
                       AND i.flight_date = :date
                       AND b.status IN ('CONFIRMED', 'REFUND_PENDING')
                """)
                .param("userId", userId)
                .param("flightNo", flightNo)
                .param("date", java.sql.Date.valueOf(date))
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public NotificationDtos.SubscriptionView subscribe(
            Long userId,
            String role,
            FlightState state,
            String uuid) {
        jdbc.sql("""
                    INSERT INTO flight_notification_subscription
                        (subscription_uuid, user_id, actor_role, flight_no, flight_date,
                         last_gate, last_terminal, active)
                    VALUES (:uuid, :userId, :role, :flightNo, :date, :gate, :terminal, TRUE)
                    ON DUPLICATE KEY UPDATE
                        subscription_uuid = VALUES(subscription_uuid),
                        actor_role = VALUES(actor_role),
                        last_gate = VALUES(last_gate),
                        last_terminal = VALUES(last_terminal),
                        active = TRUE
                """)
                .param("uuid", uuid)
                .param("userId", userId)
                .param("role", role)
                .param("flightNo", state.flightNo())
                .param("date", java.sql.Date.valueOf(state.date()))
                .param("gate", state.gate(), Types.VARCHAR)
                .param("terminal", state.terminal(), Types.VARCHAR)
                .update();
        return findSubscription(userId, state.flightNo(), state.date()).orElseThrow();
    }

    private Optional<NotificationDtos.SubscriptionView> findSubscription(
            Long userId, String flightNo, LocalDate date) {
        return jdbc.sql("""
                    SELECT subscription_uuid, flight_no, flight_date, last_gate,
                           last_terminal, active, created_at
                      FROM flight_notification_subscription
                     WHERE user_id = :userId AND flight_no = :flightNo AND flight_date = :date
                """)
                .param("userId", userId)
                .param("flightNo", flightNo)
                .param("date", java.sql.Date.valueOf(date))
                .query((rs, row) -> new NotificationDtos.SubscriptionView(
                        rs.getString("subscription_uuid"),
                        rs.getString("flight_no"),
                        rs.getDate("flight_date").toLocalDate(),
                        rs.getString("last_gate"),
                        rs.getString("last_terminal"),
                        rs.getBoolean("active"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public List<SubscriptionSnapshot> activeSubscriptions() {
        return jdbc.sql("""
                    SELECT id, subscription_uuid, user_id, flight_no, flight_date,
                           last_gate, last_terminal
                      FROM flight_notification_subscription
                     WHERE active = TRUE AND flight_date >= CURRENT_DATE
                """)
                .query((rs, row) -> new SubscriptionSnapshot(
                        rs.getLong("id"),
                        rs.getString("subscription_uuid"),
                        rs.getLong("user_id"),
                        rs.getString("flight_no"),
                        rs.getDate("flight_date").toLocalDate(),
                        rs.getString("last_gate"),
                        rs.getString("last_terminal")))
                .list();
    }

    public void updateSnapshot(long subscriptionId, FlightState state) {
        jdbc.sql("""
                    UPDATE flight_notification_subscription
                       SET last_gate = :gate, last_terminal = :terminal
                     WHERE id = :id
                """)
                .param("gate", state.gate(), Types.VARCHAR)
                .param("terminal", state.terminal(), Types.VARCHAR)
                .param("id", subscriptionId)
                .update();
    }

    public void insertEvent(
            SubscriptionSnapshot subscription,
            FlightState state,
            String eventUuid,
            Instant detectedAt) {
        jdbc.sql("""
                    INSERT INTO flight_notification_event
                        (event_uuid, subscription_id, user_id, flight_no, flight_date,
                         previous_gate, gate, previous_terminal, terminal, detected_at)
                    VALUES (:uuid, :subscriptionId, :userId, :flightNo, :date,
                            :previousGate, :gate, :previousTerminal, :terminal, :detectedAt)
                """)
                .param("uuid", eventUuid)
                .param("subscriptionId", subscription.id())
                .param("userId", subscription.userId())
                .param("flightNo", state.flightNo())
                .param("date", java.sql.Date.valueOf(state.date()))
                .param("previousGate", subscription.lastGate(), Types.VARCHAR)
                .param("gate", state.gate(), Types.VARCHAR)
                .param("previousTerminal", subscription.lastTerminal(), Types.VARCHAR)
                .param("terminal", state.terminal(), Types.VARCHAR)
                .param("detectedAt", java.sql.Timestamp.from(detectedAt))
                .update();
    }

    public List<NotificationDtos.FlightChangeEvent> events(Long userId, Instant since) {
        return jdbc.sql("""
                    SELECT event_uuid, flight_no, flight_date, previous_gate, gate,
                           previous_terminal, terminal, detected_at
                      FROM flight_notification_event
                     WHERE user_id = :userId AND detected_at > :since
                     ORDER BY detected_at
                     LIMIT 100
                """)
                .param("userId", userId)
                .param("since", java.sql.Timestamp.from(since))
                .query((rs, row) -> new NotificationDtos.FlightChangeEvent(
                        rs.getString("event_uuid"),
                        rs.getString("flight_no"),
                        rs.getDate("flight_date").toLocalDate(),
                        rs.getString("previous_gate"),
                        rs.getString("gate"),
                        rs.getString("previous_terminal"),
                        rs.getString("terminal"),
                        rs.getTimestamp("detected_at").toInstant()))
                .list();
    }

    public boolean unsubscribe(Long userId, String uuid) {
        return jdbc.sql("""
                    UPDATE flight_notification_subscription
                       SET active = FALSE
                     WHERE user_id = :userId AND subscription_uuid = :uuid
                """)
                .param("userId", userId)
                .param("uuid", uuid)
                .update() == 1;
    }

    public record FlightState(
            long instanceId, String flightNo, LocalDate date, String gate, String terminal) { }

    public record SubscriptionSnapshot(
            long id,
            String subscriptionUuid,
            long userId,
            String flightNo,
            LocalDate date,
            String lastGate,
            String lastTerminal) { }
}

