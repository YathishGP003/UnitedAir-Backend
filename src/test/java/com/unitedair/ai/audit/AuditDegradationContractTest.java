package com.unitedair.ai.audit;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditDegradationContractTest {

    @Test
    void answerAndRestoredTurnContractsRetainTheProviderDegradationReason() {
        assertThat(Arrays.stream(AuditDtos.AnswerRecordRow.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("degradedReason");
        assertThat(Arrays.stream(AuditDtos.TurnTrail.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("degradedReason");
    }

    @Test
    void databaseMigrationAddsTheDegradationReasonColumn() {
        assertThat(getClass().getResource("/db/migration/V13__answer_degradation_reason.sql"))
                .isNotNull();
    }

    @Test
    void restoredTurnContractRetainsInteractiveCommercePayload() {
        assertThat(Arrays.stream(AuditDtos.TurnTrail.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("commerce");
        assertThat(getClass().getResource("/db/migration/V18__persist_chat_commerce.sql"))
                .isNotNull();
    }

    @Test
    void operationalAuditPayloadContainsOnlySafeMetadata() {
        Map<String, Object> payload = AuditService.operationalQueryPayload(
                "REFUND_CASES", "4bf1c2", 18L, 3, false, "HTTP_429");

        assertThat(payload)
                .containsEntry("dataset", "REFUND_CASES")
                .containsEntry("queryFingerprint", "4bf1c2")
                .containsEntry("durationMs", 18L)
                .containsEntry("rowCount", 3)
                .containsEntry("truncated", false)
                .containsEntry("degradationReason", "HTTP_429");
        assertThat(payload.keySet()).doesNotContain(
                "sql", "parameters", "rows", "pnr", "passengerName",
                "contactEmail", "providerError", "secret");
        assertThat(payload.toString()).doesNotContain("X2LTWZ", "SELECT", "Bearer");
    }

    @Test
    void semanticRoutePayloadExplainsContextWithoutRawConversationText() {
        Map<String, Object> payload = AuditService.semanticRoutePayload(
                "HOSTED_SEMANTIC", "IN_SCOPE", true,
                "REFERENTIAL_FOLLOW_UP", 0.91, null);

        assertThat(payload)
                .containsEntry("routeSource", "HOSTED_SEMANTIC")
                .containsEntry("scope", "IN_SCOPE")
                .containsEntry("historyUsed", true)
                .containsEntry("contextReason", "REFERENTIAL_FOLLOW_UP");
        assertThat(payload.keySet()).doesNotContain(
                "query", "history", "messages", "pnr", "rawPlan");
    }
}
