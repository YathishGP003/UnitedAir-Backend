package com.unitedair.ai.conversation;

import java.util.List;

import com.unitedair.ai.identity.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
@Tag(name = "Sessions", description = "Conversation lifecycle and transcript")
public class SessionController {

    private final SessionService sessions;
    private final SessionBookingContext bookingContext;
    private final CurrentUser currentUser;
    private final JdbcClient jdbc;

    public SessionController(SessionService sessions,
                             SessionBookingContext bookingContext,
                             CurrentUser currentUser,
                             JdbcClient jdbc) {
        this.sessions = sessions;
        this.bookingContext = bookingContext;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @PostMapping
    @Operation(summary = "Start a conversation and receive its session UUID (SRS 4.1.4)")
    public ResponseEntity<ConversationDtos.SessionView> create(
            @RequestBody(required = false) ConversationDtos.CreateSessionRequest request) {
        String title = request == null ? null : request.title();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessions.create(currentUser.require(), title));
    }

    @GetMapping
    @Operation(summary = "List the caller's recent conversations")
    public List<ConversationDtos.SessionView> list() {
        return sessions.listForUser(currentUser.require().id());
    }

    @GetMapping("/{sessionUuid}")
    @Operation(summary = "Session metadata")
    public ConversationDtos.SessionView get(@PathVariable String sessionUuid) {
        return sessions.require(sessionUuid, currentUser.require());
    }

    @GetMapping("/{sessionUuid}/messages")
    @Operation(summary = "Redacted transcript for a conversation")
    public List<ConversationDtos.TranscriptMessage> messages(@PathVariable String sessionUuid) {
        sessions.require(sessionUuid, currentUser.require());
        return jdbc.sql("""
                    SELECT m.seq, m.role, m.content_redacted, m.trace_id, m.created_at
                    FROM chat_message m
                    JOIN chat_session s ON s.id = m.session_id
                    WHERE s.session_uuid = :uuid
                    ORDER BY m.seq
                """)
                .param("uuid", sessionUuid)
                .query((rs, n) -> new ConversationDtos.TranscriptMessage(
                        rs.getInt("seq"),
                        rs.getString("role"),
                        rs.getString("content_redacted"),
                        rs.getString("trace_id"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @GetMapping("/{sessionUuid}/booking-context")
    @Operation(summary = "Safe active-booking context for this conversation")
    public ResponseEntity<SessionBookingContext.ContextView> bookingContext(
            @PathVariable String sessionUuid) {
        sessions.require(sessionUuid, currentUser.require());
        return bookingContext.view(sessionUuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{sessionUuid}/booking-context")
    @Operation(summary = "Forget the active booking without deleting the conversation")
    public ResponseEntity<Void> clearBookingContext(@PathVariable String sessionUuid) {
        sessions.require(sessionUuid, currentUser.require());
        bookingContext.clear(sessionUuid);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionUuid}")
    @Operation(summary = "End a conversation and clear its memory (SRS 4.3.1)")
    public ResponseEntity<Void> end(@PathVariable String sessionUuid) {
        sessions.require(sessionUuid, currentUser.require());
        sessions.end(sessionUuid);
        return ResponseEntity.noContent().build();
    }
}
