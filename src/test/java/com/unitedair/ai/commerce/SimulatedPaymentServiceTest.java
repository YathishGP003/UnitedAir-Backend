package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import org.junit.jupiter.api.Test;

class SimulatedPaymentServiceTest {

    @Test
    void authorizesKnownDemoCardAndPersistsOnlyMaskedAccount() {
        PaymentRepository repository = mock(PaymentRepository.class);
        UUID draft = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        when(repository.findByIdempotency(7L, "pay-once")).thenReturn(Optional.empty());
        when(repository.create(eq(7L), eq(draft), eq("CARD"), eq("AUTHORIZED"),
                eq("•••• 4242"), any(), eq(new BigDecimal("3446.00")), eq("pay-once")))
                .thenReturn(new CommerceDtos.PaymentView(
                        payment, CommerceDtos.PaymentStatus.AUTHORIZED, "CARD",
                        "•••• 4242", "SIM-123", new BigDecimal("3446.00"),
                        Instant.now(), "Payment authorized."));

        SimulatedPaymentService tool = new SimulatedPaymentService(repository);
        CommerceDtos.PaymentView result = tool.authorize(7L,
                new CommerceDtos.PaymentRequest(
                        draft, "card", "4242424242424242", "123", null,
                        new BigDecimal("3446.00"), "pay-once"));

        assertThat(result.status()).isEqualTo(CommerceDtos.PaymentStatus.AUTHORIZED);
        assertThat(result.maskedAccount()).isEqualTo("•••• 4242");
        verify(repository).create(eq(7L), eq(draft), eq("CARD"), eq("AUTHORIZED"),
                eq("•••• 4242"), any(), eq(new BigDecimal("3446.00")), eq("pay-once"));
    }

    @Test
    void declinesKnownDemoCardWithoutExposingCardOrCvv() {
        PaymentRepository repository = mock(PaymentRepository.class);
        UUID draft = UUID.randomUUID();
        when(repository.findByIdempotency(7L, "decline-once")).thenReturn(Optional.empty());
        when(repository.create(eq(7L), eq(draft), eq("CARD"), eq("DECLINED"),
                eq("•••• 0002"), any(), eq(new BigDecimal("3446.00")), eq("decline-once")))
                .thenAnswer(invocation -> new CommerceDtos.PaymentView(
                        UUID.randomUUID(), CommerceDtos.PaymentStatus.DECLINED, "CARD",
                        invocation.getArgument(4), null, invocation.getArgument(6),
                        Instant.now(), "Payment declined by the simulator."));

        CommerceDtos.PaymentView result = new SimulatedPaymentService(repository).authorize(7L,
                new CommerceDtos.PaymentRequest(
                        draft, "CARD", "4000000000000002", "999", null,
                        new BigDecimal("3446.00"), "decline-once"));

        assertThat(result.status()).isEqualTo(CommerceDtos.PaymentStatus.DECLINED);
        assertThat(result.toString()).doesNotContain("4000000000000002", "999");
    }

    @Test
    void rejectsUnknownPaymentMethod() {
        SimulatedPaymentService tool = new SimulatedPaymentService(mock(PaymentRepository.class));
        assertThatThrownBy(() -> tool.authorize(7L,
                new CommerceDtos.PaymentRequest(
                        UUID.randomUUID(), "CASH", null, null, null,
                        BigDecimal.TEN, "cash")))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("CARD or UPI");
    }
}
