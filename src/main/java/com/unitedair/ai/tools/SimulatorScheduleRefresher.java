package com.unitedair.ai.tools;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps the simulator's relative-date examples meaningful after the original Flyway seed
 * date has passed. This only rebases the two published irregular-operation examples; it
 * never touches bookings, fares, seats, check-ins, actions, or historical departures.
 */
@Component
public class SimulatorScheduleRefresher {

    private final JdbcClient jdbc;

    public SimulatorScheduleRefresher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebase() {
        jdbc.sql("""
                    UPDATE sim_flight_instance i
                    JOIN sim_flight f ON f.id = i.flight_id
                       SET i.status = 'ON_TIME',
                           i.delay_minutes = 0,
                           i.gate = CONCAT(CASE WHEN f.international THEN 'B' ELSE 'A' END,
                                           1 + (f.id % 18))
                     WHERE f.flight_no IN ('UA102', 'UA202')
                       AND i.flight_date >= CURDATE()
                """).update();

        jdbc.sql("""
                    UPDATE sim_flight_instance i
                    JOIN sim_flight f ON f.id = i.flight_id
                       SET i.status = 'DELAYED', i.delay_minutes = 55, i.gate = 'A12'
                     WHERE f.flight_no = 'UA102'
                       AND i.flight_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY)
                """).update();

        jdbc.sql("""
                    UPDATE sim_flight_instance i
                    JOIN sim_flight f ON f.id = i.flight_id
                       SET i.status = 'CANCELLED', i.delay_minutes = 0, i.gate = NULL
                     WHERE f.flight_no = 'UA202'
                       AND i.flight_date = DATE_ADD(CURDATE(), INTERVAL 2 DAY)
                """).update();
    }
}
