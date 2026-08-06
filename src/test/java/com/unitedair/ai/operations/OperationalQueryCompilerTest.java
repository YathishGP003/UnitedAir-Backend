package com.unitedair.ai.operations;

import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.unitedair.ai.operations.OperationalQueryDtos.*;
import static org.assertj.core.api.Assertions.assertThat;

class OperationalQueryCompilerTest {

    private final SemanticDatasetCatalog catalog = new SemanticDatasetCatalog();
    private final OperationalQueryPolicy policy = new OperationalQueryPolicy(catalog);
    private final OperationalQueryCompiler compiler =
            new OperationalQueryCompiler(catalog);

    @Test
    void hostileFilterValueRemainsABoundParameter() {
        String hostile = "X2LTWZ' OR 1=1 --";
        var proposed = new QueryPlan(List.of(new DatasetQuery(
                Dataset.BOOKINGS,
                List.of("pnr", "status"),
                List.of(new Filter("pnr", Operator.EQ, List.of(hostile))),
                List.of(), List.of(), List.of(), List.of(), 20)));
        var validated = policy.validate(
                proposed, Role.PASSENGER, 42L,
                new OperationalQueryPolicy.QueryLimits(3, 2, 50));

        var compiled = compiler.compile(validated.queries().getFirst());

        assertThat(compiled.sql()).startsWith("SELECT ");
        assertThat(compiled.sql()).contains("FROM v_ai_booking t");
        assertThat(compiled.sql()).contains("t.owner_user_id = ?");
        assertThat(compiled.sql()).doesNotContain(hostile).doesNotContain(";");
        assertThat(compiled.parameters()).containsExactly(42L, hostile);
        assertThat(compiled.fingerprint()).hasSize(64);
    }

    @Test
    void inFilterSortAndLimitCompileDeterministically() {
        var proposed = new QueryPlan(List.of(new DatasetQuery(
                Dataset.REFUND_CASES,
                List.of("caseReference", "status", "dueAt"),
                List.of(new Filter(
                        "status", Operator.IN, List.of("PENDING", "PROCESSING"))),
                List.of(), List.of(), List.of(),
                List.of(new Sort("dueAt", Direction.ASC)), 20)));
        var validated = policy.validate(
                proposed, Role.AIRLINE_STAFF, 2L,
                new OperationalQueryPolicy.QueryLimits(3, 2, 50));

        var compiled = compiler.compile(validated.queries().getFirst());

        assertThat(compiled.sql())
                .contains("t.status IN (?, ?)")
                .contains("ORDER BY t.due_at ASC")
                .endsWith("LIMIT 20");
        assertThat(compiled.parameters()).containsExactly("PENDING", "PROCESSING");
    }
}
