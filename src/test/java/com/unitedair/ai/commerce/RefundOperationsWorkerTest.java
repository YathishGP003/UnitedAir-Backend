package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.providers.BookingProvider;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.tools.BookingAccess;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.tools.ToolInvocationLogger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class RefundOperationsWorkerTest {

    private final RefundWorkItemService refunds = mock(RefundWorkItemService.class);
    private final RefundOperationsWorker worker = new RefundOperationsWorker(
            refunds,
            new ToolInvocationLogger(mock(JdbcClient.class), new CurrentUser()));

    @Test
    void passengerCannotListRefundCases() {
        assertThatThrownBy(() -> worker.listCases(
                new RefundDtos.RefundCaseQuery(null, null, 50),
                BookingAccess.of(Role.PASSENGER, 42L)))
                .isInstanceOf(ApiExceptions.Forbidden.class);

        verifyNoInteractions(refunds);
    }

    @Test
    void passengerStatusUsesOwnershipScopedLookup() {
        RefundDtos.RefundCaseView refundCase = caseView(42L);
        when(refunds.statusForPassenger(42L, "B6X9K2")).thenReturn(refundCase);

        var outcome = worker.status(
                "B6X9K2",
                BookingAccess.of(Role.PASSENGER, 42L));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.data()).isInstanceOf(RefundDtos.RefundStatusView.class);
        RefundDtos.RefundStatusView status =
                (RefundDtos.RefundStatusView) outcome.data();
        assertThat(status.status()).isEqualTo("PROCESSING");
        assertThat(status.amount()).isEqualByComparingTo("2811");
        verify(refunds).statusForPassenger(42L, "B6X9K2");
    }

    @Test
    void staffCanListTheAuthorizedQueue() {
        RefundDtos.RefundCaseQuery query =
                new RefundDtos.RefundCaseQuery("PROCESSING", Instant.now(), 25);
        when(refunds.listForStaff(query)).thenReturn(List.of(caseView(42L)));

        var outcome = worker.listCases(
                query,
                BookingAccess.of(Role.AIRLINE_STAFF, 7L));

        assertThat(outcome.success()).isTrue();
        assertThat((List<?>) outcome.data()).hasSize(1);
        verify(refunds).listForStaff(query);
    }

    @Test
    void staffCanReadALegacySeededRefundStateWithoutAQueueWorkItem() {
        BookingProvider provider = mock(BookingProvider.class);
        ToolDtos.BookingView booking = mock(ToolDtos.BookingView.class);
        when(refunds.statusForStaff("K2MN7V"))
                .thenThrow(new ApiExceptions.NotFound(
                        "No refund case was found for that booking."));
        BookingAccess access = BookingAccess.of(Role.AIRLINE_STAFF, 7L);
        when(provider.retrieve("K2MN7V", access))
                .thenReturn(Optional.of(booking));
        when(booking.pnr()).thenReturn("K2MN7V");
        when(booking.status()).thenReturn("REFUND_PENDING");
        when(booking.refundAmount()).thenReturn(new BigDecimal("2811"));
        when(booking.retrievedAt()).thenReturn(
                Instant.parse("2026-07-27T10:00:00Z"));
        RefundOperationsWorker legacyWorker = new RefundOperationsWorker(
                refunds,
                new ToolInvocationLogger(mock(JdbcClient.class), new CurrentUser()),
                provider);

        ToolDtos.ToolOutcome outcome = legacyWorker.status("K2MN7V", access);

        assertThat(outcome.success()).isTrue();
        RefundDtos.RefundStatusView status =
                (RefundDtos.RefundStatusView) outcome.data();
        assertThat(status.status()).isEqualTo("REFUND_PENDING");
        assertThat(status.amount()).isEqualByComparingTo("2811");
    }

    private static RefundDtos.RefundCaseView caseView(long passengerId) {
        Instant now = Instant.parse("2026-07-27T10:00:00Z");
        return new RefundDtos.RefundCaseView(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "B6X9K2",
                passengerId,
                "Ananya Rao",
                "UA101",
                "BLR",
                "DEL",
                LocalDate.of(2026, 8, 2),
                "Value",
                "KB-AIR-004",
                new BigDecimal("4811"),
                new BigDecimal("2000"),
                new BigDecimal("2811"),
                "CARD",
                "**** 4242",
                now.plusSeconds(604800),
                RefundDtos.RefundStatus.PROCESSING,
                null,
                null,
                now,
                now,
                null,
                List.of());
    }
}
