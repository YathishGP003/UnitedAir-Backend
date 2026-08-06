package com.unitedair.ai.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.unitedair.ai.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceRegistrationTest {

    @Test
    void registrationNormalisesEmailHashesPasswordAndCreatesOnlyPassenger() {
        AppUserRepository users = mock(AppUserRepository.class);
        JwtService jwt = mock(JwtService.class);
        AuditService audit = mock(AuditService.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        AppUser created = new AppUser(
                41L, "maya@example.com", "stored", "Maya Singh",
                Role.PASSENGER, true, Instant.now(), null);

        when(users.createPassenger(
                eq("maya@example.com"), anyString(), eq("Maya Singh"), eq("+919000000000")))
                .thenReturn(created);
        when(jwt.issue(created)).thenReturn(
                new JwtService.IssuedToken("token", Instant.now().plusSeconds(900), 900));

        AuthService service = new AuthService(users, encoder, jwt, audit);
        AuthDtos.LoginResponse result = service.register(new AuthDtos.RegisterRequest(
                "Maya Singh", " Maya@Example.com ", "SafeDemo!2026",
                "SafeDemo!2026", "+91 90000 00000"));

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users).createPassenger(
                eq("maya@example.com"), hash.capture(), eq("Maya Singh"), eq("+919000000000"));
        assertThat(encoder.matches("SafeDemo!2026", hash.getValue())).isTrue();
        assertThat(result.user().role()).isEqualTo("PASSENGER");
        assertThat(result.token()).isEqualTo("token");
    }
}
