package com.unitedair.ai.identity;

import java.util.List;
import java.util.Map;

import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /**
     * Cost of verifying a BCrypt hash that will never match. Comparing the supplied
     * password against this fixed hash when the account does not exist keeps the timing of
     * "no such user" and "wrong password" indistinguishable, so the endpoint cannot be used
     * to enumerate which email addresses are registered.
     */
    private static final String DUMMY_HASH =
            "$2a$10$RsRLRdvL/4LMDQNvoOUjSua0LuaGEpxH9lTyvBmKpu3w4t1u9UIDO";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;

    public AuthService(AppUserRepository users,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase();
        AppUser user = users.findByEmail(email).orElse(null);

        if (user == null) {
            // Burn the same work a real verification would, so response time does not
            // reveal whether the address is registered.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            return rejectLogin(email);
        }

        if (!passwordEncoder.matches(request.password(), user.passwordHash()) || !user.active()) {
            return rejectLogin(email);
        }

        users.touchLastLogin(user.id());
        JwtService.IssuedToken issued = jwtService.issue(user);

        audit.record("AUTH_LOGIN", user.role().name(), user.id(),
                Map.of("email", user.email(), "role", user.role().name()));

        return new AuthDtos.LoginResponse(
                issued.token(),
                "Bearer",
                issued.expiresInSeconds(),
                issued.expiresAt(),
                AuthDtos.UserProfile.from(user));
    }

    public AuthDtos.LoginResponse register(AuthDtos.RegisterRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase();
        String displayName = request.displayName() == null ? "" : request.displayName().trim();
        String password = request.password() == null ? "" : request.password();
        if (!password.equals(request.passwordConfirmation())) {
            throw new ApiExceptions.BadRequest("Passwords do not match.");
        }
        if (!strongPassword(password)) {
            throw new ApiExceptions.BadRequest(
                    "Use at least 10 characters with a letter, number and symbol.");
        }
        if (displayName.length() < 2) {
            throw new ApiExceptions.BadRequest("Enter the passenger's full name.");
        }

        String phone = normalisePhone(request.phone());
        AppUser created;
        try {
            created = users.createPassenger(
                    email, passwordEncoder.encode(password), displayName, phone);
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiExceptions.Conflict(
                    "An account with that email already exists.");
        }

        JwtService.IssuedToken issued = jwtService.issue(created);
        audit.record("AUTH_REGISTER", created.role().name(), created.id(),
                Map.of("email", created.email(), "role", created.role().name()));
        return new AuthDtos.LoginResponse(
                issued.token(), "Bearer", issued.expiresInSeconds(), issued.expiresAt(),
                AuthDtos.UserProfile.from(created));
    }

    private static boolean strongPassword(String password) {
        return password.length() >= 10
                && password.chars().anyMatch(Character::isLetter)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
    }

    private static String normalisePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String leadingPlus = phone.trim().startsWith("+") ? "+" : "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            throw new ApiExceptions.BadRequest("Enter a valid phone number.");
        }
        return leadingPlus + digits;
    }

    /**
     * Absent account, wrong password and deactivated account all end here with one
     * message, so the response never distinguishes between them.
     */
    private AuthDtos.LoginResponse rejectLogin(String email) {
        audit.record("AUTH_LOGIN_FAILED", null, null, Map.of("email", email));
        throw new ApiExceptions.Unauthorized("Invalid email or password.");
    }

    public AuthDtos.UserProfile profile(CurrentUser.Authenticated authenticated) {
        return users.findByEmail(authenticated.email())
                .map(AuthDtos.UserProfile::from)
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Account no longer exists."));
    }

    /**
     * The seeded accounts, surfaced so the login screen can offer one-click sign-in.
     * Returns an empty list if the demo accounts have been removed, which is what a real
     * deployment would do.
     */
    public List<AuthDtos.DemoUser> demoUsers() {
        List<AuthDtos.DemoUser> candidates = List.of(
                new AuthDtos.DemoUser("passenger@unitedair.demo", "Demo!2026", "PASSENGER",
                        "Ananya Rao",
                        "Search flights, manage bookings, check in, ask about baggage and refunds"),
                new AuthDtos.DemoUser("passenger2@unitedair.demo", "Demo!2026", "PASSENGER",
                        "Arjun Mehta",
                        "A second private Passenger account for booking ownership demonstrations"),
                new AuthDtos.DemoUser("staff@unitedair.demo", "Demo!2026", "AIRLINE_STAFF",
                        "Vikram Menon",
                        "Everything a Passenger can do, plus fare rules, compliance and operations"),
                new AuthDtos.DemoUser("admin@unitedair.demo", "Demo!2026", "ADMIN",
                        "Priya Krishnan",
                        "Everything Staff can do, plus Knowledge Base ingestion and governance"));

        return candidates.stream()
                .filter(demo -> users.findByEmail(demo.email()).isPresent())
                .toList();
    }
}
