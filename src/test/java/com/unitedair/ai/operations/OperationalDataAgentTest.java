package com.unitedair.ai.operations;

import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.llm.ChatGateway;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.unitedair.ai.operations.OperationalQueryDtos.Dataset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationalDataAgentTest {

    private final ChatGateway gateway = mock(ChatGateway.class);
    private final OperationalQueryExecutor executor = mock(OperationalQueryExecutor.class);
    private final AuditService audit = mock(AuditService.class);
    private final SemanticDatasetCatalog catalog = new SemanticDatasetCatalog();
    private final OperationalDataAgent agent = new OperationalDataAgent(
            gateway,
            catalog,
            new OperationalQueryPolicy(catalog),
            new OperationalQueryCompiler(catalog),
            executor,
            new UnitedAirProperties(),
            audit);

    @Test
    void executesStrictHostedPlanAndRestoresTrustedPnrAfterPlanning() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(new ChatDtos.ChatResult("""
                        {"queries":[{"dataset":"REFUND_CASES",
                         "select":["caseReference","pnr","status","completedAt"],
                         "filters":[],"joins":[],"aggregates":[],"groupBy":[],
                         "sort":[],"limit":20}]}
                        """, 10, 10, "model", true, null));
        when(executor.execute(any())).thenAnswer(invocation -> {
            var query = (OperationalQueryDtos.CompiledQuery) invocation.getArgument(0);
            return new OperationalQueryDtos.OperationalDataResult(
                    Dataset.REFUND_CASES,
                    List.of(Map.of("pnr", "X2LTWZ", "status", "COMPLETED")),
                    Map.of(), Instant.parse("2026-07-28T04:00:00Z"),
                    1, false, query.fingerprint());
        });

        var result = agent.answer(new OperationalDataAgent.AgentRequest(
                "status of refund for [AIR-PNR-REDACTED]",
                Role.PASSENGER, 1L, "X2LTWZ", 3));

        assertThat(result.status()).isEqualTo(OperationalDataAgent.Status.EXECUTED);
        assertThat(result.results()).singleElement()
                .satisfies(data -> assertThat(data.rows().getFirst())
                        .containsEntry("status", "COMPLETED"));
        verify(executor).execute(any());
    }

    @Test
    void invalidHostedPlanNeverExecutes() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(new ChatDtos.ChatResult("""
                        {"queries":[{"dataset":"BOOKINGS","select":["contactEmail"],
                         "filters":[],"joins":[],"aggregates":[],"groupBy":[],
                         "sort":[],"limit":20}]}
                        """, 10, 10, "model", true, null));

        var result = agent.answer(new OperationalDataAgent.AgentRequest(
                "show contact emails", Role.ADMIN, 3L, null, 3));

        assertThat(result.status()).isEqualTo(OperationalDataAgent.Status.REJECTED);
        assertThat(result.degradedReason()).isEqualTo("FIELD_NOT_ALLOWED");
        verify(executor, never()).execute(any());
    }

    @Test
    void hostedOutageUsesSafeExactRefundStatusTemplate() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(new ChatDtos.ChatResult(
                        "", null, null, "model", false, "HTTP_429"));
        when(executor.execute(any())).thenReturn(
                new OperationalQueryDtos.OperationalDataResult(
                        Dataset.REFUND_CASES, List.of(), Map.of(), Instant.now(),
                        0, false, "fingerprint"));

        var result = agent.answer(new OperationalDataAgent.AgentRequest(
                "refund status for [AIR-PNR-REDACTED]",
                Role.PASSENGER, 1L, "X2LTWZ", 3));

        assertThat(result.status()).isEqualTo(OperationalDataAgent.Status.EXECUTED);
        assertThat(result.degradedReason()).isEqualTo("HTTP_429");
        verify(executor).execute(any());
    }

    @Test
    void malformedHostedPlanUsesSafeExactStaffOperationsTemplate() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(new ChatDtos.ChatResult(
                        """
                        {"queries":[{"dataset":"ESCALATIONS",
                         "select":["caseReference","status"]}]}
                        """,
                        10, 10, "model", true, null));
        when(executor.execute(any())).thenAnswer(invocation -> {
            var query = (OperationalQueryDtos.CompiledQuery) invocation.getArgument(0);
            return new OperationalQueryDtos.OperationalDataResult(
                    query.dataset(), List.of(), Map.of(), Instant.now(),
                    0, false, query.fingerprint());
        });

        var result = agent.answer(new OperationalDataAgent.AgentRequest(
                "Which pending refunds and open escalations need attention?",
                Role.AIRLINE_STAFF, 2L, null, 3));

        assertThat(result.status()).isEqualTo(OperationalDataAgent.Status.EXECUTED);
        assertThat(result.degradedReason()).isEqualTo("INVALID_SEMANTIC_PLAN");
        assertThat(result.results())
                .extracting(OperationalQueryDtos.OperationalDataResult::dataset)
                .containsExactly(Dataset.REFUND_CASES, Dataset.ESCALATIONS);
    }
}
