package com.unitedair.ai.operations;

import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.unitedair.ai.operations.OperationalQueryDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalDataSecurityTest {

    private final SemanticDatasetCatalog catalog = new SemanticDatasetCatalog();
    private final OperationalQueryPolicy policy = new OperationalQueryPolicy(catalog);
    private final OperationalQueryCompiler compiler = new OperationalQueryCompiler(catalog);
    private final OperationalQueryPolicy.QueryLimits limits =
            new OperationalQueryPolicy.QueryLimits(3, 2, 50);

    @Test
    void injectionTextIsOnlyABoundValueAndPassengerOwnerComesFromAuthentication() {
        String attack = "X2LTWZ' OR 1=1 --";
        QueryPlan plan = plan(Dataset.BOOKINGS, List.of("pnr", "status"),
                List.of(new Filter("pnr", Operator.EQ, List.of(attack))));

        CompiledQuery compiled = compiler.compile(
                policy.validate(plan, Role.PASSENGER, 42L, limits)
                        .queries().getFirst());

        assertThat(compiled.sql())
                .startsWith("SELECT ")
                .doesNotContain(attack)
                .doesNotContain(";")
                .contains("owner_user_id = ?");
        assertThat(compiled.parameters()).containsExactly(42L, attack);
    }

    @Test
    void crossRoleHiddenFieldJoinAndWriteShapedRequestsFailClosed() {
        QueryPlan staffQueue = plan(
                Dataset.ESCALATIONS, List.of("caseReference"), List.of());
        QueryPlan hiddenField = plan(
                Dataset.BOOKINGS, List.of("ownerUserId"), List.of());
        QueryPlan forbiddenJoin = new QueryPlan(List.of(new DatasetQuery(
                Dataset.BOOKINGS, List.of("pnr"), List.of(),
                List.of(new Join(Dataset.AUDIT_EVENTS, "pnr", "eventType")),
                List.of(), List.of(), List.of(), 20)));

        assertThatThrownBy(() -> policy.validate(
                staffQueue, Role.PASSENGER, 42L, limits))
                .extracting("code").isEqualTo("ROLE_FORBIDDEN");
        assertThatThrownBy(() -> policy.validate(
                hiddenField, Role.ADMIN, 1L, limits))
                .extracting("code").isEqualTo("FIELD_NOT_ALLOWED");
        assertThatThrownBy(() -> policy.validate(
                forbiddenJoin, Role.ADMIN, 1L, limits))
                .extracting("code").isEqualTo("JOIN_NOT_ALLOWED");
        assertThatThrownBy(() -> compiler.compile(new ValidatedQuery(
                new DatasetQuery(Dataset.BOOKINGS,
                        List.of("DELETE FROM booking"), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 20),
                Role.ADMIN, 1L, "client-forged")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static QueryPlan plan(
            Dataset dataset,
            List<String> fields,
            List<Filter> filters) {
        return new QueryPlan(List.of(new DatasetQuery(
                dataset, fields, filters, List.of(), List.of(),
                List.of(), List.of(), 20)));
    }
}
