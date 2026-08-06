package com.unitedair.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class BookingRepositoryTest {

    @Test
    void refundQuoteEnforcesPaidMinusFeeEqualsRefund() {
        assertThatThrownBy(() -> new ToolDtos.RefundQuote(
                "ABC123", "Value", true, 48, "Within 3 days",
                new BigDecimal("5211"), new BigDecimal("2000"),
                new BigDecimal("1211"), "5 to 7 working days", "KB-AIR-004"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arithmetic");

        var quote = new ToolDtos.RefundQuote(
                "ABC123", "Value", true, 48, "Within 3 days",
                new BigDecimal("5211"), new BigDecimal("4000"),
                new BigDecimal("1211"), "5 to 7 working days", "KB-AIR-004");

        assertThat(quote.amountPaid()
                .subtract(quote.appliedCancellationFee()))
                .isEqualByComparingTo(quote.estimatedRefund());
    }

    @Test
    void rescheduleChargesChangeFeeAndOnlyPositiveFareDifference() {
        var higher = BookingRepository.calculateRescheduleAmounts(
                new BigDecimal("5000"), new BigDecimal("1500"), new BigDecimal("7200"));
        var lower = BookingRepository.calculateRescheduleAmounts(
                new BigDecimal("5000"), new BigDecimal("750"), new BigDecimal("4200"));

        assertThat(higher.fareDifference()).isEqualByComparingTo("2200");
        assertThat(higher.totalDue()).isEqualByComparingTo("3700");
        assertThat(higher.credit()).isZero();
        assertThat(lower.totalDue()).isEqualByComparingTo("750");
        assertThat(lower.credit()).isEqualByComparingTo("800");
    }

    @Test
    void paidSeatAddsItsVerifiedFeeToTheExistingBookingTotal() {
        var amount = BookingRepository.calculateSeatChangeAmount(
                new BigDecimal("3446.00"), new BigDecimal("800.00"));

        assertThat(amount.additionalCharge()).isEqualByComparingTo("800.00");
        assertThat(amount.updatedAmountPaid()).isEqualByComparingTo("4246.00");
    }

    @Test
    void includedBusinessSeatLeavesTheBookingTotalUnchanged() {
        var amount = BookingRepository.calculateSeatChangeAmount(
                new BigDecimal("18450.00"), BigDecimal.ZERO);

        assertThat(amount.additionalCharge()).isZero();
        assertThat(amount.updatedAmountPaid()).isEqualByComparingTo("18450.00");
    }
}
