package com.unitedair.ai.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.orchestration.OrchestrationDtos;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SessionServiceTest {

    @Test
    void pendingSlotRetainsTheOperationThatRequestedIt() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        SessionService service = new SessionService(
                jdbc,
                mock(ChatMemoryStore.class),
                mock(AuditService.class),
                mock(SessionBookingContext.class),
                new UnitedAirProperties());

        service.rememberPendingSlot(
                "session-1",
                "pnr",
                OrchestrationDtos.ToolTarget.REFUND_QUOTE);

        verify(jdbc.sql(contains("pending_operation_name = :operation"))
                .param("name", "pnr")
                .param("operation", "REFUND_QUOTE")
                .param("session", "session-1"))
                .update();
    }

    @Test
    void inactivityBoundaryExpiresAndClearsOnlySelectedSessions() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        ChatMemoryStore memory = mock(ChatMemoryStore.class);
        SessionBookingContext bookingContext = mock(SessionBookingContext.class);
        Instant cutoff = Instant.parse("2026-07-27T10:00:00Z");
        when(jdbc.sql(contains("last_activity_at <= :inactiveBefore"))
                .param("inactiveBefore", Timestamp.from(cutoff))
                .query(String.class)
                .list())
                .thenReturn(List.of("session-30-minutes"));

        SessionService service = new SessionService(
                jdbc,
                memory,
                mock(AuditService.class),
                bookingContext,
                new UnitedAirProperties());

        assertThat(service.expireInactiveSessions(cutoff)).isEqualTo(1);
        verify(memory).clear("session-30-minutes");
        verify(bookingContext).clear("session-30-minutes");
    }

    @Test
    void logoutClearsModelMemoryButPreservesConversationHistoryForOneUser() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        ChatMemoryStore memory = mock(ChatMemoryStore.class);
        SessionBookingContext bookingContext = mock(SessionBookingContext.class);
        SessionService service = new SessionService(
                jdbc,
                memory,
                mock(AuditService.class),
                bookingContext,
                new UnitedAirProperties());

        service.clearActiveMemoryForUser(42L);

        verify(memory).clearActiveMemoryForUser(42L);
        verify(bookingContext).clearForUser(42L);
        verify(jdbc, never()).sql(contains("expired = TRUE"));
        verify(jdbc.sql(contains("pending_slot_name = NULL"))
                .param("userId", 42L))
                .update();
    }
}
