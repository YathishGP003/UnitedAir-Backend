package com.unitedair.ai.operations;

import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static com.unitedair.ai.operations.OperationalQueryDtos.Dataset.*;
import static org.assertj.core.api.Assertions.assertThat;

class SemanticDatasetCatalogTest {

    private final SemanticDatasetCatalog catalog = new SemanticDatasetCatalog();

    @Test
    void exposesEveryApprovedOperationalDataset() {
        assertThat(catalog.datasets()).containsExactlyInAnyOrderElementsOf(
                EnumSet.of(
                        AIRPORTS, ROUTES, FLIGHT_SCHEDULES, FLIGHT_INSTANCES,
                        FLIGHT_INVENTORY, SEAT_INVENTORY, BOOKINGS,
                        CHECK_IN_STATE, MEAL_AVAILABILITY, SPECIAL_SERVICES,
                        PAYMENT_STATUS, REFUND_CASES, REFUND_HISTORY,
                        ESCALATIONS, OPERATIONAL_DECISIONS, AUDIT_EVENTS));
    }

    @Test
    void everyDatasetIsBoundedAndMappedToAnApprovedView() {
        for (var dataset : catalog.datasets()) {
            var descriptor = catalog.descriptor(dataset);
            assertThat(descriptor.viewName()).startsWith("v_ai_");
            assertThat(descriptor.fields()).isNotEmpty();
            assertThat(descriptor.roles()).isNotEmpty();
            assertThat(descriptor.defaultLimit()).isBetween(1, descriptor.maxLimit());
            assertThat(descriptor.maxLimit()).isLessThanOrEqualTo(50);
            assertThat(descriptor.timeoutMs()).isBetween(100, 2_000);
        }
    }

    @Test
    void passengerRecordDatasetsDeclareOwnershipColumn() {
        assertThat(catalog.descriptor(BOOKINGS).ownershipColumn())
                .isEqualTo("owner_user_id");
        assertThat(catalog.descriptor(CHECK_IN_STATE).ownershipColumn())
                .isEqualTo("owner_user_id");
        assertThat(catalog.descriptor(PAYMENT_STATUS).ownershipColumn())
                .isEqualTo("owner_user_id");
        assertThat(catalog.descriptor(REFUND_CASES).ownershipColumn())
                .isEqualTo("owner_user_id");
        assertThat(catalog.descriptor(REFUND_HISTORY).ownershipColumn())
                .isEqualTo("owner_user_id");
    }

    @Test
    void internalQueuesAreNotPassengerReadable() {
        assertThat(catalog.descriptor(ESCALATIONS).roles())
                .containsExactlyInAnyOrder(Role.AIRLINE_STAFF, Role.ADMIN);
        assertThat(catalog.descriptor(AUDIT_EVENTS).roles())
                .containsExactly(Role.ADMIN);
    }
}
