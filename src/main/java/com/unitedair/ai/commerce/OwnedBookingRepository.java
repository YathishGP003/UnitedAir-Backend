package com.unitedair.ai.commerce;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OwnedBookingRepository {

    private final JdbcClient jdbc;

    public OwnedBookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CommerceDtos.TicketView> findConfirmedByDraft(
            long userId, UUID draftUuid) {
        return jdbc.sql("""
                    SELECT t.snapshot_json
                    FROM sim_payment p
                    JOIN booking_draft d ON d.id=p.draft_id
                    JOIN sim_ticket t ON t.booking_id=p.booking_id
                    WHERE d.draft_uuid=:draft AND d.user_id=:userId
                    LIMIT 1
                """)
                .param("draft", draftUuid.toString())
                .param("userId", userId)
                .query((rs, row) -> Json.read(
                        rs.getString("snapshot_json"), CommerceDtos.TicketView.class))
                .optional();
    }

    public ConfirmationContext lockConfirmation(
            long userId, UUID draftUuid, UUID paymentUuid) {
        return jdbc.sql("""
                    SELECT d.draft_uuid,d.user_id,d.flight_instance_id,d.fare_id,d.seat_number,
                           d.traveller_json,d.contact_json,d.state,
                           p.payment_uuid,p.status AS payment_status,p.amount_inr,p.method,
                           p.masked_account,p.provider_reference,
                           sf.price_inr,sf.base_fare_inr,sf.taxes_inr,sf.cabin,sf.fare_class,
                           sf.fare_brand,sf.checked_baggage_kg,sf.cabin_baggage_kg,
                           COALESCE(ss.fee_inr,0) AS seat_fee_inr,
                           f.flight_no,f.origin,f.destination,i.flight_date,
                           f.dep_time_local,f.arr_time_local,i.terminal,i.gate
                    FROM booking_draft d
                    JOIN sim_payment p ON p.draft_id=d.id
                    JOIN sim_fare sf ON sf.id=d.fare_id
                                      AND sf.flight_instance_id=d.flight_instance_id
                    JOIN sim_flight_instance i ON i.id=d.flight_instance_id
                    JOIN sim_flight f ON f.id=i.flight_id
                    LEFT JOIN sim_seat ss ON ss.flight_instance_id=d.flight_instance_id
                                          AND ss.seat_number=d.seat_number
                    WHERE d.draft_uuid=:draft AND d.user_id=:userId
                      AND p.payment_uuid=:payment AND p.user_id=:userId
                      AND d.expires_at > CURRENT_TIMESTAMP
                    FOR UPDATE
                """)
                .param("draft", draftUuid.toString())
                .param("payment", paymentUuid.toString())
                .param("userId", userId)
                .query(OwnedBookingRepository::mapConfirmation)
                .optional()
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "Booking draft or payment authorization not found."));
    }

    public boolean decrementFare(long fareId) {
        return jdbc.sql("""
                    UPDATE sim_fare
                    SET seats_available=seats_available-1
                    WHERE id=:fare AND seats_available > 0
                """)
                .param("fare", fareId)
                .update() == 1;
    }

    public long insertBooking(ConfirmationContext context, String pnr) {
        String fullName = context.traveller().fullName().trim();
        String[] names = fullName.split("\\s+");
        String surname = names[names.length - 1];
        jdbc.sql("""
                    INSERT INTO sim_booking
                        (pnr,user_id,passenger_name,surname,contact_email,contact_phone,
                         flight_instance_id,fare_id,status,seat_number,amount_paid_inr)
                    VALUES (:pnr,:userId,:name,:surname,:email,:phone,:instance,:fare,
                            'CONFIRMED',:seat,:amount)
                """)
                .param("pnr", pnr)
                .param("userId", context.userId())
                .param("name", fullName)
                .param("surname", surname)
                .param("email", context.contact().email())
                .param("phone", context.contact().phone())
                .param("instance", context.flightInstanceId())
                .param("fare", context.fareId())
                .param("seat", context.seatNumber())
                .param("amount", context.paymentAmount())
                .update();
        return jdbc.sql("SELECT id FROM sim_booking WHERE pnr=:pnr")
                .param("pnr", pnr).query(Long.class).single();
    }

    public void captureAndComplete(
            long bookingId,
            long userId,
            UUID draftUuid,
            UUID paymentUuid,
            String ticketNumber,
            String snapshotJson) {
        int ticket = jdbc.sql("""
                    INSERT INTO sim_ticket
                        (ticket_number,booking_id,user_id,status,snapshot_json)
                    VALUES (:ticket,:booking,:userId,'ACTIVE',:snapshot)
                """)
                .param("ticket", ticketNumber)
                .param("booking", bookingId)
                .param("userId", userId)
                .param("snapshot", snapshotJson)
                .update();
        int payment = jdbc.sql("""
                    UPDATE sim_payment
                    SET booking_id=:booking,status='CAPTURED',captured_at=CURRENT_TIMESTAMP
                    WHERE payment_uuid=:payment AND user_id=:userId AND status='AUTHORIZED'
                """)
                .param("booking", bookingId)
                .param("payment", paymentUuid.toString())
                .param("userId", userId)
                .update();
        int draft = jdbc.sql("""
                    UPDATE booking_draft SET state='CONFIRMED',version=version+1
                    WHERE draft_uuid=:draft AND user_id=:userId
                      AND state IN ('CHECKOUT','PAYMENT_PENDING')
                """)
                .param("draft", draftUuid.toString())
                .param("userId", userId)
                .update();
        if (ticket != 1 || payment != 1 || draft != 1) {
            throw new ApiExceptions.Conflict(
                    "The booking changed before confirmation. No charge was captured.");
        }
    }

    public List<CommerceDtos.OwnedBookingView> list(long userId) {
        return jdbc.sql("""
                    SELECT b.pnr,t.ticket_number,b.passenger_name,b.status,f.flight_no,
                           f.origin,f.destination,i.flight_date,b.seat_number,sf.cabin,
                           sf.fare_brand,b.amount_paid_inr,b.booked_at
                    FROM sim_booking b
                    JOIN sim_flight_instance i ON i.id=b.flight_instance_id
                    JOIN sim_flight f ON f.id=i.flight_id
                    JOIN sim_fare sf ON sf.id=b.fare_id
                    LEFT JOIN sim_ticket t ON t.booking_id=b.id
                    WHERE b.user_id=:userId
                    ORDER BY i.flight_date DESC,b.booked_at DESC
                """)
                .param("userId", userId)
                .query((rs, row) -> new CommerceDtos.OwnedBookingView(
                        rs.getString("pnr"), rs.getString("ticket_number"),
                        rs.getString("passenger_name"), rs.getString("status"),
                        rs.getString("flight_no"), rs.getString("origin"),
                        rs.getString("destination"), rs.getDate("flight_date").toLocalDate(),
                        rs.getString("seat_number"), rs.getString("cabin"),
                        rs.getString("fare_brand"), rs.getBigDecimal("amount_paid_inr"),
                        rs.getTimestamp("booked_at").toInstant()))
                .list();
    }

    public Optional<CommerceDtos.TicketView> detail(long userId, String pnr) {
        return jdbc.sql("""
                    SELECT t.snapshot_json,t.ticket_number,t.issued_at,
                           b.pnr,b.passenger_name,b.contact_email,b.contact_phone,
                           b.status,b.seat_number,b.amount_paid_inr,b.booked_at,
                           b.cancelled_at,b.refund_amount_inr,
                           f.flight_no,f.origin,f.destination,f.dep_time_local,f.arr_time_local,
                           i.flight_date,i.terminal,i.gate,
                           sf.cabin,sf.fare_class,sf.fare_brand,sf.base_fare_inr,
                           sf.taxes_inr,sf.checked_baggage_kg,sf.cabin_baggage_kg,
                           p.method,p.masked_account,p.provider_reference
                    FROM sim_booking b
                    JOIN sim_flight_instance i ON i.id=b.flight_instance_id
                    JOIN sim_flight f ON f.id=i.flight_id
                    JOIN sim_fare sf ON sf.id=b.fare_id
                    LEFT JOIN sim_ticket t ON t.booking_id=b.id
                    LEFT JOIN sim_payment p ON p.booking_id=b.id
                    WHERE b.user_id=:userId AND b.pnr=:pnr
                """)
                .param("userId", userId)
                .param("pnr", pnr)
                .query((rs, row) -> currentTicket(rs))
                .optional();
    }

    public Optional<CommerceDtos.TicketView> detailById(long userId, long bookingId) {
        return jdbc.sql("""
                    SELECT t.snapshot_json,b.status,b.cancelled_at,b.refund_amount_inr
                    FROM sim_booking b
                    JOIN sim_ticket t ON t.booking_id=b.id
                    WHERE b.user_id=:userId AND b.id=:booking
                """)
                .param("userId", userId)
                .param("booking", bookingId)
                .query((rs, row) -> currentTicket(rs))
                .optional();
    }

    public Optional<CommerceDtos.CancellationView> cancellation(
            long userId, String pnr) {
        return jdbc.sql("""
                    SELECT b.pnr,b.status,b.amount_paid_inr,b.refund_amount_inr,b.cancelled_at,
                           f.flight_no,f.origin,f.destination,i.flight_date
                    FROM sim_booking b
                    JOIN sim_flight_instance i ON i.id=b.flight_instance_id
                    JOIN sim_flight f ON f.id=i.flight_id
                    WHERE b.user_id=:userId AND b.pnr=:pnr
                      AND b.status IN ('CANCELLED','REFUND_PENDING','REFUNDED')
                """)
                .param("userId", userId)
                .param("pnr", pnr)
                .query((rs, row) -> {
                    BigDecimal paid = rs.getBigDecimal("amount_paid_inr");
                    BigDecimal refund = rs.getBigDecimal("refund_amount_inr");
                    if (refund == null) {
                        refund = BigDecimal.ZERO;
                    }
                    return new CommerceDtos.CancellationView(
                            rs.getString("pnr"), rs.getString("flight_no"),
                            rs.getString("origin"), rs.getString("destination"),
                            rs.getDate("flight_date").toLocalDate(),
                            rs.getString("status"), paid, paid.subtract(refund),
                            refund, "5-7 business days",
                            rs.getTimestamp("cancelled_at").toInstant());
                })
                .optional();
    }

    private static CommerceDtos.TicketView currentTicket(ResultSet rs) throws SQLException {
        CommerceDtos.TicketView saved = Json.read(
                rs.getString("snapshot_json"), CommerceDtos.TicketView.class);
        if (saved == null) {
            return new CommerceDtos.TicketView(
                    rs.getString("pnr"),
                    rs.getString("ticket_number"),
                    rs.getString("status"),
                    rs.getString("passenger_name"),
                    null,
                    null,
                    rs.getString("contact_email"),
                    rs.getString("contact_phone"),
                    rs.getString("flight_no"),
                    rs.getString("origin"),
                    rs.getString("destination"),
                    rs.getDate("flight_date").toLocalDate(),
                    rs.getTime("dep_time_local").toLocalTime(),
                    rs.getTime("arr_time_local").toLocalTime(),
                    rs.getString("terminal"),
                    rs.getString("gate"),
                    rs.getString("cabin"),
                    rs.getString("fare_class"),
                    rs.getString("fare_brand"),
                    rs.getString("seat_number"),
                    rs.getInt("checked_baggage_kg"),
                    rs.getInt("cabin_baggage_kg"),
                    rs.getBigDecimal("base_fare_inr"),
                    rs.getBigDecimal("taxes_inr"),
                    BigDecimal.ZERO,
                    rs.getBigDecimal("amount_paid_inr"),
                    rs.getString("method") == null ? "DEMO" : rs.getString("method"),
                    rs.getString("masked_account") == null
                            ? "Not applicable" : rs.getString("masked_account"),
                    rs.getString("provider_reference") == null
                            ? "Seeded booking" : rs.getString("provider_reference"),
                    (rs.getTimestamp("issued_at") == null
                            ? rs.getTimestamp("booked_at") : rs.getTimestamp("issued_at"))
                            .toInstant(),
                    rs.getTimestamp("cancelled_at") == null
                            ? null : rs.getTimestamp("cancelled_at").toInstant(),
                    rs.getBigDecimal("refund_amount_inr"));
        }
        return new CommerceDtos.TicketView(
                saved.pnr(), saved.ticketNumber(), rs.getString("status"),
                saved.travellerName(), saved.dateOfBirth(), saved.nationality(),
                saved.contactEmail(), saved.contactPhone(), saved.flightNo(),
                saved.origin(), saved.destination(), saved.flightDate(),
                saved.departureTime(), saved.arrivalTime(), saved.terminal(), saved.gate(),
                saved.cabin(), saved.fareClass(), saved.fareBrand(), saved.seatNumber(),
                saved.checkedBaggageKg(), saved.cabinBaggageKg(), saved.baseFare(),
                saved.taxes(), saved.seatFee(), saved.totalPaid(), saved.paymentMethod(),
                saved.maskedPayment(), saved.paymentReference(), saved.issuedAt(),
                rs.getTimestamp("cancelled_at") == null
                        ? null : rs.getTimestamp("cancelled_at").toInstant(),
                rs.getBigDecimal("refund_amount_inr"));
    }

    private static ConfirmationContext mapConfirmation(ResultSet rs, int row)
            throws SQLException {
        return new ConfirmationContext(
                UUID.fromString(rs.getString("draft_uuid")),
                rs.getLong("user_id"),
                rs.getLong("flight_instance_id"),
                rs.getLong("fare_id"),
                UUID.fromString(rs.getString("payment_uuid")),
                rs.getString("payment_status"),
                rs.getBigDecimal("amount_inr"),
                rs.getBigDecimal("price_inr"),
                rs.getString("origin"), rs.getString("destination"),
                rs.getString("flight_no"), rs.getDate("flight_date").toLocalDate(),
                rs.getTime("dep_time_local").toLocalTime(),
                rs.getTime("arr_time_local").toLocalTime(),
                rs.getString("terminal"), rs.getString("gate"),
                rs.getString("cabin"), rs.getString("fare_class"),
                rs.getString("fare_brand"), rs.getBigDecimal("base_fare_inr"),
                rs.getBigDecimal("taxes_inr"), rs.getBigDecimal("seat_fee_inr"),
                rs.getInt("checked_baggage_kg"),
                rs.getInt("cabin_baggage_kg"), rs.getString("seat_number"),
                Json.read(rs.getString("traveller_json"), CommerceDtos.Traveller.class),
                Json.read(rs.getString("contact_json"), CommerceDtos.Contact.class),
                rs.getString("method"), rs.getString("masked_account"),
                rs.getString("provider_reference"));
    }

    public record ConfirmationContext(
            UUID draftUuid,
            long userId,
            long flightInstanceId,
            long fareId,
            UUID paymentUuid,
            String paymentStatus,
            BigDecimal paymentAmount,
            BigDecimal currentFare,
            String origin,
            String destination,
            String flightNo,
            LocalDate flightDate,
            LocalTime departureTime,
            LocalTime arrivalTime,
            String terminal,
            String gate,
            String cabin,
            String fareClass,
            String fareBrand,
            BigDecimal baseFare,
            BigDecimal taxes,
            BigDecimal seatFee,
            int checkedBaggageKg,
            int cabinBaggageKg,
            String seatNumber,
            CommerceDtos.Traveller traveller,
            CommerceDtos.Contact contact,
            String paymentMethod,
            String maskedPayment,
            String paymentReference) { }
}
