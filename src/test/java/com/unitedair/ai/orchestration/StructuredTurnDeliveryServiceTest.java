package com.unitedair.ai.orchestration;

import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.actions.ActionService;
import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.conversation.ChatMemoryStore;
import com.unitedair.ai.conversation.SessionService;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.AiMode;
import com.unitedair.ai.privacy.PiiRedactor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredTurnDeliveryServiceTest {

    private final SessionService sessions = mock(SessionService.class);
    private final ChatMemoryStore memory = mock(ChatMemoryStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final ActionService actions = mock(ActionService.class);
    private final StructuredTurnDeliveryService service =
            new StructuredTurnDeliveryService(
                    sessions,
                    memory,
                    new PiiRedactor(),
                    audit,
                    actions,
                    AiMode.LIVE);

    @Test
    void confirmationSettlesOnlyTheTrustedPendingAction() {
        ActionDtos.ActionView pending = action(
                "action-1", "PENDING", null);
        ActionDtos.ActionView confirmed = action(
                "action-1",
                "CONFIRMED",
                Map.of("message", "Booking cancelled; refund is pending."));
        when(actions.pendingFor("session-1")).thenReturn(List.of(pending));
        when(actions.confirm("action-1")).thenReturn(confirmed);

        var answer = service.settlePendingAction(
                ValidatedRoute.ActionDecision.CONFIRM,
                "session-1",
                Role.PASSENGER,
                7L,
                "yes, cancel this booking",
                "trace-1",
                System.nanoTime(),
                event -> { });

        verify(actions).confirm("action-1");
        assertThat(answer.proposedAction().status()).isEqualTo("CONFIRMED");
        assertThat(answer.answer())
                .contains("Booking cancelled", "refund is pending");
        assertThat(answer.toolsUsed())
                .containsExactly("BookingManagementTool");
    }

    private static ActionDtos.ActionView action(
            String id,
            String status,
            Map<String, Object> result) {
        return new ActionDtos.ActionView(
                id,
                "CANCEL_BOOKING",
                status,
                "N7QTX2",
                Map.of("flight", "UA101 BLR-DEL"),
                List.of(Map.of(
                        "documentCode", "KB-AIR-004",
                        "section", "2.1 Cancellation Fee Matrix by Fare Type")),
                result,
                Instant.parse("2026-07-28T10:00:00Z"),
                Instant.parse("2026-07-28T10:10:00Z"),
                null);
    }
}
