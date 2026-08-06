package com.unitedair.ai.operations;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.unitedair.ai.operations.OperationalQueryDtos.Dataset.*;
import static org.assertj.core.api.Assertions.assertThat;

class OperationalAnswerComposerTest {

    private final OperationalAnswerComposer composer = new OperationalAnswerComposer();

    @Test
    void completedRefundIsRenderedAsCompletedNotEstimated() {
        var refund = result(REFUND_CASES, Map.of(
                "pnr", "X2LTWZ",
                "status", "COMPLETED",
                "refundAmountInr", 1211,
                "completedAt", "2026-07-28T03:50:49Z"));

        String answer = composer.compose(List.of(refund));

        assertThat(answer)
                .contains("Refund X2LTWZ")
                .contains("completed")
                .contains("INR 1,211")
                .contains("2026-07-28T03:50:49Z")
                .doesNotContainIgnoringCase("estimated");
    }

    @Test
    void multipleDatasetsProduceSeparateVerifiedSections() {
        var refunds = result(REFUND_CASES, Map.of(
                "caseReference", "REF-1", "status", "PENDING", "dueAt", "2026-08-01"));
        var escalations = result(ESCALATIONS, Map.of(
                "caseReference", "ESC-1", "priority", "HIGH", "status", "OPEN"));

        String answer = composer.compose(List.of(refunds, escalations));

        assertThat(answer)
                .contains("Refund cases")
                .contains("Escalations")
                .contains("REF-1")
                .contains("ESC-1");
    }

    private static OperationalQueryDtos.OperationalDataResult result(
            OperationalQueryDtos.Dataset dataset,
            Map<String, Object> row) {
        return new OperationalQueryDtos.OperationalDataResult(
                dataset, List.of(row), Map.of(),
                Instant.parse("2026-07-28T04:00:00Z"),
                1, false, "fingerprint");
    }
}
