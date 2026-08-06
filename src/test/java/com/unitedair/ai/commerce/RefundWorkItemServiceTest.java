package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;

class RefundWorkItemServiceTest {

    private final RefundWorkItemRepository repository = mock(RefundWorkItemRepository.class);
    private final RefundWorkItemService service = new RefundWorkItemService(repository);

    @Test
    void cancellationCreatesAPendingCaseWithoutCreatingACallback() {
        ToolDtos.RefundQuote quote = new ToolDtos.RefundQuote(
                "ABC123", "Value", true, 240, "More than 7 days",
                new BigDecimal("5000"), new BigDecimal("2000"),
                new BigDecimal("3000"), "5 to 7 working days",
                "Value fare cancellation matrix");
        RefundDtos.RefundCaseView expected = caseView(RefundDtos.RefundStatus.PENDING);
        when(repository.createForCancellation(7L, "ABC123", quote)).thenReturn(expected);

        RefundDtos.RefundCaseView created =
                service.createForCancellation(7L, "ABC123", quote);

        assertThat(created.status()).isEqualTo(RefundDtos.RefundStatus.PENDING);
        assertThat(created.callbackStatus()).isNull();
        assertThat(created.refundAmount()).isEqualByComparingTo("3000");
    }

    @Test
    void pendingCaseCannotSkipDirectlyToCompleted() {
        UUID caseUuid = UUID.randomUUID();
        when(repository.findForStaff(caseUuid))
                .thenReturn(java.util.Optional.of(caseView(RefundDtos.RefundStatus.PENDING)));

        assertThatThrownBy(() -> service.transition(
                caseUuid, RefundDtos.RefundStatus.COMPLETED, "Settled", 3L))
                .isInstanceOf(ApiExceptions.Conflict.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void processingCaseCanCompleteAndReturnsItsHistory() {
        UUID caseUuid = UUID.randomUUID();
        RefundDtos.RefundCaseView processing =
                caseView(caseUuid, RefundDtos.RefundStatus.PROCESSING);
        RefundDtos.RefundCaseView completed =
                caseView(caseUuid, RefundDtos.RefundStatus.COMPLETED);
        when(repository.findForStaff(caseUuid))
                .thenReturn(java.util.Optional.of(processing));
        when(repository.transition(
                caseUuid, RefundDtos.RefundStatus.PROCESSING,
                RefundDtos.RefundStatus.COMPLETED, "Provider settled", 3L))
                .thenReturn(completed);

        RefundDtos.RefundCaseView result = service.transition(
                caseUuid, RefundDtos.RefundStatus.COMPLETED, "Provider settled", 3L);

        assertThat(result.status()).isEqualTo(RefundDtos.RefundStatus.COMPLETED);
        assertThat(result.history()).isNotEmpty();
        assertThat(result.history().getLast().toStatus())
                .isEqualTo(RefundDtos.RefundStatus.COMPLETED);
    }

    @Test
    void legalTransitionTableSupportsContactAndFailedRecovery() {
        assertThat(RefundWorkItemService.allowedTargets(RefundDtos.RefundStatus.PENDING))
                .containsExactlyInAnyOrder(
                        RefundDtos.RefundStatus.PROCESSING,
                        RefundDtos.RefundStatus.CONTACT_NEEDED,
                        RefundDtos.RefundStatus.FAILED);
        assertThat(RefundWorkItemService.allowedTargets(RefundDtos.RefundStatus.FAILED))
                .containsExactly(RefundDtos.RefundStatus.PROCESSING);
        assertThat(RefundWorkItemService.allowedTargets(RefundDtos.RefundStatus.COMPLETED))
                .isEmpty();
    }

    private static RefundDtos.RefundCaseView caseView(RefundDtos.RefundStatus status) {
        return caseView(UUID.randomUUID(), status);
    }

    private static RefundDtos.RefundCaseView caseView(
            UUID caseUuid, RefundDtos.RefundStatus status) {
        Instant now = Instant.parse("2026-07-27T10:00:00Z");
        return new RefundDtos.RefundCaseView(
                caseUuid, "ABC123", 7L, "Maya Singh",
                "UA101", "BLR", "DEL", LocalDate.parse("2026-08-10"),
                "Value", "Value fare cancellation matrix",
                new BigDecimal("5000"), new BigDecimal("2000"),
                new BigDecimal("3000"), "CARD", "**** 4242",
                now.plusSeconds(604800), status, null, null,
                now, now, status == RefundDtos.RefundStatus.COMPLETED ? now : null,
                List.of(new RefundDtos.RefundHistoryView(
                        null, status, "Created", null, now)));
    }
}
