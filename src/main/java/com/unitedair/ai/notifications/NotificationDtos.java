package com.unitedair.ai.notifications;

import java.time.Instant;
import java.time.LocalDate;

public final class NotificationDtos {

    private NotificationDtos() { }

    public record SubscriptionRequest(String flightNo, LocalDate date) { }

    public record SubscriptionView(
            String subscriptionUuid,
            String flightNo,
            LocalDate date,
            String gate,
            String terminal,
            boolean active,
            Instant createdAt) { }

    public record FlightChangeEvent(
            String eventUuid,
            String flightNo,
            LocalDate date,
            String previousGate,
            String gate,
            String previousTerminal,
            String terminal,
            Instant detectedAt) { }
}

