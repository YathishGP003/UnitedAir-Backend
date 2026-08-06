package com.unitedair.ai.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unitedair.ai.identity.Role;
import java.util.Optional;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SessionBookingContextTest {

    @Test
    void resolvesAStoredBookingRelationshipWithoutPersistingThePnrInChatMemory() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(contains("SELECT b.pnr"))
                .param("session", "session-1")
                .query(String.class)
                .optional())
                .thenReturn(Optional.of("B6X9K2"));

        SessionBookingContext context = new SessionBookingContext(jdbc);

        assertThat(context.resolve("session-1")).contains("B6X9K2");
    }

    @Test
    void resolvesFlightDetailsForAStatusFollowUp() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        var details = new SessionBookingContext.BookingContext(
                "H3PL8M", "UA404", "DEL", "LHR", LocalDate.of(2026, 8, 17));
        when(jdbc.sql(contains("f.flight_no AS flightNo"))
                .param("session", "session-1")
                .query(SessionBookingContext.BookingContext.class)
                .optional())
                .thenReturn(Optional.of(details));

        assertThat(new SessionBookingContext(jdbc).resolveDetails("session-1"))
                .contains(details);
    }

    @Test
    void treatsALegacyRefundBookingAsRefundConversationContext() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        var reference = new SessionBookingContext.ConversationReference(
                "K2MN7V", "UA105", LocalDate.of(2026, 8, 7),
                "DEL", "BLR", "legacy-K2MN7V");
        when(jdbc.sql(contains("LEFT(UPPER(b.status), 7) = 'REFUND_'"))
                .param("session", "session-1")
                .query(SessionBookingContext.ConversationReference.class)
                .optional())
                .thenReturn(Optional.of(reference));

        assertThat(new SessionBookingContext(jdbc).resolveReference("session-1"))
                .contains(reference);
    }

    @Test
    void passengerContextCanOnlyRememberAnOwnedBooking() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(contains("AND b.user_id = :userId"))
                .param("pnr", "B6X9K2")
                .param("session", "session-1")
                .param("userId", 7L)
                .update())
                .thenReturn(1);

        boolean remembered = new SessionBookingContext(jdbc).remember(
                "session-1", "B6X9K2", Role.PASSENGER, 7L);

        assertThat(remembered).isTrue();
    }

    @Test
    void ambiguousUpcomingBookingsAreNotSilentlyAutoSelected() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(contains("b.status IN ('CONFIRMED', 'CHECKED_IN')"))
                .param("userId", 7L)
                .query(String.class)
                .list())
                .thenReturn(java.util.List.of("OLD111", "NEW222"));
        when(jdbc.sql(contains("b.status IN ('CONFIRMED', 'CHECKED_IN')"))
                .param("userId", 7L)
                .query(String.class)
                .optional())
                .thenReturn(Optional.of("OLD111"));

        var result = new SessionBookingContext(jdbc)
                .rememberUpcoming("session-1", 7L);

        assertThat(result).isEmpty();
        verify(jdbc, never()).sql(contains("INSERT INTO session_booking_context"));
    }

    @Test
    void exposesSafeContextWithoutThePnrAndCanForgetIt() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        var details = new SessionBookingContext.BookingContext(
                "H3PL8M", "UA404", "DEL", "LHR", LocalDate.of(2026, 8, 17));
        when(jdbc.sql(contains("f.flight_no AS flightNo"))
                .param("session", "session-1")
                .query(SessionBookingContext.BookingContext.class)
                .optional())
                .thenReturn(Optional.of(details));

        SessionBookingContext context = new SessionBookingContext(jdbc);

        assertThat(context.view("session-1").orElseThrow())
                .extracting(
                        SessionBookingContext.ContextView::flightNo,
                        SessionBookingContext.ContextView::origin,
                        SessionBookingContext.ContextView::destination)
                .containsExactly("UA404", "DEL", "LHR");
        context.clear("session-1");
        verify(jdbc.sql(contains("DELETE FROM session_booking_context"))
                .param("session", "session-1")).update();
    }
}
