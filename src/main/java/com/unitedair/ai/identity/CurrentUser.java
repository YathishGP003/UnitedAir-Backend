package com.unitedair.ai.identity;

import java.util.Optional;

import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller from the security context.
 *
 * <p>Reads identity straight off the verified JWT rather than reloading the user on every
 * request. The token is signed and short lived, so its claims are trustworthy for the
 * duration; only operations that need mutable state (such as recording a login) go back to
 * the database.
 */
@Component
public class CurrentUser {

    public Optional<Authenticated> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        Long id = jwt.getClaim("uid") instanceof Number n ? n.longValue() : null;
        return Optional.of(new Authenticated(
                id,
                jwt.getSubject(),
                jwt.getClaimAsString("name"),
                Role.fromString(jwt.getClaimAsString("role"))));
    }

    public Authenticated require() {
        return find().orElseThrow(() -> new ApiExceptions.Unauthorized("Authentication required."));
    }

    /** The effective role of the caller, defaulting to the least privileged. */
    public Role role() {
        return find().map(Authenticated::role).orElse(Role.PASSENGER);
    }

    public record Authenticated(Long id, String email, String displayName, Role role) { }
}
