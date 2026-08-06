package com.unitedair.ai.commerce;

import java.util.Locale;

import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.stereotype.Service;

@Service
public class CallbackService {

    private final CallbackRepository repository;

    public CallbackService(CallbackRepository repository) {
        this.repository = repository;
    }

    /**
     * Requests human help only. This method intentionally has no dependency on
     * booking mutation services, so "callback pending" can never mean "cancelled".
     */
    public CommerceDtos.CallbackView request(
            long userId, String pnr, String channel) {
        String normalizedPnr = pnr == null
                ? "" : pnr.trim().toUpperCase(Locale.ROOT);
        if (!normalizedPnr.matches("[A-Z0-9]{6}")) {
            throw new ApiExceptions.BadRequest("Enter a valid six-character PNR.");
        }
        String normalizedChannel = channel == null
                ? "PHONE" : channel.trim().toUpperCase(Locale.ROOT);
        if (!normalizedChannel.equals("PHONE") && !normalizedChannel.equals("EMAIL")) {
            throw new ApiExceptions.BadRequest("Callback channel must be PHONE or EMAIL.");
        }
        return repository.createOwned(userId, normalizedPnr, normalizedChannel);
    }
}
