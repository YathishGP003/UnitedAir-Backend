package com.unitedair.ai.commerce;

import java.util.List;
import java.util.Locale;

import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.stereotype.Service;

@Service
public class SeatInventoryWorker {

    private final SeatInventoryRepository repository;

    public SeatInventoryWorker(SeatInventoryRepository repository) {
        this.repository = repository;
    }

    public List<ToolDtos.SeatOption> available(long flightInstanceId, String cabin) {
        String normalizedCabin = cabin(cabin);
        return repository.seatMap(flightInstanceId).stream()
                .filter(ToolDtos.SeatOption::available)
                .filter(seat -> normalizedCabin.equalsIgnoreCase(seat.cabin()))
                .toList();
    }

    public ToolDtos.SeatOption claim(long flightInstanceId, String seatNumber, String cabin) {
        String normalizedSeat = seat(seatNumber);
        String normalizedCabin = cabin(cabin);
        ToolDtos.SeatOption selected = repository.seatMap(flightInstanceId).stream()
                .filter(option -> option.seatNumber().equalsIgnoreCase(normalizedSeat))
                .filter(option -> option.cabin().equalsIgnoreCase(normalizedCabin))
                .findFirst()
                .orElseThrow(() -> new ApiExceptions.BadRequest(
                        "That seat does not exist in the selected cabin."));
        if (!selected.available()
                || !repository.claim(flightInstanceId, normalizedSeat, normalizedCabin)) {
            throw new ApiExceptions.Conflict(
                    "That seat is no longer available. Choose another seat.");
        }
        return selected;
    }

    public void release(long flightInstanceId, String seatNumber) {
        repository.release(flightInstanceId, seat(seatNumber));
    }

    private static String seat(String value) {
        if (value == null || !value.trim().toUpperCase(Locale.ROOT).matches("\\d{1,2}[A-F]")) {
            throw new ApiExceptions.BadRequest("Choose a valid seat such as 12A.");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String cabin(String value) {
        if (value == null || value.isBlank()) {
            return "ECONOMY";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!List.of("ECONOMY", "PREMIUM_ECONOMY", "BUSINESS", "FIRST")
                .contains(normalized)) {
            throw new ApiExceptions.BadRequest("Unsupported cabin: " + value);
        }
        return normalized;
    }
}
