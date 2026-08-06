package com.unitedair.ai.commerce;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.stereotype.Service;

@Service
public class BookingDraftService {

    private final BookingDraftRepository repository;

    public BookingDraftService(BookingDraftRepository repository) {
        this.repository = repository;
    }

    public CommerceDtos.BookingDraftView startOrResume(long userId, String sessionUuid) {
        return repository.findActiveBySession(userId, sessionUuid)
                .orElseGet(() -> repository.create(userId, sessionUuid));
    }

    public Optional<CommerceDtos.BookingDraftView> findActive(
            long userId, String sessionUuid) {
        return repository.findActiveBySession(userId, sessionUuid);
    }

    public CommerceDtos.BookingDraftView requireOwned(long userId, UUID uuid) {
        CommerceDtos.BookingDraftView draft = repository.findOwned(uuid, userId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Booking draft not found."));
        if (draft.expiresAt().isBefore(Instant.now())) {
            throw new ApiExceptions.Conflict("This booking draft expired. Start a new search.");
        }
        return draft;
    }

    public CommerceDtos.BookingDraftView applySlots(
            long userId,
            UUID uuid,
            CommerceDtos.DraftPatch patch,
            int expectedVersion) {
        CommerceDtos.BookingDraftView current = requireOwned(userId, uuid);

        String origin = code(patch.origin(), current.origin());
        String destination = code(patch.destination(), current.destination());
        LocalDate date = patch.travelDate() == null ? current.travelDate() : patch.travelDate();
        if (date != null && date.isBefore(LocalDate.now())) {
            throw new ApiExceptions.BadRequest("Travel date cannot be in the past.");
        }
        if (origin != null && origin.equals(destination)) {
            throw new ApiExceptions.BadRequest("Origin and destination must be different.");
        }

        Long instanceId = patch.flightInstanceId() == null
                ? current.flightInstanceId() : patch.flightInstanceId();
        Long fareId = patch.fareId() == null ? current.fareId() : patch.fareId();
        CommerceDtos.DraftState state = current.state();
        if (instanceId != null && fareId != null) {
            state = CommerceDtos.DraftState.CHECKOUT;
        } else if (origin != null && destination != null && date != null) {
            state = CommerceDtos.DraftState.FLIGHTS_SHOWN;
        } else {
            state = CommerceDtos.DraftState.COLLECTING;
        }

        CommerceDtos.BookingDraftView desired = new CommerceDtos.BookingDraftView(
                uuid, state, origin, destination, date,
                patch.cabin() == null ? current.cabin() : normaliseCabin(patch.cabin()),
                instanceId, fareId,
                patch.seatNumber() == null ? current.seatNumber() : patch.seatNumber().toUpperCase(Locale.ROOT),
                patch.traveller() == null ? current.traveller() : patch.traveller(),
                patch.contact() == null ? current.contact() : patch.contact(),
                expectedVersion + 1, current.expiresAt());
        CommerceDtos.BookingDraftView updated =
                repository.update(uuid, userId, expectedVersion, desired);
        if (updated == null) {
            throw new ApiExceptions.Conflict(
                    "This booking changed in another request. Review the latest details.");
        }
        return updated;
    }

    public void abandon(long userId, UUID uuid) {
        requireOwned(userId, uuid);
        if (!repository.abandon(uuid, userId)) {
            throw new ApiExceptions.Conflict(
                    "This booking draft can no longer be abandoned.");
        }
    }

    private static String code(String supplied, String existing) {
        return supplied == null || supplied.isBlank()
                ? existing
                : supplied.trim().toUpperCase(Locale.ROOT);
    }

    private static String normaliseCabin(String cabin) {
        String value = cabin.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (value) {
            case "ECONOMY", "BUSINESS", "PREMIUM_ECONOMY", "FIRST" -> value;
            default -> throw new ApiExceptions.BadRequest("Unsupported cabin: " + cabin);
        };
    }
}
