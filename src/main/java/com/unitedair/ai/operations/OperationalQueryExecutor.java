package com.unitedair.ai.operations;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.unitedair.ai.operations.OperationalQueryDtos.*;

/** Executes precompiled SELECT statements with a bounded result surface. */
@Component
public class OperationalQueryExecutor {

    private final JdbcClient jdbc;
    private final UnitedAirProperties properties;

    public OperationalQueryExecutor(
            JdbcClient jdbc,
            UnitedAirProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional(readOnly = true, timeout = 2)
    public OperationalDataResult execute(CompiledQuery query) {
        if (query == null || query.sql() == null
                || !query.sql().startsWith("SELECT ")
                || query.sql().contains(";")) {
            throw new IllegalArgumentException("Only compiled SELECT queries are accepted.");
        }
        List<Map<String, Object>> raw = jdbc.sql(query.sql())
                .params(query.parameters())
                .query()
                .listOfRows();
        int cellBudget = Math.max(
                1, properties.getOperationalQuery().getMaxCellsPerTurn());
        ArrayList<Map<String, Object>> bounded = new ArrayList<>();
        int cells = 0;
        boolean truncated = false;
        for (Map<String, Object> row : raw) {
            if (cells + row.size() > cellBudget) {
                truncated = true;
                break;
            }
            bounded.add(new LinkedHashMap<>(row));
            cells += row.size();
        }
        if (bounded.size() < raw.size()) {
            truncated = true;
        }
        return new OperationalDataResult(
                query.dataset(),
                bounded,
                Map.of(),
                Instant.now(),
                bounded.size(),
                truncated,
                query.fingerprint());
    }
}
