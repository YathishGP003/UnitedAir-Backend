package com.unitedair.ai.commerce;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TravelCatalogRepository {

    private final JdbcClient jdbc;

    public TravelCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CommerceDtos.AirportView> airports() {
        return jdbc.sql("""
                    SELECT code,city,name,country,domestic
                    FROM sim_airport ORDER BY city
                """).query(TravelCatalogRepository::airport).list();
    }

    public List<CommerceDtos.AirportView> destinationsFrom(String origin) {
        return jdbc.sql("""
                    SELECT DISTINCT a.code,a.city,a.name,a.country,a.domestic
                    FROM sim_flight f
                    JOIN sim_airport a ON a.code=f.destination
                    WHERE f.origin=:origin
                    ORDER BY a.city
                """)
                .param("origin", origin)
                .query(TravelCatalogRepository::airport)
                .list();
    }

    public List<LocalDate> nearbyDates(
            String origin, String destination, LocalDate requested) {
        return jdbc.sql("""
                    SELECT DISTINCT i.flight_date
                    FROM sim_flight_instance i
                    JOIN sim_flight f ON f.id=i.flight_id
                    WHERE f.origin=:origin AND f.destination=:destination
                      AND i.flight_date >= CURRENT_DATE
                    ORDER BY ABS(DATEDIFF(i.flight_date,:requested)), i.flight_date
                    LIMIT 5
                """)
                .param("origin", origin)
                .param("destination", destination)
                .param("requested", requested)
                .query(LocalDate.class)
                .list();
    }

    private static CommerceDtos.AirportView airport(ResultSet rs, int row) throws SQLException {
        return new CommerceDtos.AirportView(
                rs.getString("code"), rs.getString("city"), rs.getString("name"),
                rs.getString("country"), rs.getBoolean("domestic"));
    }
}
