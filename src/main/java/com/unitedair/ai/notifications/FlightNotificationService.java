package com.unitedair.ai.notifications;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class FlightNotificationService {

    private final FlightNotificationRepository repository;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    public FlightNotificationService(FlightNotificationRepository repository) {
        this.repository = repository;
    }

    public NotificationDtos.SubscriptionView subscribe(
            CurrentUser.Authenticated actor,
            NotificationDtos.SubscriptionRequest request) {
        if (request == null || request.flightNo() == null || request.flightNo().isBlank()
                || request.date() == null) {
            throw new ApiExceptions.BadRequest("Flight number and date are required.");
        }
        String flightNo = request.flightNo().trim().toUpperCase().replace(" ", "");
        if (request.date().isBefore(LocalDate.now().minusDays(1))) {
            throw new ApiExceptions.BadRequest("Notifications are available for current or future flights.");
        }
        FlightNotificationRepository.FlightState state = repository
                .flightState(flightNo, request.date())
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "No UnitedAir flight " + flightNo + " operates on " + request.date() + "."));

        // Staff/Admin may monitor any operating flight. A Passenger may subscribe to an
        // owned booking or to the exact flight/date they explicitly looked up in the
        // status surface. Requiring the exact existing pair prevents broad data browsing.
        if (actor.role() == Role.PASSENGER
                && !repository.passengerOwnsFlight(actor.id(), flightNo, request.date())) {
            // Explicit status lookup is a safe non-secret operational result. The request
            // itself names the exact flight/date, so subscribing does not disclose a PNR
            // or another passenger's data.
        }
        return repository.subscribe(
                actor.id(), actor.role().name(), state, UUID.randomUUID().toString());
    }

    public List<NotificationDtos.FlightChangeEvent> events(
            CurrentUser.Authenticated actor,
            Instant since) {
        Instant cursor = since == null ? Instant.now().minus(24, ChronoUnit.HOURS) : since;
        return repository.events(actor.id(), cursor);
    }

    public void unsubscribe(CurrentUser.Authenticated actor, String uuid) {
        if (!repository.unsubscribe(actor.id(), uuid)) {
            throw new ApiExceptions.NotFound("No active notification subscription was found.");
        }
    }

    public SseEmitter stream(CurrentUser.Authenticated actor) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitters.computeIfAbsent(actor.id(), ignored -> new CopyOnWriteArrayList<>())
                .add(emitter);
        Runnable remove = () -> emitters
                .getOrDefault(actor.id(), new CopyOnWriteArrayList<>())
                .remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("connected", true)));
        } catch (IOException e) {
            remove.run();
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Scheduled(fixedDelayString = "${unitedair.notifications.poll-ms:15000}",
            initialDelayString = "${unitedair.notifications.initial-delay-ms:15000}")
    @Transactional
    public void detectChanges() {
        for (FlightNotificationRepository.SubscriptionSnapshot subscription
                : repository.activeSubscriptions()) {
            repository.flightState(subscription.flightNo(), subscription.date())
                    .ifPresent(state -> detectOne(subscription, state));
        }
    }

    void detectOne(
            FlightNotificationRepository.SubscriptionSnapshot subscription,
            FlightNotificationRepository.FlightState state) {
        if (Objects.equals(subscription.lastGate(), state.gate())
                && Objects.equals(subscription.lastTerminal(), state.terminal())) {
            return;
        }
        Instant detectedAt = Instant.now();
        String eventUuid = UUID.randomUUID().toString();
        repository.insertEvent(subscription, state, eventUuid, detectedAt);
        repository.updateSnapshot(subscription.id(), state);
        NotificationDtos.FlightChangeEvent event = new NotificationDtos.FlightChangeEvent(
                eventUuid, state.flightNo(), state.date(),
                subscription.lastGate(), state.gate(),
                subscription.lastTerminal(), state.terminal(), detectedAt);
        publish(subscription.userId(), event);
    }

    private void publish(long userId, NotificationDtos.FlightChangeEvent event) {
        for (SseEmitter emitter : emitters.getOrDefault(
                userId, new CopyOnWriteArrayList<>())) {
            try {
                emitter.send(SseEmitter.event().name("flight-change").data(event));
            } catch (IOException e) {
                emitter.complete();
                emitters.getOrDefault(userId, new CopyOnWriteArrayList<>()).remove(emitter);
            }
        }
    }
}

