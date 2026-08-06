package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkAudienceResolverTest {

    @Test
    void narrowsExplicitInternalSectionsInMixedAudienceDocuments() {
        assertThat(ChunkAudienceResolver.resolve(
                List.of("Passenger", "Airline Staff"),
                "4 Yield Management Parameters (Airline Staff)",
                "Internal inventory thresholds"))
                .isEqualTo("Airline Staff");

        assertThat(ChunkAudienceResolver.resolve(
                List.of("Passenger", "Airline Staff"),
                "2.2 Booking Class Codes and Revenue Bands",
                "Internal fare controls"))
                .isEqualTo("Airline Staff");
    }

    @Test
    void keepsPassengerPolicySectionsAvailableToBothDeclaredAudiences() {
        assertThat(ChunkAudienceResolver.resolve(
                List.of("Passenger", "Airline Staff"),
                "3 Seat Selection",
                "Seat types and published selection fees"))
                .isEqualTo("Passenger,Airline Staff");

        assertThat(ChunkAudienceResolver.resolve(
                List.of("Passenger", "Airline Staff"),
                "5 Upgrades",
                "Published upgrade choices"))
                .isEqualTo("Passenger,Airline Staff");
    }

    @Test
    void neverBroadensAStaffOnlyDocument() {
        assertThat(ChunkAudienceResolver.resolve(
                List.of("Airline Staff"),
                "Passenger service",
                "A heading alone must not broaden access"))
                .isEqualTo("Airline Staff");
    }
}
