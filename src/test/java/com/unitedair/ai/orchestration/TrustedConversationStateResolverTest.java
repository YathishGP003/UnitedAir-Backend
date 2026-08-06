package com.unitedair.ai.orchestration;

import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.actions.ActionService;
import com.unitedair.ai.commerce.BookingDraftService;
import com.unitedair.ai.conversation.SessionBookingContext;
import com.unitedair.ai.conversation.SessionService;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.shared.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustedConversationStateResolverTest {

    private final SessionService sessions = mock(SessionService.class);
    private final SessionBookingContext bookings =
            mock(SessionBookingContext.class);
    private final ActionService actions = mock(ActionService.class);
    private final BookingDraftService drafts = mock(BookingDraftService.class);
    private final TrustedConversationStateResolver resolver =
            new TrustedConversationStateResolver(
                    sessions, bookings, actions, drafts);

    @Test
    void summaryCarriesSafeLabelsButNeverThePnr() throws Exception {
        when(sessions.pendingSlot("session-1")).thenReturn(Optional.of(
                new SessionService.PendingSlot(
                        "pnr",
                        "REFUND_QUOTE",
                        Instant.parse("2026-07-28T10:00:00Z"))));
        when(bookings.view("session-1")).thenReturn(Optional.of(
                new SessionBookingContext.ContextView(
                        "UA101",
                        "BLR",
                        "DEL",
                        LocalDate.of(2026, 7, 29))));
        when(actions.pendingFor("session-1")).thenReturn(List.of(
                new ActionDtos.ActionView(
                        "action-1",
                        "CANCEL_BOOKING",
                        "PENDING",
                        "N7QTX2",
                        Map.of("flight", "UA101 BLR-DEL"),
                        List.of(),
                        null,
                        Instant.parse("2026-07-28T10:00:00Z"),
                        Instant.parse("2026-07-28T10:10:00Z"),
                        null)));

        TrustedConversationState state = resolver.resolve(
                "session-1", Role.PASSENGER, 1L, true);

        assertThat(state.ownedBookingResolved()).isTrue();
        assertThat(state.selectedFlightNo()).isEqualTo("UA101");
        assertThat(state.selectedRoute()).isEqualTo("BLR-DEL");
        assertThat(state.pendingOperation()).isEqualTo("REFUND_QUOTE");
        assertThat(state.pendingActionType()).isEqualTo("CANCEL_BOOKING");
        assertThat(state.suppliedTrustedSlot()).isTrue();
        assertThat(Json.mapper().writeValueAsString(state))
                .doesNotContain("N7QTX2");
    }
}
