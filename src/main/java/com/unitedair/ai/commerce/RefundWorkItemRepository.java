package com.unitedair.ai.commerce;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefundWorkItemRepository {

    private static final String SELECT_CASE = """
            SELECT r.id,r.case_uuid,r.passenger_user_id,r.status,r.fare_brand,
                   r.cancellation_basis,r.amount_paid_inr,r.cancellation_fee_inr,
                   r.refund_amount_inr,r.payment_method,r.masked_payment,r.due_at,
                   r.staff_note,r.created_at,r.updated_at,r.completed_at,
                   b.pnr,b.passenger_name,f.flight_no,f.origin,f.destination,i.flight_date,
                   (SELECT c.status FROM support_callback c
                    WHERE c.booking_id=b.id
                    ORDER BY c.created_at DESC LIMIT 1) AS callback_status
            FROM refund_work_item r
            JOIN sim_booking b ON b.id=r.booking_id
            JOIN sim_flight_instance i ON i.id=b.flight_instance_id
            JOIN sim_flight f ON f.id=i.flight_id
            """;

    private final JdbcClient jdbc;

    public RefundWorkItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RefundDtos.RefundCaseView createForCancellation(
            long passengerUserId, String pnr, ToolDtos.RefundQuote quote) {
        Optional<RefundDtos.RefundCaseView> existing =
                findForPassenger(passengerUserId, pnr);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID caseUuid = UUID.randomUUID();
        int changed = jdbc.sql("""
                    INSERT INTO refund_work_item
                        (case_uuid,booking_id,passenger_user_id,payment_id,status,
                         fare_brand,cancellation_basis,amount_paid_inr,
                         cancellation_fee_inr,refund_amount_inr,payment_method,
                         masked_payment,due_at,timing_band,policy_document_code,
                         policy_section,quote_calculated_at)
                    SELECT :caseUuid,b.id,b.user_id,p.id,'PENDING',
                           :fareBrand,:basis,:amountPaid,:fee,:refund,
                           COALESCE(p.method,'NOT_AVAILABLE'),
                           COALESCE(p.masked_account,'Not available'),
                           DATE_ADD(CURRENT_TIMESTAMP,
                               INTERVAL CASE WHEN p.method='NETBANK' THEN 15 ELSE 7 END DAY),
                           :timingBand,:policyCode,:policySection,:calculatedAt
                    FROM sim_booking b
                    LEFT JOIN sim_payment p ON p.booking_id=b.id
                    WHERE b.user_id=:userId AND b.pnr=:pnr
                    ON DUPLICATE KEY UPDATE
                        case_uuid=refund_work_item.case_uuid
                """)
                .param("caseUuid", caseUuid.toString())
                .param("fareBrand", quote.fareBrand())
                .param("basis", quote.basis())
                .param("amountPaid", quote.amountPaid())
                .param("fee", quote.cancellationFee())
                .param("refund", quote.estimatedRefund())
                .param("timingBand", quote.timingBand())
                .param("policyCode", quote.policyDocumentCode())
                .param("policySection", quote.policySection())
                .param("calculatedAt", java.sql.Timestamp.from(quote.calculatedAt()))
                .param("userId", passengerUserId)
                .param("pnr", pnr)
                .update();
        if (changed == 0 && findForPassenger(passengerUserId, pnr).isEmpty()) {
            throw new ApiExceptions.NotFound("Booking not found.");
        }

        RefundDtos.RefundCaseView created = findForPassenger(passengerUserId, pnr)
                .orElseThrow(() -> new ApiExceptions.Conflict(
                        "The refund case could not be created."));
        jdbc.sql("""
                    INSERT INTO refund_work_item_history
                        (refund_work_item_id,from_status,to_status,note,changed_by)
                    SELECT r.id,NULL,'PENDING','Cancellation confirmed',NULL
                    FROM refund_work_item r
                    WHERE r.case_uuid=:caseUuid
                      AND NOT EXISTS (
                        SELECT 1 FROM refund_work_item_history h
                        WHERE h.refund_work_item_id=r.id)
                """)
                .param("caseUuid", created.caseUuid().toString())
                .update();
        return findForStaff(created.caseUuid()).orElseThrow();
    }

    public List<RefundDtos.RefundCaseView> listForPassenger(long passengerUserId) {
        return jdbc.sql(SELECT_CASE + """
                    WHERE r.passenger_user_id=:userId
                    ORDER BY r.created_at DESC
                """)
                .param("userId", passengerUserId)
                .query(this::mapCase)
                .list()
                .stream()
                .map(this::withHistory)
                .toList();
    }

    public List<RefundDtos.RefundCaseView> listForStaff(
            RefundDtos.RefundStatus status) {
        return listForStaff(status, null, 200);
    }

    public List<RefundDtos.RefundCaseView> listForStaff(
            RefundDtos.RefundStatus status,
            Instant dueBefore,
            int limit) {
        String sql = SELECT_CASE
                + " WHERE (:status IS NULL OR r.status=:status) "
                + " AND (:dueBefore IS NULL OR r.due_at<=:dueBefore) "
                + " ORDER BY CASE r.status "
                + "WHEN 'FAILED' THEN 0 WHEN 'CONTACT_NEEDED' THEN 1 "
                + "WHEN 'PENDING' THEN 2 WHEN 'PROCESSING' THEN 3 ELSE 4 END,"
                + " r.due_at,r.created_at LIMIT :limit";
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("status", status == null ? null : status.name())
                .param("dueBefore", dueBefore == null
                        ? null : java.sql.Timestamp.from(dueBefore))
                .param("limit", Math.max(1, Math.min(limit, 200)));
        return statement.query(this::mapCase).list().stream()
                .map(this::withHistory)
                .toList();
    }

    public Optional<RefundDtos.RefundCaseView> findForStaff(UUID caseUuid) {
        return jdbc.sql(SELECT_CASE + " WHERE r.case_uuid=:caseUuid")
                .param("caseUuid", caseUuid.toString())
                .query(this::mapCase)
                .optional()
                .map(this::withHistory);
    }

    public Optional<RefundDtos.RefundCaseView> findForPassenger(
            long passengerUserId, String pnr) {
        return jdbc.sql(SELECT_CASE + """
                    WHERE r.passenger_user_id=:userId AND b.pnr=:pnr
                """)
                .param("userId", passengerUserId)
                .param("pnr", pnr)
                .query(this::mapCase)
                .optional()
                .map(this::withHistory);
    }

    public Optional<RefundDtos.RefundCaseView> findForStaffByPnr(String pnr) {
        return jdbc.sql(SELECT_CASE + """
                    WHERE b.pnr=:pnr
                    ORDER BY r.created_at DESC
                    LIMIT 1
                """)
                .param("pnr", pnr)
                .query(this::mapCase)
                .optional()
                .map(this::withHistory);
    }

    public RefundDtos.RefundCaseView transition(
            UUID caseUuid,
            RefundDtos.RefundStatus from,
            RefundDtos.RefundStatus to,
            String note,
            long staffUserId) {
        int changed = jdbc.sql("""
                    UPDATE refund_work_item
                    SET status=:target,staff_note=:note,assigned_to=:staff,
                        completed_at=CASE WHEN :target='COMPLETED'
                            THEN CURRENT_TIMESTAMP ELSE completed_at END
                    WHERE case_uuid=:caseUuid AND status=:current
                """)
                .param("target", to.name())
                .param("note", note.isBlank() ? null : note)
                .param("staff", staffUserId)
                .param("caseUuid", caseUuid.toString())
                .param("current", from.name())
                .update();
        if (changed != 1) {
            throw new ApiExceptions.Conflict(
                    "The refund case changed before this update. Refresh and try again.");
        }
        jdbc.sql("""
                    INSERT INTO refund_work_item_history
                        (refund_work_item_id,from_status,to_status,note,changed_by)
                    SELECT id,:fromStatus,:toStatus,:note,:staff
                    FROM refund_work_item WHERE case_uuid=:caseUuid
                """)
                .param("fromStatus", from.name())
                .param("toStatus", to.name())
                .param("note", note.isBlank() ? null : note)
                .param("staff", staffUserId)
                .param("caseUuid", caseUuid.toString())
                .update();

        if (to == RefundDtos.RefundStatus.COMPLETED) {
            jdbc.sql("""
                        UPDATE sim_booking b
                        JOIN refund_work_item r ON r.booking_id=b.id
                        SET b.status='REFUNDED',b.refund_status='COMPLETED'
                        WHERE r.case_uuid=:caseUuid
                    """)
                    .param("caseUuid", caseUuid.toString())
                    .update();
            jdbc.sql("""
                        UPDATE sim_payment p
                        JOIN refund_work_item r ON r.payment_id=p.id
                        SET p.status='REFUNDED',p.refunded_at=CURRENT_TIMESTAMP
                        WHERE r.case_uuid=:caseUuid
                    """)
                    .param("caseUuid", caseUuid.toString())
                    .update();
        } else {
            jdbc.sql("""
                        UPDATE sim_booking b
                        JOIN refund_work_item r ON r.booking_id=b.id
                        SET b.refund_status=:status
                        WHERE r.case_uuid=:caseUuid
                    """)
                    .param("status", to.name())
                    .param("caseUuid", caseUuid.toString())
                    .update();
        }
        return findForStaff(caseUuid).orElseThrow();
    }

    private RefundDtos.RefundCaseView withHistory(RefundDtos.RefundCaseView view) {
        List<RefundDtos.RefundHistoryView> history = jdbc.sql("""
                    SELECT h.from_status,h.to_status,h.note,u.display_name,h.changed_at
                    FROM refund_work_item_history h
                    JOIN refund_work_item r ON r.id=h.refund_work_item_id
                    LEFT JOIN app_user u ON u.id=h.changed_by
                    WHERE r.case_uuid=:caseUuid
                    ORDER BY h.changed_at,h.id
                """)
                .param("caseUuid", view.caseUuid().toString())
                .query((rs, row) -> new RefundDtos.RefundHistoryView(
                        statusOrNull(rs.getString("from_status")),
                        RefundDtos.RefundStatus.parse(rs.getString("to_status")),
                        rs.getString("note"),
                        rs.getString("display_name"),
                        rs.getTimestamp("changed_at").toInstant()))
                .list();
        return new RefundDtos.RefundCaseView(
                view.caseUuid(), view.pnr(), view.passengerUserId(), view.passengerName(),
                view.flightNo(), view.origin(), view.destination(), view.flightDate(),
                view.fareBrand(), view.cancellationBasis(), view.amountPaid(),
                view.cancellationFee(), view.refundAmount(), view.paymentMethod(),
                view.maskedPayment(), view.dueAt(), view.status(), view.callbackStatus(),
                view.staffNote(), view.createdAt(), view.updatedAt(), view.completedAt(),
                history);
    }

    private RefundDtos.RefundCaseView mapCase(ResultSet rs, int row) throws SQLException {
        return new RefundDtos.RefundCaseView(
                UUID.fromString(rs.getString("case_uuid")),
                rs.getString("pnr"),
                rs.getLong("passenger_user_id"),
                rs.getString("passenger_name"),
                rs.getString("flight_no"),
                rs.getString("origin"),
                rs.getString("destination"),
                rs.getDate("flight_date").toLocalDate(),
                rs.getString("fare_brand"),
                rs.getString("cancellation_basis"),
                rs.getBigDecimal("amount_paid_inr"),
                rs.getBigDecimal("cancellation_fee_inr"),
                rs.getBigDecimal("refund_amount_inr"),
                rs.getString("payment_method"),
                rs.getString("masked_payment"),
                rs.getTimestamp("due_at").toInstant(),
                RefundDtos.RefundStatus.parse(rs.getString("status")),
                rs.getString("callback_status"),
                rs.getString("staff_note"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instantOrNull(rs, "completed_at"),
                List.of());
    }

    private static RefundDtos.RefundStatus statusOrNull(String value) {
        return value == null ? null : RefundDtos.RefundStatus.parse(value);
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
