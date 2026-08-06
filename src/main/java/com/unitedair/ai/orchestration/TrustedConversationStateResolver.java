package com.unitedair.ai.orchestration;

import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.actions.ActionService;
import com.unitedair.ai.commerce.BookingDraftService;
import com.unitedair.ai.commerce.CommerceDtos;
import com.unitedair.ai.conversation.SessionBookingContext;
import com.unitedair.ai.conversation.SessionService;
import com.unitedair.ai.identity.Role;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Builds the bounded state summary supplied to semantic planning.
 *
 * <p>The summary intentionally excludes PNRs, people, contact details, payment data,
 * action identifiers and mutation parameters.
 */
@Component
public class TrustedConversationStateResolver {

    private final SessionService sessions;
    private final SessionBookingContext bookingContext;
    private final ActionService actions;
    private final BookingDraftService drafts;

    public TrustedConversationStateResolver(
            SessionService sessions,
            SessionBookingContext bookingContext,
            ActionService actions,
            BookingDraftService drafts) {
        this.sessions = sessions;
        this.bookingContext = bookingContext;
        this.actions = actions;
        this.drafts = drafts;
    }

    public TrustedConversationState resolve(
            String sessionUuid,
            Role role,
            Long userId,
            boolean suppliedTrustedSlot) {
        SessionService.PendingSlot pendingSlot =
                sessions.pendingSlot(sessionUuid).orElse(null);
        SessionBookingContext.ContextView booking =
                bookingContext.view(sessionUuid).orElse(null);
        ActionDtos.ActionView pendingAction = actions.pendingFor(sessionUuid).stream()
                .filter(action -> "PENDING".equalsIgnoreCase(action.status()))
                .findFirst()
                .orElse(null);
        Optional<CommerceDtos.BookingDraftView> draft =
                role == Role.PASSENGER && userId != null
                        ? drafts.findActive(userId, sessionUuid)
                        : Optional.empty();

        String flightNo = booking == null ? null : booking.flightNo();
        String route = booking == null
                ? draft.map(TrustedConversationStateResolver::route).orElse(null)
                : route(booking.origin(), booking.destination());
        LocalDate travelDate = booking == null
                ? draft.map(CommerceDtos.BookingDraftView::travelDate).orElse(null)
                : booking.travelDate();

        return new TrustedConversationState(
                booking != null,
                flightNo,
                route,
                travelDate,
                pendingSlot == null ? null : pendingSlot.operation(),
                pendingSlot == null ? null : pendingSlot.name(),
                pendingAction == null ? null : pendingAction.type(),
                pendingAction == null ? null : pendingAction.status(),
                suppliedTrustedSlot,
                draft.isPresent(),
                draft.map(value -> value.state().name()).orElse(null));
    }

    private static String route(CommerceDtos.BookingDraftView draft) {
        return route(draft.origin(), draft.destination());
    }

    private static String route(String origin, String destination) {
        return origin == null || destination == null
                ? null : origin + "-" + destination;
    }
}
