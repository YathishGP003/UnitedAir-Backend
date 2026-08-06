package com.unitedair.ai.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unitedair.ai.llm.AiMode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class OperationsControllerTest {

    @Test
    void healthDetailsReadsFailedJobsFromTheMigratedKbTable() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql("SELECT COUNT(*) FROM kb_chunk").query(Long.class).single())
                .thenReturn(120L);
        when(jdbc.sql("SELECT COUNT(*) FROM kb_document").query(Long.class).single())
                .thenReturn(8L);
        when(jdbc.sql(contains("FROM kb_ingestion_job"))
                .query(Long.class).single())
                .thenReturn(2L);
        when(jdbc.sql(contains("FROM ingestion_job"))
                .query(Long.class).single())
                .thenReturn(0L);

        var health = new OperationsController(jdbc, AiMode.LIVE).healthDetails();

        assertThat(health.failedIngestions()).isEqualTo(2);
        assertThat(health.aiMode()).isEqualTo("LIVE");
    }
}
