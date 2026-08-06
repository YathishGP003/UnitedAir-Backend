package com.unitedair.ai.commerce;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CommerceDtos.PaymentView> findByIdempotency(
            long userId, String idempotencyKey) {
        return jdbc.sql("""
                    SELECT payment_uuid,status,method,masked_account,provider_reference,
                           amount_inr,created_at
                    FROM sim_payment
                    WHERE user_id=:userId AND idempotency_key=:idempotency
                """)
                .param("userId", userId)
                .param("idempotency", idempotencyKey)
                .query(PaymentRepository::map)
                .optional();
    }

    public CommerceDtos.PaymentView create(
            long userId,
            UUID draftUuid,
            String method,
            String status,
            String maskedAccount,
            String providerReference,
            BigDecimal amount,
            String idempotencyKey) {
        UUID paymentUuid = UUID.randomUUID();
        try {
            int changed = jdbc.sql("""
                        INSERT INTO sim_payment
                            (payment_uuid,draft_id,user_id,method,status,masked_account,
                             provider_reference,amount_inr,idempotency_key,authorized_at)
                        SELECT :paymentUuid,d.id,d.user_id,:method,:status,:masked,
                               :provider,:amount,:idempotency,
                               CASE WHEN :status='AUTHORIZED' THEN CURRENT_TIMESTAMP ELSE NULL END
                        FROM booking_draft d
                        WHERE d.draft_uuid=:draftUuid AND d.user_id=:userId
                          AND d.expires_at > CURRENT_TIMESTAMP
                    """)
                    .param("paymentUuid", paymentUuid.toString())
                    .param("method", method)
                    .param("status", status)
                    .param("masked", maskedAccount)
                    .param("provider", providerReference, Types.VARCHAR)
                    .param("amount", amount)
                    .param("idempotency", idempotencyKey)
                    .param("draftUuid", draftUuid.toString())
                    .param("userId", userId)
                    .update();
            if (changed != 1) {
                throw new ApiExceptions.NotFound(
                        "The booking draft was not found or has expired.");
            }
        } catch (DuplicateKeyException duplicate) {
            return findByIdempotency(userId, idempotencyKey)
                    .orElseThrow(() -> duplicate);
        }
        return findByIdempotency(userId, idempotencyKey).orElseThrow();
    }

    private static CommerceDtos.PaymentView map(ResultSet rs, int row) throws SQLException {
        CommerceDtos.PaymentStatus status =
                CommerceDtos.PaymentStatus.valueOf(rs.getString("status"));
        return new CommerceDtos.PaymentView(
                UUID.fromString(rs.getString("payment_uuid")),
                status,
                rs.getString("method"),
                rs.getString("masked_account"),
                rs.getString("provider_reference"),
                rs.getBigDecimal("amount_inr"),
                rs.getTimestamp("created_at") == null
                        ? Instant.EPOCH : rs.getTimestamp("created_at").toInstant(),
                status == CommerceDtos.PaymentStatus.AUTHORIZED
                        ? "Payment authorized."
                        : "Payment declined by the simulator.");
    }
}
