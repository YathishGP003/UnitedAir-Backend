package com.unitedair.ai.identity;

import java.util.List;

import com.unitedair.ai.conversation.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "Sign-in and session identity")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;
    private final SessionService sessions;

    public AuthController(
            AuthService authService,
            CurrentUser currentUser,
            SessionService sessions) {
        this.authService = authService;
        this.currentUser = currentUser;
        this.sessions = sessions;
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in and receive a bearer token")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/register")
    @Operation(summary = "Create a Passenger account and receive a bearer token")
    public ResponseEntity<AuthDtos.LoginResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @GetMapping("/me")
    @Operation(summary = "Profile of the currently authenticated user")
    public ResponseEntity<AuthDtos.UserProfile> me() {
        return ResponseEntity.ok(authService.profile(currentUser.require()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Clear active conversation memory before ending the local sign-in")
    public ResponseEntity<Void> logout() {
        CurrentUser.Authenticated user = currentUser.require();
        sessions.clearActiveMemoryForUser(user.id());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/demo-users")
    @Operation(summary = "Seeded demonstration accounts, for one-click sign-in on the login screen")
    public ResponseEntity<List<AuthDtos.DemoUser>> demoUsers() {
        return ResponseEntity.ok(authService.demoUsers());
    }
}
