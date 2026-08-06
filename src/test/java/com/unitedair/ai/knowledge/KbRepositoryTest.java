package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class KbRepositoryTest {

    @Test
    void requestedDateRangeUsesIntervalOverlapBeforeScoring() throws Exception {
        RetrievalDtos.Filter filter = new RetrievalDtos.Filter(
                List.of("Passenger", "All"),
                Set.of("TXT"),
                Set.of("fare-rule"),
                Set.of("KB-AIR-004"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                Map.of("doc_type", "fare_rule"));
        Map<String, Object> params = new HashMap<>();

        String where = invokeBuildWhere(filter, params);

        assertThat(where)
                .contains("v.status = 'ACTIVE'")
                .contains("c.effective_from IS NULL OR c.effective_from <= :effectiveTo")
                .contains("c.effective_to IS NULL OR c.effective_to >= :effectiveFrom")
                .contains("JSON_VALUE(c.tags, :tagPath0) = :tagVal0");
        assertThat(params)
                .containsEntry("effectiveFrom", java.sql.Date.valueOf("2026-07-01"))
                .containsEntry("effectiveTo", java.sql.Date.valueOf("2026-07-31"))
                .containsEntry("tagPath0", "$.doc_type")
                .containsEntry("tagVal0", "fare_rule");
    }

    @Test
    void openEndedRangeOnlyAddsTheRelevantBoundary() throws Exception {
        RetrievalDtos.Filter filter = new RetrievalDtos.Filter(
                List.of("All"), Set.of(), Set.of(), Set.of(),
                LocalDate.of(2026, 7, 1), null, Map.of());

        String where = invokeBuildWhere(filter, new HashMap<>());

        assertThat(where)
                .contains("c.effective_to IS NULL OR c.effective_to >= :effectiveFrom")
                .doesNotContain(":effectiveTo");
    }

    private static String invokeBuildWhere(
            RetrievalDtos.Filter filter,
            Map<String, Object> params) throws Exception {
        KbRepository repository = new KbRepository(
                org.mockito.Mockito.mock(JdbcClient.class));
        Method method = KbRepository.class.getDeclaredMethod(
                "buildWhere", RetrievalDtos.Filter.class, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(repository, filter, params);
    }
}

