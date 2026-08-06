package com.unitedair.ai.providers;

import java.util.Optional;

import com.unitedair.ai.tools.BookingAccess;
import com.unitedair.ai.tools.BookingRepository;
import com.unitedair.ai.tools.ToolDtos;

public interface BookingProvider {

    Optional<ToolDtos.BookingView> retrieve(String pnr, BookingAccess access);

    ToolDtos.RefundQuote quoteRefund(ToolDtos.BookingView booking);

    BookingRepository.RescheduleQuote quoteReschedule(
            ToolDtos.BookingView booking,
            Long targetFlightInstanceId,
            String targetFareClass);

    ProviderActionResult confirm(
            ProviderActionCommand command,
            BookingAccess access);

    ProviderCapability capability();

    record ProviderActionCommand(
            String type,
            String pnr,
            String idempotencyKey,
            java.util.Map<String, Object> parameters) { }

    record ProviderActionResult(
            String actionId,
            String status,
            String message,
            Object data) { }
}
