package com.unitedair.ai.providers.simulator;

import java.util.Optional;

import com.unitedair.ai.providers.BookingProvider;
import com.unitedair.ai.providers.CheckInProvider;
import com.unitedair.ai.providers.ProviderCapability;
import com.unitedair.ai.tools.BookingAccess;
import com.unitedair.ai.tools.BookingRepository;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "unitedair.providers",
        name = "mode",
        havingValue = "simulator",
        matchIfMissing = true)
public class SimulatorBookingProvider implements BookingProvider, CheckInProvider {

    private final BookingRepository bookings;

    public SimulatorBookingProvider(BookingRepository bookings) {
        this.bookings = bookings;
    }

    @Override
    public Optional<ToolDtos.BookingView> retrieve(
            String pnr, BookingAccess access) {
        return bookings.findByPnr(pnr, access);
    }

    @Override
    public ToolDtos.RefundQuote quoteRefund(ToolDtos.BookingView booking) {
        return bookings.quoteRefund(booking);
    }

    @Override
    public BookingRepository.RescheduleQuote quoteReschedule(
            ToolDtos.BookingView booking,
            Long targetFlightInstanceId,
            String targetFareClass) {
        return bookings.quoteReschedule(
                booking, targetFlightInstanceId, targetFareClass);
    }

    @Override
    public ProviderActionResult confirm(
            ProviderActionCommand command, BookingAccess access) {
        throw new UnsupportedOperationException(
                "Simulator confirmations remain behind authenticated ActionService endpoints.");
    }

    @Override
    public ToolDtos.CheckInEligibility eligibility(ToolDtos.BookingView booking) {
        return bookings.checkInEligibility(booking);
    }

    @Override
    public ProviderCapability capability() {
        return ProviderCapability.simulator();
    }
}
