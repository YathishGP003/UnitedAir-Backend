package com.unitedair.ai.identity;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes for {@code /auth}. */
public final class AuthDtos {

    private AuthDtos() { }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) { }

    public record RegisterRequest(
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Email @Size(max = 190) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank String passwordConfirmation,
            @Size(max = 32) String phone) { }

    public record LoginResponse(
            String token,
            String tokenType,
            long expiresInSeconds,
            Instant expiresAt,
            UserProfile user) { }

    /** What the frontend is told about the signed-in user. Never includes the hash. */
    public record UserProfile(
            Long id,
            String email,
            String displayName,
            String role,
            String roleDisplayName,
            List<String> readableAudiences) {

        public static UserProfile from(AppUser user) {
            return new UserProfile(
                    user.id(),
                    user.email(),
                    user.displayName(),
                    user.role().name(),
                    user.role().displayName(),
                    user.role().readableAudiences());
        }
    }

    /**
     * Advertised by {@code GET /auth/demo-users} so the login screen can offer one-click
     * sign-in for the three seeded accounts. Passwords are included because these are
     * published demonstration credentials documented in the SRS deliverables; the endpoint
     * is disabled whenever the {@code demo} accounts are absent.
     */
    public record DemoUser(
            String email,
            String password,
            String role,
            String displayName,
            String description) { }
}
