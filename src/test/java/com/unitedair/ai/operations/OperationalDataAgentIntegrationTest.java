package com.unitedair.ai.operations;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static com.unitedair.ai.operations.OperationalQueryDtos.*;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OperationalDataAgentIntegrationTest {

    @Test
    void everySemanticDatasetCompilesAndExecutesAgainstMariaDb() {
        JdbcClient jdbc = jdbc();
        SemanticDatasetCatalog catalog = new SemanticDatasetCatalog();
        OperationalQueryPolicy policy = new OperationalQueryPolicy(catalog);
        OperationalQueryCompiler compiler = new OperationalQueryCompiler(catalog);
        UnitedAirProperties properties = new UnitedAirProperties();
        OperationalQueryExecutor executor =
                new OperationalQueryExecutor(jdbc, properties);
        var limits = new OperationalQueryPolicy.QueryLimits(3, 0, 50);

        for (Dataset dataset : Dataset.values()) {
            String field = catalog.descriptor(dataset).fields()
                    .keySet().stream().sorted().findFirst().orElseThrow();
            DatasetQuery query = new DatasetQuery(
                    dataset, List.of(field), List.of(), List.of(),
                    List.of(), List.of(), List.of(), 1);
            CompiledQuery compiled = compiler.compile(
                    policy.validate(
                            new QueryPlan(List.of(query)),
                            Role.ADMIN, 1L, limits)
                            .queries().getFirst());

            OperationalDataResult result = executor.execute(compiled);

            assertThat(result.dataset()).isEqualTo(dataset);
            assertThat(result.queryFingerprint()).hasSize(64);
            assertThat(result.rowCount()).isBetween(0, 1);
        }
    }

    @Test
    void passengerOwnershipPredicatePreventsCrossOwnerBookingReads() {
        JdbcClient jdbc = jdbc();
        SemanticDatasetCatalog catalog = new SemanticDatasetCatalog();
        OperationalQueryPolicy policy = new OperationalQueryPolicy(catalog);
        OperationalQueryCompiler compiler = new OperationalQueryCompiler(catalog);
        OperationalQueryExecutor executor =
                new OperationalQueryExecutor(jdbc, new UnitedAirProperties());
        DatasetQuery bookings = new DatasetQuery(
                Dataset.BOOKINGS, List.of("pnr", "status"), List.of(),
                List.of(), List.of(), List.of(), List.of(), 20);
        var limits = new OperationalQueryPolicy.QueryLimits(1, 0, 20);

        Long passengerId = jdbc.sql("""
                SELECT id FROM app_user
                WHERE email = 'passenger@unitedair.demo'
                """).query(Long.class).single();
        OperationalDataResult owned = executor.execute(compiler.compile(
                policy.validate(new QueryPlan(List.of(bookings)),
                        Role.PASSENGER, passengerId, limits)
                        .queries().getFirst()));
        OperationalDataResult foreign = executor.execute(compiler.compile(
                policy.validate(new QueryPlan(List.of(bookings)),
                        Role.PASSENGER, -9_999_999L, limits)
                        .queries().getFirst()));

        assertThat(owned.rows()).isNotEmpty();
        assertThat(foreign.rows()).isEmpty();
    }

    private static JdbcClient jdbc() {
        String url = value("test.db.url", "DB_URL",
                "jdbc:mariadb://127.0.0.1:3306/unitedair");
        String username = value("test.db.username", "DB_USERNAME", "unitedair");
        String password = value("test.db.password", "DB_PASSWORD", "unitedair");
        DriverManagerDataSource source = new DriverManagerDataSource(
                url, username, password);
        source.setDriverClassName("org.mariadb.jdbc.Driver");
        return JdbcClient.create(source);
    }

    private static String value(
            String property,
            String environment,
            String fallback) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(environment);
        }
        return configured == null || configured.isBlank()
                ? fallback : configured;
    }
}
