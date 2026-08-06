package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;

class FollowUpServiceTest {

    private final FollowUpService service = new FollowUpService();

    @Test
    void passengerToolFollowupsAreRoleAwareAndRetainTheToolHandle() {
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME,
                new ToolDtos.FlightSearchResult(
                        "BLR", "DEL", java.time.LocalDate.parse("2026-08-10"),
                        "ECONOMY", 0, List.of(), Instant.now()),
                "No flights on this date.",
                5,
                Instant.now(),
                Map.of());

        var suggestions = service.suggest(
                Role.PASSENGER, List.of(), List.of(outcome), false);

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions)
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.text()).startsWith("As a Passenger");
                    assertThat(suggestion.sourceCitationHandles()).containsExactly("T1");
                });
    }

    @Test
    void staffPolicyFollowupsNameTheRoleAndSourceEvidence() {
        RetrievalDtos.Chunk chunk = new RetrievalDtos.Chunk(
                1L, "chunk-1", "KB-AIR-007", "Operations",
                "4.2 Approval Threshold", 1, "TXT", "sop",
                "Airline Staff", "Approval threshold details.", 0.9, 1.0);

        var suggestions = service.suggest(
                Role.AIRLINE_STAFF,
                List.of(new RetrievalDtos.Ranked(chunk, 0.9, 0.9, 1)),
                List.of(),
                false);

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.getFirst().text()).startsWith("As Airline Staff");
        assertThat(suggestions.getFirst().sourceCitationHandles()).containsExactly("E1");
    }

    @Test
    void escalatedFollowupsOnlyOfferEscalationChannels() {
        var suggestions = service.suggest(
                Role.PASSENGER, List.of(), List.of(), true);

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions)
                .allMatch(suggestion ->
                        suggestion.kind() == FollowUpService.FollowUpKind.ESCALATION_CHANNEL);
    }

    @Test
    void operationalDataResultsGetTwoVerifiedNextSteps() {
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                "OperationalDataAgent",
                "verified rows",
                "2 verified records.",
                4,
                Instant.now(),
                Map.of("dataset", "REFUND_CASES"));

        var suggestions = service.suggest(
                Role.AIRLINE_STAFF, List.of(), List.of(outcome), false);

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions)
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.text()).startsWith("As Airline Staff");
                    assertThat(suggestion.sourceCitationHandles()).containsExactly("T1");
                });
    }
}
