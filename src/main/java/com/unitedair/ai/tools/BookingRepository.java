package com.unitedair.ai.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservation-system access for BookingManagementTool and CheckInStatusTool.
 *
 * <p>Times are handled in the airline's operating zone rather than the server's. A check-in
 * window that opens "48 hours before departure" has to be computed against the departure's
 * local time, and a server running in a different zone would otherwise open the window at
 * the wrong moment.
 */
@Repository
public class BookingRepository {

    private static final ZoneId OPERATING_ZONE = ZoneId.of("Asia/Kolkata");

    /** Domestic web check-in opens 48h before and closes 60 minutes before departure. */
    private static final long CHECKIN_OPENS_HOURS = 48;
    private static final long CHECKIN_CLOSES_MINUTES = 60;

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ToolDtos.BookingView> findByPnr(String pnr) {
        return findByPnr(pnr, new BookingAccess(com.unitedair.ai.identity.Role.AIRLINE_STAFF, null));
    }

    public Optional<ToolDtos.BookingView> findByPnr(String pnr, BookingAccess access) {
        if (pnr == null || pnr.isBlank()) {
            return Optional.empty();
        }
        if (access == null || (!access.privileged() && access.userId() == null)) {
            return Optional.empty();
        }
        String ownership = access.privileged() ? "" : " AND b.user_id = :userId";
        JdbcClient.StatementSpec query = jdbc.sql("""
                    SELECT b.pnr, b.passenger_name, b.status, b.seat_number, b.amount_paid_inr,
                           b.refund_amount_inr, b.ffp_tier, b.booked_at,
                           f.flight_no, f.origin, f.destination, f.dep_time_local, f.arr_time_local,
                           i.flight_date, i.status AS flight_status, i.delay_minutes, i.terminal, i.gate,
                           sf.cabin, sf.fare_class, sf.fare_brand, sf.refundable, sf.changeable,
                           sf.change_fee_inr, sf.cancel_fee_inr, sf.checked_baggage_kg,
                           c.id AS checkin_id, c.boarding_gate, c.seat AS checkin_seat
                    FROM sim_booking b
                    JOIN sim_flight_instance i ON i.id = b.flight_instance_id
                    JOIN sim_flight f ON f.id = i.flight_id
                    JOIN sim_fare sf ON sf.id = b.fare_id
                    LEFT JOIN sim_checkin c ON c.booking_id = b.id
                    WHERE b.pnr = :pnr
                """ + ownership)
                .param("pnr", pnr.trim().toUpperCase(Locale.ROOT));
        if (!access.privileged()) {
            query = query.param("userId", access.userId());
        }
        return query
                .query(BookingRepository::mapBooking)
                .optional();
    }

    /**
     * Refund arithmetic for one booking, following the KB_04 section 2.1 matrix.
     *
     * <p>The bands and their fees live in the KB and the assistant must cite them; this
     * applies them to the booking in hand so the passenger gets a number rather than a
     * table. Where the two could disagree the KB wins, which is why {@code basis} names the
     * band that was applied.
     */
    public ToolDtos.RefundQuote quoteRefund(ToolDtos.BookingView booking) {
        long hours = hoursToDeparture(booking.flightDate(), booking.departureTime());
        String brand = booking.fareBrand() == null ? "" : booking.fareBrand();
        BigDecimal paid = booking.amountPaid() == null ? BigDecimal.ZERO : booking.amountPaid();

        String band;
        BigDecimal fee;

        if (!booking.refundable()) {
            // Saver and Super Saver forfeit the base fare; statutory taxes still return.
            band = "Non-refundable fare (Saver / Super Saver)";
            fee = booking.cancelFee() == null ? paid : booking.cancelFee();
        } else if (brand.toLowerCase(Locale.ROOT).contains("full flex")) {
            band = hours >= 2 ? "Full Flex, more than 2 hours before departure" : "Within 2 hours of departure";
            fee = hours >= 2 ? BigDecimal.ZERO : paid;
        } else if (brand.toLowerCase(Locale.ROOT).contains("flex")) {
            band = hours >= 3 ? "Flex, more than 3 hours before departure" : "Within 3 hours of departure";
            fee = hours >= 3 ? BigDecimal.valueOf(1000) : paid;
        } else if (hours >= 24 * 7) {
            band = "Value, more than 7 days before departure";
            fee = BigDecimal.valueOf(2000);
        } else if (hours >= 24 * 3) {
            band = "Value, 3 to 7 days before departure";
            fee = BigDecimal.valueOf(3000);
        } else {
            band = "Value, within 3 days of departure";
            fee = BigDecimal.valueOf(4000);
        }

        BigDecimal refund = paid.subtract(fee).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        return new ToolDtos.RefundQuote(
                booking.pnr(),
                brand,
                booking.refundable(),
                hours,
                band,
                paid,
                fee.setScale(2, RoundingMode.HALF_UP),
                refund,
                "Refunds are processed to the original payment method within 5 to 7 working days "
                        + "for cards and up to 15 working days for net banking.",
                "Computed from the booking's fare brand and time to departure. "
                        + "The governing fee matrix is KB_04 section 2.1.",
                Instant.now());
    }

    public ToolDtos.CheckInEligibility checkInEligibility(ToolDtos.BookingView booking) {
        long hours = hoursToDeparture(booking.flightDate(), booking.departureTime());
        Instant departure = departureInstant(booking.flightDate(), booking.departureTime());
        Instant opens = departure.minus(Duration.ofHours(CHECKIN_OPENS_HOURS));
        Instant closes = departure.minus(Duration.ofMinutes(CHECKIN_CLOSES_MINUTES));

        boolean eligible = true;
        String reason = "Check-in is open.";

        if (booking.checkedIn()) {
            eligible = false;
            reason = "This booking is already checked in.";
        } else if (!"CONFIRMED".equals(booking.status())) {
            eligible = false;
            reason = "Booking status is " + booking.status() + "; only confirmed bookings can check in.";
        } else if ("CANCELLED".equals(booking.flightStatus())) {
            eligible = false;
            reason = "The flight is cancelled. Please speak to a UnitedAir agent about rebooking.";
        } else if (Instant.now().isBefore(opens)) {
            eligible = false;
            reason = "Check-in opens " + CHECKIN_OPENS_HOURS + " hours before departure.";
        } else if (Instant.now().isAfter(closes)) {
            eligible = false;
            reason = "Online check-in closed " + CHECKIN_CLOSES_MINUTES
                    + " minutes before departure. Please use an airport counter.";
        }

        return new ToolDtos.CheckInEligibility(
                booking.pnr(), eligible, reason, hours, opens, closes,
                booking.checkedIn(), booking.seatNumber(), booking.boardingGate(),
                List.of("WEB", "MOBILE", "KIOSK", "COUNTER"));
    }

    /** Performs check-in. Only ever called after an explicit user confirmation. */
    public ToolDtos.CheckInResult performCheckIn(ToolDtos.BookingView booking, String channel) {
        Long bookingId = jdbc.sql("SELECT id FROM sim_booking WHERE pnr = :pnr")
                .param("pnr", booking.pnr()).query(Long.class).single();

        String seat = booking.seatNumber() != null ? booking.seatNumber() : allocateSeat(booking.pnr());
        String gate = booking.gate() != null ? booking.gate() : "A12";
        int sequence = nextSequenceNumber(booking.pnr());

        jdbc.sql("""
                    INSERT INTO sim_checkin (booking_id, seat, boarding_gate, channel, sequence_no)
                    VALUES (:bookingId, :seat, :gate, :channel, :seq)
                    ON DUPLICATE KEY UPDATE seat = VALUES(seat), boarding_gate = VALUES(boarding_gate)
                """)
                .param("bookingId", bookingId)
                .param("seat", seat)
                .param("gate", gate)
                .param("channel", channel == null ? "WEB" : channel)
                .param("seq", sequence)
                .update();

        jdbc.sql("UPDATE sim_booking SET seat_number = :seat WHERE id = :id")
                .param("seat", seat).param("id", bookingId).update();

        return new ToolDtos.CheckInResult(
                booking.pnr(), true, seat, gate, booking.terminal(), sequence,
                channel == null ? "WEB" : channel, Instant.now(),
                "Checked in. Boarding pass issued for seat " + seat + ", gate " + gate + ".");
    }

    /** Cancels a booking and records the refund amount. Two-phase confirmed upstream. */
    public void cancelBooking(String pnr, BigDecimal refundAmount) {
        Map<String, Object> booking = jdbc.sql("""
                    SELECT id,fare_id,flight_instance_id,seat_number,status
                    FROM sim_booking WHERE pnr=:pnr FOR UPDATE
                """)
                .param("pnr", pnr)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new com.unitedair.ai.shared.ApiExceptions.NotFound(
                        "No booking found for that reference."));
        if (!"CONFIRMED".equals(String.valueOf(booking.get("status")))) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "This booking is already "
                            + String.valueOf(booking.get("status")).toLowerCase(Locale.ROOT) + ".");
        }
        long bookingId = ((Number) booking.get("id")).longValue();
        long fareId = ((Number) booking.get("fare_id")).longValue();
        long instanceId = ((Number) booking.get("flight_instance_id")).longValue();
        String seat = booking.get("seat_number") == null
                ? null : String.valueOf(booking.get("seat_number"));
        String status = refundAmount != null
                && refundAmount.compareTo(BigDecimal.ZERO) > 0
                ? "REFUND_PENDING" : "CANCELLED";

        int changed = jdbc.sql("""
                    UPDATE sim_booking
                       SET status = :status,
                           refund_amount_inr = :refund,
                           refund_status = 'PENDING',
                           cancelled_at = CURRENT_TIMESTAMP
                     WHERE id = :id AND status='CONFIRMED'
                """)
                .param("status", status)
                .param("refund", refundAmount)
                .param("id", bookingId)
                .update();
        if (changed != 1) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "The booking changed before cancellation. Refresh and try again.");
        }

        jdbc.sql("UPDATE sim_fare SET seats_available=seats_available+1 WHERE id=:fare")
                .param("fare", fareId).update();
        if (seat != null && !seat.isBlank()) {
            jdbc.sql("""
                        UPDATE sim_seat SET occupied=FALSE
                        WHERE flight_instance_id=:instance AND seat_number=:seat
                    """)
                    .param("instance", instanceId)
                    .param("seat", seat)
                    .update();
        }
        jdbc.sql("""
                    UPDATE sim_ticket SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP
                    WHERE booking_id=:booking
                """)
                .param("booking", bookingId)
                .update();
        if (refundAmount != null && refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            jdbc.sql("""
                        UPDATE sim_payment
                        SET status='REFUNDED',refunded_at=CURRENT_TIMESTAMP
                        WHERE booking_id=:booking AND status='CAPTURED'
                    """)
                    .param("booking", bookingId)
                    .update();
        }
    }

    public RescheduleQuote quoteReschedule(ToolDtos.BookingView booking,
                                          Long targetFlightInstanceId,
                                          String targetFareClass) {
        if (!"CONFIRMED".equals(booking.status())) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "Only a confirmed booking can be rescheduled.");
        }
        if (!booking.changeable()) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "This fare does not permit rescheduling.");
        }
        if (targetFlightInstanceId == null || targetFareClass == null || targetFareClass.isBlank()) {
            throw new com.unitedair.ai.shared.ApiExceptions.BadRequest(
                    "Choose a target flight and fare before requesting a reschedule.");
        }

        TargetFare target = jdbc.sql("""
                    SELECT i.id AS instance_id, f.flight_no, f.origin, f.destination,
                           i.flight_date, f.dep_time_local, sf.id AS fare_id,
                           sf.fare_class, sf.cabin, sf.fare_brand, sf.price_inr,
                           sf.seats_available, i.status
                    FROM sim_flight_instance i
                    JOIN sim_flight f ON f.id = i.flight_id
                    JOIN sim_fare sf ON sf.flight_instance_id = i.id
                    WHERE i.id = :instance AND sf.fare_class = :fare
                """)
                .param("instance", targetFlightInstanceId)
                .param("fare", targetFareClass.trim().toUpperCase(Locale.ROOT))
                .query((rs, n) -> new TargetFare(
                        rs.getLong("instance_id"),
                        rs.getLong("fare_id"),
                        rs.getString("flight_no"),
                        rs.getString("origin"),
                        rs.getString("destination"),
                        rs.getDate("flight_date").toLocalDate(),
                        rs.getTime("dep_time_local").toLocalTime(),
                        rs.getString("fare_class"),
                        rs.getString("cabin"),
                        rs.getString("fare_brand"),
                        rs.getBigDecimal("price_inr"),
                        rs.getInt("seats_available"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> new com.unitedair.ai.shared.ApiExceptions.NotFound(
                        "That fare is not available on the selected flight."));

        if (target.instanceId().equals(currentFlightInstanceId(booking.pnr()))) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "Choose a different flight to reschedule this booking.");
        }
        if (!booking.origin().equals(target.origin()) || !booking.destination().equals(target.destination())) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "The new flight must use the same origin and destination.");
        }
        if (target.flightDate().isBefore(LocalDate.now())
                || "CANCELLED".equalsIgnoreCase(target.status())) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "The selected flight is not available for travel.");
        }
        if (target.seatsAvailable() < 1) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "That fare is sold out on the selected flight.");
        }
        if (!booking.cabin().equalsIgnoreCase(target.cabin())) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "Choose a fare in the booking's current " + booking.cabin() + " cabin.");
        }

        RescheduleAmounts amounts = calculateRescheduleAmounts(
                booking.amountPaid(), booking.changeFee(), target.price());
        return new RescheduleQuote(
                target.instanceId(), target.fareId(), target.flightNo(), target.origin(),
                target.destination(), target.flightDate(), target.departureTime(),
                target.fareClass(), target.cabin(), target.fareBrand(), target.price(),
                amounts.fareDifference(), booking.changeFee(), amounts.totalDue(), amounts.credit());
    }

    public SeatChangeQuote quoteSeatChange(ToolDtos.BookingView booking, String requestedSeat) {
        if (!"CONFIRMED".equals(booking.status())) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "Only a confirmed booking can change seats.");
        }
        String seat = normaliseSeat(requestedSeat);
        if (seat.equalsIgnoreCase(booking.seatNumber())) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "That seat is already assigned to this booking.");
        }

        Long instanceId = currentFlightInstanceId(booking.pnr());
        ensureSeatMap(instanceId);
        ToolDtos.SeatOption option = jdbc.sql("""
                    SELECT seat_number, cabin, seat_type, extra_legroom, exit_row, fee_inr,
                           (occupied = FALSE AND blocked = FALSE) AS available
                    FROM sim_seat
                    WHERE flight_instance_id = :instance AND seat_number = :seat
                """)
                .param("instance", instanceId)
                .param("seat", seat)
                .query((rs, n) -> new ToolDtos.SeatOption(
                        rs.getString("seat_number"), rs.getString("cabin"),
                        rs.getString("seat_type"), rs.getBoolean("extra_legroom"),
                        rs.getBoolean("exit_row"), rs.getBigDecimal("fee_inr"),
                        rs.getBoolean("available")))
                .optional()
                .orElseThrow(() -> new com.unitedair.ai.shared.ApiExceptions.NotFound(
                        "That seat does not exist on this aircraft."));

        if (!option.available()) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "Seat " + seat + " is not available.");
        }
        if (!booking.cabin().equalsIgnoreCase(option.cabin())) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "Seat " + seat + " is outside the booking's " + booking.cabin() + " cabin.");
        }
        return new SeatChangeQuote(instanceId, booking.seatNumber(), option);
    }

    @Transactional
    public void reschedule(String pnr, long flightInstanceId, String fareClass) {
        LockedBooking current = lockBooking(pnr);
        TargetFare target = lockTargetFare(flightInstanceId, fareClass);
        if (target.seatsAvailable() < 1) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "That fare sold out before confirmation. No change was made.");
        }

        int reserved = jdbc.sql("""
                    UPDATE sim_fare SET seats_available = seats_available - 1
                    WHERE id = :id AND seats_available > 0
                """).param("id", target.fareId()).update();
        if (reserved != 1) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "That fare sold out before confirmation. No change was made.");
        }
        jdbc.sql("UPDATE sim_fare SET seats_available = seats_available + 1 WHERE id = :id")
                .param("id", current.fareId()).update();
        releaseSeat(current.flightInstanceId(), current.seatNumber());
        jdbc.sql("DELETE FROM sim_checkin WHERE booking_id = :id")
                .param("id", current.bookingId()).update();
        jdbc.sql("""
                    UPDATE sim_booking
                       SET flight_instance_id = :instance, fare_id = :fare,
                           seat_number = NULL, refund_amount_inr = NULL
                     WHERE id = :booking
                """)
                .param("instance", target.instanceId())
                .param("fare", target.fareId())
                .param("booking", current.bookingId())
                .update();
    }

    @Transactional
    public SeatChangeResult changeSeat(String pnr, String requestedSeat) {
        LockedBooking current = lockBooking(pnr);
        String seat = normaliseSeat(requestedSeat);
        ensureSeatMap(current.flightInstanceId());
        if (seat.equalsIgnoreCase(current.seatNumber())) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "That seat is already assigned to this booking.");
        }

        ToolDtos.SeatOption option = jdbc.sql("""
                    SELECT seat_number, cabin, seat_type, extra_legroom, exit_row, fee_inr,
                           (occupied = FALSE AND blocked = FALSE) AS available
                    FROM sim_seat
                    WHERE flight_instance_id = :instance AND seat_number = :seat
                    FOR UPDATE
                """)
                .param("instance", current.flightInstanceId())
                .param("seat", seat)
                .query((rs, n) -> new ToolDtos.SeatOption(
                        rs.getString("seat_number"), rs.getString("cabin"),
                        rs.getString("seat_type"), rs.getBoolean("extra_legroom"),
                        rs.getBoolean("exit_row"), rs.getBigDecimal("fee_inr"),
                        rs.getBoolean("available")))
                .optional()
                .orElseThrow(() -> new com.unitedair.ai.shared.ApiExceptions.NotFound(
                        "That seat does not exist on this aircraft."));
        if (!current.cabin().equalsIgnoreCase(option.cabin())) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "Seat " + seat + " is outside the booking's " + current.cabin() + " cabin.");
        }
        if (!option.available()) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "Seat " + seat + " is no longer available. No change was made.");
        }

        int occupied = jdbc.sql("""
                    UPDATE sim_seat SET occupied = TRUE
                    WHERE flight_instance_id = :instance AND seat_number = :seat
                      AND occupied = FALSE AND blocked = FALSE
                """)
                .param("instance", current.flightInstanceId())
                .param("seat", seat)
                .update();
        if (occupied != 1) {
            throw new com.unitedair.ai.shared.ApiExceptions.Conflict(
                    "Seat " + seat + " is no longer available. No change was made.");
        }
        releaseSeat(current.flightInstanceId(), current.seatNumber());
        SeatChangeAmount amount = calculateSeatChangeAmount(
                current.amountPaid(), option.feeInr());
        jdbc.sql("""
                    UPDATE sim_booking
                    SET seat_number = :seat, amount_paid_inr = :updatedAmount
                    WHERE id = :id
                """)
                .param("seat", seat)
                .param("updatedAmount", amount.updatedAmountPaid())
                .param("id", current.bookingId())
                .update();
        jdbc.sql("UPDATE sim_checkin SET seat = :seat WHERE booking_id = :id")
                .param("seat", seat).param("id", current.bookingId()).update();
        return new SeatChangeResult(
                current.seatNumber(),
                seat,
                option.seatType(),
                amount.additionalCharge(),
                amount.updatedAmountPaid());
    }

    static SeatChangeAmount calculateSeatChangeAmount(
            BigDecimal amountPaid, BigDecimal verifiedSeatFee) {
        BigDecimal current = amountPaid == null ? BigDecimal.ZERO : amountPaid;
        BigDecimal charge = verifiedSeatFee == null
                ? BigDecimal.ZERO
                : verifiedSeatFee.max(BigDecimal.ZERO);
        return new SeatChangeAmount(charge, current.add(charge));
    }

    static RescheduleAmounts calculateRescheduleAmounts(BigDecimal amountPaid,
                                                        BigDecimal changeFee,
                                                        BigDecimal targetFare) {
        BigDecimal paid = amountPaid == null ? BigDecimal.ZERO : amountPaid;
        BigDecimal fee = changeFee == null ? BigDecimal.ZERO : changeFee;
        BigDecimal target = targetFare == null ? BigDecimal.ZERO : targetFare;
        BigDecimal difference = target.subtract(paid);
        BigDecimal totalDue = fee.add(difference.max(BigDecimal.ZERO));
        BigDecimal credit = difference.signum() < 0 ? difference.abs() : BigDecimal.ZERO;
        return new RescheduleAmounts(difference, totalDue, credit);
    }

    private Long currentFlightInstanceId(String pnr) {
        return jdbc.sql("SELECT flight_instance_id FROM sim_booking WHERE pnr = :pnr")
                .param("pnr", pnr).query(Long.class)
                .optional()
                .orElseThrow(() -> new com.unitedair.ai.shared.ApiExceptions.NotFound(
                        "The booking no longer exists."));
    }

    private LockedBooking lockBooking(String pnr) {
        return jdbc.sql("""
                    SELECT b.id, b.flight_instance_id, b.fare_id, b.seat_number,
                           b.amount_paid_inr, f.cabin
                    FROM sim_booking b
                    JOIN sim_fare f ON f.id = b.fare_id
                    WHERE b.pnr = :pnr FOR UPDATE
                """)
                .param("pnr", pnr)
                .query((rs, n) -> new LockedBooking(
                        rs.getLong("id"), rs.getLong("flight_instance_id"),
                        rs.getLong("fare_id"), rs.getString("seat_number"),
                        rs.getString("cabin"), rs.getBigDecimal("amount_paid_inr")))
                .optional()
                .orElseThrow(() -> new com.unitedair.ai.shared.ApiExceptions.NotFound(
                        "The booking no longer exists."));
    }

    private TargetFare lockTargetFare(long flightInstanceId, String fareClass) {
        return jdbc.sql("""
                    SELECT i.id AS instance_id, f.flight_no, f.origin, f.destination,
                           i.flight_date, f.dep_time_local, sf.id AS fare_id,
                           sf.fare_class, sf.cabin, sf.fare_brand, sf.price_inr,
                           sf.seats_available, i.status
                    FROM sim_flight_instance i
                    JOIN sim_flight f ON f.id = i.flight_id
                    JOIN sim_fare sf ON sf.flight_instance_id = i.id
                    WHERE i.id = :instance AND sf.fare_class = :fare
                    FOR UPDATE
                """)
                .param("instance", flightInstanceId)
                .param("fare", fareClass.trim().toUpperCase(Locale.ROOT))
                .query((rs, n) -> new TargetFare(
                        rs.getLong("instance_id"), rs.getLong("fare_id"),
                        rs.getString("flight_no"), rs.getString("origin"),
                        rs.getString("destination"), rs.getDate("flight_date").toLocalDate(),
                        rs.getTime("dep_time_local").toLocalTime(), rs.getString("fare_class"),
                        rs.getString("cabin"), rs.getString("fare_brand"),
                        rs.getBigDecimal("price_inr"), rs.getInt("seats_available"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> new com.unitedair.ai.shared.ApiExceptions.NotFound(
                        "That fare is no longer available."));
    }

    private void releaseSeat(Long instanceId, String seat) {
        if (instanceId == null || seat == null || seat.isBlank()) {
            return;
        }
        jdbc.sql("""
                    UPDATE sim_seat SET occupied = FALSE
                    WHERE flight_instance_id = :instance AND seat_number = :seat
                """).param("instance", instanceId).param("seat", seat).update();
    }

    private static String normaliseSeat(String seat) {
        if (seat == null || !seat.trim().toUpperCase(Locale.ROOT).matches("\\d{1,2}[A-F]")) {
            throw new com.unitedair.ai.shared.ApiExceptions.BadRequest(
                    "Choose a valid seat such as 12A.");
        }
        return seat.trim().toUpperCase(Locale.ROOT);
    }

    public List<ToolDtos.SeatOption> seatMap(Long flightInstanceId) {
        ensureSeatMap(flightInstanceId);
        return jdbc.sql("""
                    SELECT seat_number, cabin, seat_type, extra_legroom, exit_row, fee_inr,
                           (occupied = FALSE AND blocked = FALSE) AS available
                    FROM sim_seat
                    WHERE flight_instance_id = :id
                    ORDER BY CAST(REGEXP_REPLACE(seat_number, '[^0-9]', '') AS UNSIGNED), seat_number
                """)
                .param("id", flightInstanceId)
                .query((rs, n) -> new ToolDtos.SeatOption(
                        rs.getString("seat_number"),
                        rs.getString("cabin"),
                        rs.getString("seat_type"),
                        rs.getBoolean("extra_legroom"),
                        rs.getBoolean("exit_row"),
                        rs.getBigDecimal("fee_inr"),
                        rs.getBoolean("available")))
                .list();
    }

    /**
     * Seat maps are generated on first request rather than seeded. Pre-seeding every
     * departure would mean tens of thousands of rows for data most of which is never read.
     */
    private void ensureSeatMap(Long flightInstanceId) {
        Long existing = jdbc.sql("SELECT COUNT(*) FROM sim_seat WHERE flight_instance_id = :id")
                .param("id", flightInstanceId).query(Long.class).single();
        if (existing != null && existing > 0) {
            return;
        }

        Boolean international = jdbc.sql("""
                    SELECT f.international FROM sim_flight f
                    JOIN sim_flight_instance i ON i.flight_id = f.id
                    WHERE i.id = :id
                """).param("id", flightInstanceId).query(Boolean.class).optional().orElse(false);

        int businessRows = Boolean.TRUE.equals(international) ? 4 : 2;
        int economyRows = Boolean.TRUE.equals(international) ? 34 : 28;
        char[] columns = {'A', 'B', 'C', 'D', 'E', 'F'};
        // Deterministic occupancy so a demo shows a realistic map that does not
        // change between page loads.
        Random random = new Random(flightInstanceId);

        List<Object[]> batch = new ArrayList<>();
        int row = 1;
        for (; row <= businessRows; row++) {
            for (char column : new char[]{'A', 'C', 'D', 'F'}) {
                batch.add(new Object[]{row + "" + column, "BUSINESS", seatType(column),
                        true, false, BigDecimal.ZERO, random.nextInt(100) < 35});
            }
        }
        for (int i = 0; i < economyRows; i++, row++) {
            boolean exitRow = i == 10 || i == 11;
            boolean extraLegroom = exitRow || i == 0;
            BigDecimal fee = extraLegroom ? BigDecimal.valueOf(800)
                    : i < 5 ? BigDecimal.valueOf(400) : BigDecimal.valueOf(200);
            for (char column : columns) {
                batch.add(new Object[]{row + "" + column, "ECONOMY", seatType(column),
                        extraLegroom, exitRow, fee, random.nextInt(100) < 45});
            }
        }

        for (Object[] seat : batch) {
            jdbc.sql("""
                        INSERT IGNORE INTO sim_seat (flight_instance_id, seat_number, cabin, seat_type,
                            extra_legroom, exit_row, fee_inr, occupied)
                        VALUES (:id, :seat, :cabin, :type, :legroom, :exit, :fee, :occupied)
                    """)
                    .param("id", flightInstanceId)
                    .param("seat", seat[0])
                    .param("cabin", seat[1])
                    .param("type", seat[2])
                    .param("legroom", seat[3])
                    .param("exit", seat[4])
                    .param("fee", seat[5])
                    .param("occupied", seat[6])
                    .update();
        }
    }

    private static String seatType(char column) {
        return switch (column) {
            case 'A', 'F' -> "WINDOW";
            case 'C', 'D' -> "AISLE";
            default -> "MIDDLE";
        };
    }

    private String allocateSeat(String pnr) {
        Long instanceId = jdbc.sql("SELECT flight_instance_id FROM sim_booking WHERE pnr = :pnr")
                .param("pnr", pnr).query(Long.class).single();
        ensureSeatMap(instanceId);

        String seat = jdbc.sql("""
                    SELECT seat_number FROM sim_seat
                    WHERE flight_instance_id = :id AND occupied = FALSE AND blocked = FALSE
                      AND cabin = 'ECONOMY'
                    ORDER BY fee_inr, seat_number
                    LIMIT 1
                """).param("id", instanceId).query(String.class).optional().orElse("18C");

        jdbc.sql("""
                    UPDATE sim_seat SET occupied = TRUE
                    WHERE flight_instance_id = :id AND seat_number = :seat
                """).param("id", instanceId).param("seat", seat).update();

        return seat;
    }

    private int nextSequenceNumber(String pnr) {
        Long instanceId = jdbc.sql("SELECT flight_instance_id FROM sim_booking WHERE pnr = :pnr")
                .param("pnr", pnr).query(Long.class).single();
        Long count = jdbc.sql("""
                    SELECT COUNT(*) FROM sim_checkin c
                    JOIN sim_booking b ON b.id = c.booking_id
                    WHERE b.flight_instance_id = :id
                """).param("id", instanceId).query(Long.class).single();
        return (count == null ? 0 : count.intValue()) + 1;
    }

    // -------------------------------------------------------------------- time ---

    private static Instant departureInstant(LocalDate date, LocalTime time) {
        return LocalDateTime.of(date, time).atZone(OPERATING_ZONE).toInstant();
    }

    static long hoursToDeparture(LocalDate date, LocalTime time) {
        return Duration.between(Instant.now(), departureInstant(date, time)).toHours();
    }

    private static ToolDtos.BookingView mapBooking(ResultSet rs, int rowNum) throws SQLException {
        boolean checkedIn = rs.getObject("checkin_id") != null;
        String seat = rs.getString("checkin_seat") != null
                ? rs.getString("checkin_seat")
                : rs.getString("seat_number");

        return new ToolDtos.BookingView(
                rs.getString("pnr"),
                rs.getString("passenger_name"),
                rs.getString("status"),
                rs.getString("flight_no"),
                rs.getString("origin"),
                rs.getString("destination"),
                rs.getDate("flight_date").toLocalDate(),
                rs.getTime("dep_time_local").toLocalTime(),
                rs.getTime("arr_time_local").toLocalTime(),
                rs.getString("flight_status"),
                rs.getInt("delay_minutes"),
                rs.getString("terminal"),
                rs.getString("gate"),
                rs.getString("cabin"),
                rs.getString("fare_class"),
                rs.getString("fare_brand"),
                rs.getBoolean("refundable"),
                rs.getBoolean("changeable"),
                rs.getBigDecimal("change_fee_inr"),
                rs.getBigDecimal("cancel_fee_inr"),
                rs.getBigDecimal("amount_paid_inr"),
                rs.getBigDecimal("refund_amount_inr"),
                seat,
                rs.getInt("checked_baggage_kg"),
                rs.getString("ffp_tier"),
                checkedIn,
                rs.getString("boarding_gate"),
                rs.getTimestamp("booked_at").toInstant(),
                Instant.now());
    }

    public record RescheduleQuote(
            Long targetFlightInstanceId,
            Long targetFareId,
            String flightNo,
            String origin,
            String destination,
            LocalDate flightDate,
            LocalTime departureTime,
            String fareClass,
            String cabin,
            String fareBrand,
            BigDecimal targetFare,
            BigDecimal fareDifference,
            BigDecimal changeFee,
            BigDecimal totalDue,
            BigDecimal credit) { }

    public record SeatChangeQuote(
            Long flightInstanceId,
            String currentSeat,
            ToolDtos.SeatOption targetSeat) { }

    public record SeatChangeResult(
            String previousSeat,
            String selectedSeat,
            String seatType,
            BigDecimal additionalCharge,
            BigDecimal updatedAmountPaid) { }

    record SeatChangeAmount(
            BigDecimal additionalCharge,
            BigDecimal updatedAmountPaid) { }

    record RescheduleAmounts(
            BigDecimal fareDifference,
            BigDecimal totalDue,
            BigDecimal credit) { }

    private record TargetFare(
            Long instanceId,
            Long fareId,
            String flightNo,
            String origin,
            String destination,
            LocalDate flightDate,
            LocalTime departureTime,
            String fareClass,
            String cabin,
            String fareBrand,
            BigDecimal price,
            int seatsAvailable,
            String status) { }

    private record LockedBooking(
            Long bookingId,
            Long flightInstanceId,
            Long fareId,
            String seatNumber,
            String cabin,
            BigDecimal amountPaid) { }
}
