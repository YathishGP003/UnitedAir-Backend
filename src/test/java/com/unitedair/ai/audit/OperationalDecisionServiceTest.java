package com.unitedair.ai.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Set;

import com.unitedair.ai.shared.ApiExceptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class OperationalDecisionServiceTest {

    @Test
    void pnrIsStoredAndQueriedOnlyByStableHash() {
        String first = OperationalDecisionService.hashPnr("xbhjdm");
        String second = OperationalDecisionService.hashPnr(" XBHJDM ");

        assertThat(first)
                .isEqualTo(second)
                .hasSize(64)
                .doesNotContain("XBHJDM");
    }

    @Test
    void invalidDateRangeFailsBeforeDatabaseAccess() {
        OperationalDecisionService service =
                new OperationalDecisionService(mock(JdbcClient.class));
        var query = new OperationalDecisionDtos.DecisionQuery(
                Set.of(OperationalDecisionDtos.DecisionType.REFUND_APPROVAL),
                null, null,
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-27T00:00:00Z"),
                null);

        assertThatThrownBy(() -> service.search(query))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("from");
    }

    @Test
    void decisionDetailNeverStoresRawPnrOrPassengerName() {
        var safe = OperationalDecisionService.redactDetail(
                java.util.Map.of(
                        "action", "Cancel booking XBHJDM",
                        "passenger", "Ananya Rao"),
                "XBHJDM");

        assertThat(safe)
                .containsEntry("action", "Cancel booking [AIR-PNR-REDACTED]")
                .containsEntry("passenger", "[PERSON-REDACTED]");
        assertThat(safe.toString()).doesNotContain("XBHJDM", "Ananya Rao");
    }
}
