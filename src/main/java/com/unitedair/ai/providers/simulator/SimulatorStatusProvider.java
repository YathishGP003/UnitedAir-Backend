package com.unitedair.ai.providers.simulator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import com.unitedair.ai.providers.ProviderCapability;
import com.unitedair.ai.providers.StatusProvider;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "unitedair.providers",
        name = "mode",
        havingValue = "simulator",
        matchIfMissing = true)
public class SimulatorStatusProvider implements StatusProvider {

    private final JdbcClient jdbc;

    public SimulatorStatusProvider(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolDtos.FlightStatusView status(String flightNo, LocalDate date) {
        return jdbc.sql("""
                    SELECT f.flight_no, i.flight_date, f.origin, f.destination,
                           i.status, i.delay_minutes, f.dep_time_local, f.arr_time_local,
                           i.terminal, i.gate, i.belt
                    FROM sim_flight_instance i
                    JOIN sim_flight f ON f.id = i.flight_id
                    WHERE f.flight_no = :flightNo AND i.flight_date = :date
                """)
                .param("flightNo", flightNo)
                .param("date", java.sql.Date.valueOf(date))
                .query((rs, n) -> {
                    LocalTime scheduled =
                            rs.getTime("dep_time_local").toLocalTime();
                    int delay = rs.getInt("delay_minutes");
                    return new ToolDtos.FlightStatusView(
                            rs.getString("flight_no"),
                            rs.getDate("flight_date").toLocalDate(),
                            rs.getString("origin"),
                            rs.getString("destination"),
                            rs.getString("status"),
                            delay,
                            scheduled,
                            scheduled.plusMinutes(delay),
                            rs.getTime("arr_time_local").toLocalTime(),
                            rs.getString("terminal"),
                            rs.getString("gate"),
                            rs.getString("belt"),
                            Instant.now());
                })
                .optional()
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "No UnitedAir flight " + flightNo + " operating on " + date + "."));
    }

    @Override
    public ProviderCapability capability() {
        return ProviderCapability.simulator();
    }
}
