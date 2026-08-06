package com.unitedair.ai.commerce;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.shared.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BookingDraftRepository {

    private final JdbcClient jdbc;

    public BookingDraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CommerceDtos.BookingDraftView> findOwned(UUID uuid, long userId) {
        return jdbc.sql("""
                    SELECT draft_uuid, state, origin, destination, travel_date, cabin,
                           flight_instance_id, fare_id, seat_number, traveller_json,
                           contact_json, version, expires_at
                    FROM booking_draft
                    WHERE draft_uuid=:uuid AND user_id=:userId
                """)
                .param("uuid", uuid.toString())
                .param("userId", userId)
                .query(BookingDraftRepository::map)
                .optional();
    }

    public Optional<CommerceDtos.BookingDraftView> findActiveBySession(
            long userId, String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                    SELECT draft_uuid, state, origin, destination, travel_date, cabin,
                           flight_instance_id, fare_id, seat_number, traveller_json,
                           contact_json, version, expires_at
                    FROM booking_draft
                    WHERE user_id=:userId AND session_uuid=:session
                      AND state IN ('COLLECTING','FLIGHTS_SHOWN','CHECKOUT','PAYMENT_PENDING')
                      AND expires_at > CURRENT_TIMESTAMP
                    ORDER BY updated_at DESC LIMIT 1
                """)
                .param("userId", userId)
                .param("session", sessionUuid)
                .query(BookingDraftRepository::map)
                .optional();
    }

    public CommerceDtos.BookingDraftView create(long userId, String sessionUuid) {
        UUID uuid = UUID.randomUUID();
        UUID idempotency = UUID.randomUUID();
        jdbc.sql("""
                    INSERT INTO booking_draft
                        (draft_uuid,user_id,session_uuid,state,idempotency_key,expires_at)
                    VALUES (:uuid,:userId,:session,'COLLECTING',:idempotency,
                            DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 MINUTE))
                """)
                .param("uuid", uuid.toString())
                .param("userId", userId)
                .param("session", sessionUuid)
                .param("idempotency", idempotency.toString())
                .update();
        return findOwned(uuid, userId).orElseThrow();
    }

    public CommerceDtos.BookingDraftView update(
            UUID uuid,
            long userId,
            int expectedVersion,
            CommerceDtos.BookingDraftView desired) {
        int changed = jdbc.sql("""
                    UPDATE booking_draft
                    SET state=:state, origin=:origin, destination=:destination,
                        travel_date=:date, cabin=:cabin,
                        flight_instance_id=:instanceId, fare_id=:fareId,
                        seat_number=:seat, traveller_json=:traveller,
                        contact_json=:contact, version=version+1
                    WHERE draft_uuid=:uuid AND user_id=:userId AND version=:version
                """)
                .param("state", desired.state().name())
                .param("origin", desired.origin())
                .param("destination", desired.destination())
                .param("date", desired.travelDate())
                .param("cabin", desired.cabin())
                .param("instanceId", desired.flightInstanceId())
                .param("fareId", desired.fareId())
                .param("seat", desired.seatNumber())
                .param("traveller", Json.write(desired.traveller()))
                .param("contact", Json.write(desired.contact()))
                .param("uuid", uuid.toString())
                .param("userId", userId)
                .param("version", expectedVersion)
                .update();
        if (changed != 1) {
            return null;
        }
        return findOwned(uuid, userId).orElseThrow();
    }

    public boolean abandon(UUID uuid, long userId) {
        return jdbc.sql("""
                    UPDATE booking_draft
                    SET state='ABANDONED',version=version+1
                    WHERE draft_uuid=:uuid AND user_id=:userId
                      AND state NOT IN ('CONFIRMED','ABANDONED','EXPIRED')
                """)
                .param("uuid", uuid.toString())
                .param("userId", userId)
                .update() == 1;
    }

    private static CommerceDtos.BookingDraftView map(ResultSet rs, int row) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new CommerceDtos.BookingDraftView(
                UUID.fromString(rs.getString("draft_uuid")),
                CommerceDtos.DraftState.valueOf(rs.getString("state")),
                rs.getString("origin"),
                rs.getString("destination"),
                rs.getDate("travel_date") == null ? null : rs.getDate("travel_date").toLocalDate(),
                rs.getString("cabin"),
                nullableLong(rs, "flight_instance_id"),
                nullableLong(rs, "fare_id"),
                rs.getString("seat_number"),
                Json.read(rs.getString("traveller_json"), CommerceDtos.Traveller.class),
                Json.read(rs.getString("contact_json"), CommerceDtos.Contact.class),
                rs.getInt("version"),
                expires == null ? Instant.EPOCH : expires.toInstant());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
