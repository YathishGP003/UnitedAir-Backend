package com.unitedair.ai.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unitedair.ai.conversation.SessionService;
import org.junit.jupiter.api.Test;

class AuthControllerTest {

    @Test
    void logoutClearsServerMemoryBeforeTheClientDropsItsJwt() {
        CurrentUser current = mock(CurrentUser.class);
        SessionService sessions = mock(SessionService.class);
        when(current.require()).thenReturn(
                new CurrentUser.Authenticated(
                        42L, "passenger@example.com", "Passenger", Role.PASSENGER));

        var response = new AuthController(
                mock(AuthService.class), current, sessions).logout();

        verify(sessions).clearActiveMemoryForUser(42L);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }
}
