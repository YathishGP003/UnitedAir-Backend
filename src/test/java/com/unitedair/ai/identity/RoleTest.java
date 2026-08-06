package com.unitedair.ai.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SRS 2.3 and 4.3.2 - the audience policy that retrieval pushes into SQL. */
class RoleTest {

    @Test
    @DisplayName("a Passenger may never read staff or admin material")
    void passengerAudienceIsNarrow() {
        // This list becomes the WHERE clause of both retrieval lanes. If "Airline Staff"
        // ever appears here, every staff-only document becomes retrievable by passengers.
        assertThat(Role.PASSENGER.readableAudiences())
                .containsExactlyInAnyOrder("Passenger", "All")
                .doesNotContain("Airline Staff", "Admin");
    }

    @Test
    void staffMayReadPassengerAndStaffMaterial() {
        assertThat(Role.AIRLINE_STAFF.readableAudiences())
                .contains("Passenger", "Airline Staff", "All")
                .doesNotContain("Admin");
    }

    @Test
    void adminMayReadEverything() {
        assertThat(Role.ADMIN.readableAudiences())
                .contains("Passenger", "Airline Staff", "Admin", "All");
    }

    @Test
    void rankIsCumulative() {
        assertThat(Role.ADMIN.atLeast(Role.AIRLINE_STAFF)).isTrue();
        assertThat(Role.AIRLINE_STAFF.atLeast(Role.PASSENGER)).isTrue();
        assertThat(Role.PASSENGER.atLeast(Role.AIRLINE_STAFF)).isFalse();
    }

    @Test
    @DisplayName("role names are parsed from every form the KB and JWT use")
    void parsesVariantSpellings() {
        assertThat(Role.fromString("AIRLINE_STAFF")).isEqualTo(Role.AIRLINE_STAFF);
        assertThat(Role.fromString("Airline Staff")).isEqualTo(Role.AIRLINE_STAFF);
        assertThat(Role.fromString("staff")).isEqualTo(Role.AIRLINE_STAFF);
        assertThat(Role.fromString("ROLE_ADMIN")).isEqualTo(Role.ADMIN);
        assertThat(Role.fromString("Passenger")).isEqualTo(Role.PASSENGER);
    }

    @Test
    @DisplayName("an unknown or missing role falls back to the least privileged")
    void defaultsToLeastPrivilege() {
        assertThat(Role.fromString(null)).isEqualTo(Role.PASSENGER);
        assertThat(Role.fromString("")).isEqualTo(Role.PASSENGER);
        assertThat(Role.fromString("something-else")).isEqualTo(Role.PASSENGER);
    }
}
