package com.unitedair.ai.operations;

import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.unitedair.ai.operations.OperationalQueryDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalQueryPolicyTest {

    private final OperationalQueryPolicy policy =
            new OperationalQueryPolicy(new SemanticDatasetCatalog());
    private final OperationalQueryPolicy.QueryLimits limits =
            new OperationalQueryPolicy.QueryLimits(3, 2, 50);

    @Test
    void passengerBookingQueryCarriesAuthenticatedOwner() {
        var plan = new QueryPlan(List.of(new DatasetQuery(
                Dataset.BOOKINGS, List.of("pnr", "status"), List.of(),
                List.of(), List.of(), List.of(), List.of(), 20)));

        var validated = policy.validate(plan, Role.PASSENGER, 42L, limits);

        assertThat(validated.queries()).hasSize(1);
        assertThat(validated.queries().getFirst().userId()).isEqualTo(42L);
    }

    @Test
    void passengerCannotReadInternalQueue() {
        var plan = new QueryPlan(List.of(new DatasetQuery(
                Dataset.ESCALATIONS, List.of("caseReference"), List.of(),
                List.of(), List.of(), List.of(), List.of(), 20)));

        assertThatThrownBy(() -> policy.validate(plan, Role.PASSENGER, 42L, limits))
                .isInstanceOf(OperationalQueryPolicy.QueryRejectedException.class)
                .extracting("code").isEqualTo("ROLE_FORBIDDEN");
    }

    @Test
    void unknownFieldAndExcessiveLimitFailClosed() {
        var unknown = new QueryPlan(List.of(new DatasetQuery(
                Dataset.BOOKINGS, List.of("contactEmail"), List.of(),
                List.of(), List.of(), List.of(), List.of(), 20)));
        var excessive = new QueryPlan(List.of(new DatasetQuery(
                Dataset.BOOKINGS, List.of("pnr"), List.of(),
                List.of(), List.of(), List.of(), List.of(), 500)));

        assertThatThrownBy(() -> policy.validate(unknown, Role.ADMIN, 1L, limits))
                .extracting("code").isEqualTo("FIELD_NOT_ALLOWED");
        assertThatThrownBy(() -> policy.validate(excessive, Role.ADMIN, 1L, limits))
                .extracting("code").isEqualTo("BUDGET_EXCEEDED");
    }

    @Test
    void queryCountBudgetIsEnforced() {
        var query = new DatasetQuery(
                Dataset.AIRPORTS, List.of("code"), List.of(),
                List.of(), List.of(), List.of(), List.of(), 10);
        var plan = new QueryPlan(List.of(query, query, query, query));

        assertThatThrownBy(() -> policy.validate(plan, Role.ADMIN, 1L, limits))
                .extracting("code").isEqualTo("BUDGET_EXCEEDED");
    }
}
