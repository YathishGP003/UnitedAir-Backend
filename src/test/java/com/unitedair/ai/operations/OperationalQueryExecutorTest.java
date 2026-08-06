package com.unitedair.ai.operations;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static com.unitedair.ai.operations.OperationalQueryDtos.Dataset.AIRPORTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationalQueryExecutorTest {

    @Test
    void boundsRowsByCellBudgetAndReturnsProvenance() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.ResultQuerySpec query = mock(JdbcClient.ResultQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.params(anyList())).thenReturn(statement);
        when(statement.query()).thenReturn(query);
        when(query.listOfRows()).thenReturn(List.of(
                Map.of("code", "BLR", "city", "Bengaluru"),
                Map.of("code", "DEL", "city", "New Delhi"),
                Map.of("code", "GOI", "city", "Goa")));
        UnitedAirProperties properties = new UnitedAirProperties();
        properties.getOperationalQuery().setMaxCellsPerTurn(4);
        var executor = new OperationalQueryExecutor(jdbc, properties);

        var result = executor.execute(new OperationalQueryDtos.CompiledQuery(
                AIRPORTS,
                "SELECT t.code AS code, t.city AS city FROM v_ai_airport t LIMIT 20",
                List.of(), 1500, "abc"));

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.queryFingerprint()).isEqualTo("abc");
    }
}
