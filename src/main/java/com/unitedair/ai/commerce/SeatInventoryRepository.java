package com.unitedair.ai.commerce;

import java.util.List;

import com.unitedair.ai.tools.BookingRepository;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SeatInventoryRepository {

    private final BookingRepository bookings;
    private final JdbcClient jdbc;

    public SeatInventoryRepository(BookingRepository bookings, JdbcClient jdbc) {
        this.bookings = bookings;
        this.jdbc = jdbc;
    }

    public List<ToolDtos.SeatOption> seatMap(long flightInstanceId) {
        return bookings.seatMap(flightInstanceId);
    }

    /**
     * Atomically claims a currently available seat. The conditional update is the
     * concurrency guard; a stale UI can never overwrite another passenger's claim.
     */
    public boolean claim(long flightInstanceId, String seatNumber, String cabin) {
        bookings.seatMap(flightInstanceId);
        return jdbc.sql("""
                    UPDATE sim_seat
                    SET occupied=TRUE
                    WHERE flight_instance_id=:instance
                      AND seat_number=:seat
                      AND cabin=:cabin
                      AND occupied=FALSE
                      AND blocked=FALSE
                """)
                .param("instance", flightInstanceId)
                .param("seat", seatNumber)
                .param("cabin", cabin)
                .update() == 1;
    }

    public void release(long flightInstanceId, String seatNumber) {
        jdbc.sql("""
                    UPDATE sim_seat SET occupied=FALSE
                    WHERE flight_instance_id=:instance AND seat_number=:seat
                """)
                .param("instance", flightInstanceId)
                .param("seat", seatNumber)
                .update();
    }
}
