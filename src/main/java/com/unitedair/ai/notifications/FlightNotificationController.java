package com.unitedair.ai.notifications;

import java.time.Instant;
import java.util.List;

import com.unitedair.ai.identity.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/notifications/flights")
public class FlightNotificationController {

    private final FlightNotificationService service;
    private final CurrentUser currentUser;

    public FlightNotificationController(
            FlightNotificationService service,
            CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping
    public NotificationDtos.SubscriptionView subscribe(
            @RequestBody NotificationDtos.SubscriptionRequest request) {
        return service.subscribe(currentUser.require(), request);
    }

    @DeleteMapping("/{subscriptionUuid}")
    public ResponseEntity<Void> unsubscribe(@PathVariable String subscriptionUuid) {
        service.unsubscribe(currentUser.require(), subscriptionUuid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<NotificationDtos.FlightChangeEvent> events(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return service.events(currentUser.require(), since);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return service.stream(currentUser.require());
    }
}
