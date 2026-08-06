package com.unitedair.ai.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.Test;

class FlightNotificationServiceTest {

    private final FlightNotificationRepository repository =
            mock(FlightNotificationRepository.class);
    private final FlightNotificationService service =
            new FlightNotificationService(repository);

    @Test
    void passengerSubscribesOnlyToTheExactExistingFlightAndDate() {
        LocalDate date = LocalDate.now().plusDays(1);
        var state = new FlightNotificationRepository.FlightState(
                10L, "UA102", date, "A12", "T1");
        var actor = new CurrentUser.Authenticated(
                7L, "passenger@example.test", "Passenger", Role.PASSENGER);
        var view = new NotificationDtos.SubscriptionView(
                "sub-1", "UA102", date, "A12", "T1", true,
                java.time.Instant.now());
        when(repository.flightState("UA102", date)).thenReturn(Optional.of(state));
        when(repository.subscribe(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("PASSENGER"),
                org.mockito.ArgumentMatchers.eq(state),
                anyString())).thenReturn(view);

        assertThat(service.subscribe(
                actor, new NotificationDtos.SubscriptionRequest("ua 102", date)))
                .isEqualTo(view);
        verify(repository).flightState("UA102", date);
    }

    @Test
    void unchangedGateAndTerminalDoNotCreateDuplicateEvents() {
        LocalDate date = LocalDate.now().plusDays(1);
        var subscription = new FlightNotificationRepository.SubscriptionSnapshot(
                1L, "sub", 7L, "UA102", date, "A12", "T1");
        var state = new FlightNotificationRepository.FlightState(
                10L, "UA102", date, "A12", "T1");

        service.detectOne(subscription, state);

        verify(repository, never()).insertEvent(any(), any(), anyString(), any());
        verify(repository, never()).updateSnapshot(anyLong(), any());
    }

    @Test
    void changedGateCreatesOneEventAndAdvancesSnapshot() {
        LocalDate date = LocalDate.now().plusDays(1);
        var subscription = new FlightNotificationRepository.SubscriptionSnapshot(
                1L, "sub", 7L, "UA102", date, "A10", "T1");
        var state = new FlightNotificationRepository.FlightState(
                10L, "UA102", date, "A12", "T1");

        service.detectOne(subscription, state);

        verify(repository).insertEvent(
                org.mockito.ArgumentMatchers.eq(subscription),
                org.mockito.ArgumentMatchers.eq(state),
                anyString(),
                any());
        verify(repository).updateSnapshot(1L, state);
    }
}
