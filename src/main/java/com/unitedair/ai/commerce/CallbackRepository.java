package com.unitedair.ai.commerce;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CallbackRepository {

    private final JdbcClient jdbc;

    public CallbackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CommerceDtos.CallbackView> pending(
            long userId, String pnr, String channel) {
        return jdbc.sql("""
                    SELECT c.case_uuid,b.pnr,c.requested_channel,c.status,c.created_at
                    FROM support_callback c
                    JOIN sim_booking b ON b.id=c.booking_id
                    WHERE c.user_id=:userId AND b.pnr=:pnr
                      AND c.requested_channel=:channel AND c.status='PENDING'
                    ORDER BY c.created_at DESC LIMIT 1
                """)
                .param("userId", userId)
                .param("pnr", pnr)
                .param("channel", channel)
                .query((rs, row) -> new CommerceDtos.CallbackView(
                        UUID.fromString(rs.getString("case_uuid")),
                        rs.getString("pnr"),
                        rs.getString("requested_channel"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public CommerceDtos.CallbackView createOwned(
            long userId, String pnr, String channel) {
        Optional<CommerceDtos.CallbackView> existing = pending(userId, pnr, channel);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID caseUuid = UUID.randomUUID();
        int changed = jdbc.sql("""
                    INSERT INTO support_callback
                        (case_uuid,user_id,booking_id,reason,requested_channel,status)
                    SELECT :caseUuid,:userId,b.id,'CANCELLATION_SUPPORT',:channel,'PENDING'
                    FROM sim_booking b
                    WHERE b.user_id=:userId AND b.pnr=:pnr
                """)
                .param("caseUuid", caseUuid.toString())
                .param("userId", userId)
                .param("pnr", pnr)
                .param("channel", channel)
                .update();
        if (changed != 1) {
            throw new ApiExceptions.NotFound("Booking not found.");
        }
        return pending(userId, pnr, channel).orElseThrow();
    }
}
