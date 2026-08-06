package com.unitedair.ai.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unitedair.ai.commerce.RefundDtos;
import com.unitedair.ai.tools.BookingRepository;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;

class ActionServiceSeatChangeTest {

    @Test
    void paidSeatResultNamesTheAdditionalChargeAndUpdatedTotal() {
        Map<String, Object> result = ActionService.seatChangeResult(
                new BookingRepository.SeatChangeResult(
                        "8B", "12A", "WINDOW",
                        new BigDecimal("800.00"), new BigDecimal("4246.00")));

        assertThat(result)
                .containsEntry("previousSeat", "8B")
                .containsEntry("seatNumber", "12A")
                .containsEntry("additionalCharge", new BigDecimal("800.00"))
                .containsEntry("includedWithFare", false)
                .containsEntry("updatedAmountPaid", new BigDecimal("4246.00"));
        assertThat(String.valueOf(result.get("message")))
                .contains("INR 800")
                .contains("new booking total is INR 4246");
    }

    @Test
    void includedSeatResultExplainsThatNoChargeWasAdded() {
        Map<String, Object> result = ActionService.seatChangeResult(
                new BookingRepository.SeatChangeResult(
                        "2C", "2A", "WINDOW",
                        BigDecimal.ZERO, new BigDecimal("18450.00")));

        assertThat(result).containsEntry("includedWithFare", true);
        assertThat(String.valueOf(result.get("message")))
                .contains("included with this fare")
                .doesNotContain("INR 0");
    }

    @Test
    void cancellationResultStartsRefundTrackingWithoutPromisingACallback() {
        ToolDtos.RefundQuote quote = new ToolDtos.RefundQuote(
                "ABC123", "Value", true, 240, "More than 7 days",
                new BigDecimal("5000"), new BigDecimal("2000"),
                new BigDecimal("3000"), "5 to 7 working days", "Policy basis");
        Instant now = Instant.parse("2026-07-27T10:00:00Z");
        RefundDtos.RefundCaseView refundCase = new RefundDtos.RefundCaseView(
                UUID.fromString("30df6a3f-dfb3-4c0a-96d4-52da5a5822f9"),
                "ABC123", 7L, "Maya Singh", "UA101", "BLR", "DEL",
                LocalDate.parse("2026-08-10"), "Value", "Policy basis",
                new BigDecimal("5000"), new BigDecimal("2000"),
                new BigDecimal("3000"), "CARD", "**** 4242",
                now.plusSeconds(604800), RefundDtos.RefundStatus.PENDING,
                null, null, now, now, null, List.of());

        Map<String, Object> result = ActionService.cancellationResult(quote, refundCase);

        assertThat(result)
                .containsEntry("status", "REFUND_PENDING")
                .containsEntry("refundStatus", "PENDING")
                .containsEntry("refundCaseUuid", refundCase.caseUuid().toString());
        assertThat(String.valueOf(result.get("message")))
                .containsIgnoringCase("refund tracking case")
                .doesNotContainIgnoringCase("will call")
                .doesNotContainIgnoringCase("callback");
    }

    @Test
    void unchangedRefundQuoteCanBeConfirmed() {
        ToolDtos.RefundQuote current = new ToolDtos.RefundQuote(
                "ABC123", "Value", true, 240, "More than 7 days",
                new BigDecimal("5211"), new BigDecimal("4000"),
                new BigDecimal("1211"), "5 to 7 working days", "Policy basis");
        Map<String, Object> proposal = Map.of(
                "timingBand", "More than 7 days",
                "amountPaid", "5211.00",
                "cancellationFee", 4000,
                "estimatedRefund", new BigDecimal("1211"));

        ActionService.ensureRefundQuoteUnchanged(proposal, current);
    }

    @Test
    void changedRefundFeeIsRejectedBeforeMutation() {
        ToolDtos.RefundQuote current = new ToolDtos.RefundQuote(
                "ABC123", "Value", true, 48, "Within 3 days",
                new BigDecimal("5211"), new BigDecimal("4000"),
                new BigDecimal("1211"), "5 to 7 working days", "Policy basis");
        Map<String, Object> staleProposal = Map.of(
                "timingBand", "More than 7 days",
                "amountPaid", new BigDecimal("5211"),
                "cancellationFee", new BigDecimal("2000"),
                "estimatedRefund", new BigDecimal("3211"));

        assertThatThrownBy(() ->
                ActionService.ensureRefundQuoteUnchanged(staleProposal, current))
                .hasMessageContaining("quote changed")
                .hasMessageContaining("No change was made");
    }

    @Test
    void changedRescheduleFareIsRejectedBeforeMutation() {
        BookingRepository.RescheduleQuote current =
                new BookingRepository.RescheduleQuote(
                        20L, 30L, "UA202", "BLR", "DEL",
                        LocalDate.parse("2026-08-20"),
                        java.time.LocalTime.parse("10:15"),
                        "Y", "ECONOMY", "Value",
                        new BigDecimal("7200"), new BigDecimal("2200"),
                        new BigDecimal("1500"), new BigDecimal("3700"),
                        BigDecimal.ZERO);
        Map<String, Object> staleProposal = Map.of(
                "targetFare", new BigDecimal("7000"),
                "fareDifference", new BigDecimal("2000"),
                "changeFee", new BigDecimal("1500"),
                "totalDue", new BigDecimal("3500"));

        assertThatThrownBy(() ->
                ActionService.ensureRescheduleQuoteUnchanged(staleProposal, current))
                .hasMessageContaining("fare changed")
                .hasMessageContaining("No change was made");
    }
}
