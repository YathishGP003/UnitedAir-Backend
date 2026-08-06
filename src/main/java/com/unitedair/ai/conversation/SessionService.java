package com.unitedair.ai.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.orchestration.OrchestrationDtos;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Conversation sessions.
 *
 * <p>SRS 4.1.4 requires a unique UUID generated at session initiation, against which every
 * tool invocation is logged. That UUID is the spine of the audit trail, so it is created
 * here and nowhere else.
 *
 * <p>A session records the actor role at creation time. Retrieval filters on that role, so
 * a session started as a Passenger stays a Passenger session even if the same person later
 * signs in as Staff - the alternative would let a conversation silently gain access to
 * staff-only policy halfway through.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final JdbcClient jdbc;
    private final ChatMemoryStore memory;
    private final AuditService audit;
    private final SessionBookingContext bookingContext;
    private final UnitedAirProperties.ChatMemory config;

    public SessionService(JdbcClient jdbc,
                          ChatMemoryStore memory,
                          AuditService audit,
                          SessionBookingContext bookingContext,
                          UnitedAirProperties properties) {
        this.jdbc = jdbc;
        this.memory = memory;
        this.audit = audit;
        this.bookingContext = bookingContext;
        this.config = properties.getChatMemory();
    }

    public ConversationDtos.SessionView create(CurrentUser.Authenticated user, String title) {
        String sessionUuid = UUID.randomUUID().toString();

        jdbc.sql("""
                    INSERT INTO chat_session (session_uuid, user_id, actor_role, title)
                    VALUES (:uuid, :userId, :role, :title)
                """)
                .param("uuid", sessionUuid)
                .param("userId", user.id(), java.sql.Types.BIGINT)
                .param("role", user.role().name())
                .param("title", title)
                .update();

        audit.recordFor(sessionUuid, null, "SESSION_CREATED", user.role().name(), user.id(),
                Map.of("actorRole", user.role().name()));

        log.debug("Session {} created for {} ({})", sessionUuid, user.email(), user.role());
        return new ConversationDtos.SessionView(
                sessionUuid, user.role().name(), title, Instant.now(), Instant.now(), false, 0);
    }

    public ConversationDtos.SessionView require(String sessionUuid, CurrentUser.Authenticated user) {
        ConversationDtos.SessionRow row = findRow(sessionUuid);

        if (row == null) {
            throw new ApiExceptions.NotFound("No such session. Start a new conversation.");
        }
        // A session belongs to the user who created it. Anonymous sessions (no user id)
        // are only reachable by the role that created them.
        if (row.userId() != null && user.id() != null && !row.userId().equals(user.id())) {
            throw new ApiExceptions.Forbidden("This conversation belongs to another user.");
        }
        if (row.expired()) {
            throw new ApiExceptions.Conflict(
                    "This conversation has expired after " + config.getSessionTimeoutMinutes()
                            + " minutes of inactivity. Start a new one.");
        }

        return new ConversationDtos.SessionView(
                row.sessionUuid(), row.actorRole(), row.title(),
                row.createdAt(), row.lastActivityAt(), row.expired(), row.messageCount());
    }

    /** The role the session was created with, which is what retrieval filters on. */
    public Role actorRole(String sessionUuid) {
        ConversationDtos.SessionRow row = findRow(sessionUuid);
        return row == null ? Role.PASSENGER : Role.fromString(row.actorRole());
    }

    public void touch(String sessionUuid) {
        jdbc.sql("""
                    UPDATE chat_session SET last_activity_at = CURRENT_TIMESTAMP
                    WHERE session_uuid = :uuid
                """).param("uuid", sessionUuid).update();
    }

    public List<ConversationDtos.SessionView> listForUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return jdbc.sql("""
                    SELECT s.session_uuid, s.actor_role, s.title, s.created_at,
                           s.last_activity_at, s.expired,
                           (SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id) AS message_count
                    FROM chat_session s
                    WHERE s.user_id = :userId
                      AND s.expired = FALSE
                    ORDER BY s.last_activity_at DESC
                    LIMIT 50
                """)
                .param("userId", userId)
                .query((rs, n) -> new ConversationDtos.SessionView(
                        rs.getString("session_uuid"),
                        rs.getString("actor_role"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_activity_at").toInstant(),
                        rs.getBoolean("expired"),
                        rs.getInt("message_count")))
                .list();
    }

    /** Explicit end of conversation. SRS 4.3.1 clears memory on logout. */
    public void end(String sessionUuid) {
        jdbc.sql("UPDATE chat_session SET expired = TRUE WHERE session_uuid = :uuid")
                .param("uuid", sessionUuid).update();
        memory.clear(sessionUuid);
        bookingContext.clear(sessionUuid);
        audit.recordFor(sessionUuid, null, "SESSION_ENDED", null, null, Map.of());
    }

    /**
     * SRS 4.3.1: memory is cleared after the configured inactivity period. Runs every
     * minute; the window is small enough that a slightly late sweep is harmless, and
     * checking on read as well would double the cost of every turn.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void expireIdleSessions() {
        expireInactiveSessions(
                Instant.now().minusSeconds(config.getSessionTimeoutMinutes() * 60L));
    }

    public int expireInactiveSessions(Instant inactiveBefore) {
        List<String> expiring = jdbc.sql("""
                    SELECT session_uuid FROM chat_session
                    WHERE expired = FALSE
                      AND last_activity_at <= :inactiveBefore
                """)
                .param("inactiveBefore", java.sql.Timestamp.from(inactiveBefore))
                .query(String.class)
                .list();

        if (expiring.isEmpty()) {
            return 0;
        }

        for (String sessionUuid : expiring) {
            jdbc.sql("UPDATE chat_session SET expired = TRUE WHERE session_uuid = :uuid")
                    .param("uuid", sessionUuid).update();
            memory.clear(sessionUuid);
            bookingContext.clear(sessionUuid);
        }
        log.info("Expired {} idle session(s) after {} minutes of inactivity",
                expiring.size(), config.getSessionTimeoutMinutes());
        return expiring.size();
    }

    public void clearActiveMemoryForUser(long userId) {
        memory.clearActiveMemoryForUser(userId);
        bookingContext.clearForUser(userId);
        jdbc.sql("""
                    UPDATE chat_session
                    SET pending_slot_name = NULL,
                        pending_operation_name = NULL,
                        pending_slot_requested_at = NULL
                    WHERE user_id = :userId AND expired = FALSE
                """)
                .param("userId", userId)
                .update();
    }

    public void rememberPendingSlot(
            String sessionUuid,
            String name,
            OrchestrationDtos.ToolTarget operation) {
        jdbc.sql("""
                    UPDATE chat_session
                    SET pending_slot_name = :name,
                        pending_operation_name = :operation,
                        pending_slot_requested_at = CURRENT_TIMESTAMP
                    WHERE session_uuid = :session AND expired = FALSE
                """)
                .param("name", name)
                .param("operation", operation == null ? null : operation.name())
                .param("session", sessionUuid)
                .update();
    }

    public java.util.Optional<PendingSlot> pendingSlot(String sessionUuid) {
        return jdbc.sql("""
                    SELECT pending_slot_name, pending_operation_name,
                           pending_slot_requested_at
                    FROM chat_session
                    WHERE session_uuid = :session
                      AND expired = FALSE
                      AND pending_slot_name IS NOT NULL
                """)
                .param("session", sessionUuid)
                .query((rs, row) -> new PendingSlot(
                        rs.getString("pending_slot_name"),
                        rs.getString("pending_operation_name"),
                        rs.getTimestamp("pending_slot_requested_at").toInstant()))
                .optional();
    }

    public void clearPendingSlot(String sessionUuid) {
        jdbc.sql("""
                    UPDATE chat_session
                    SET pending_slot_name = NULL,
                        pending_operation_name = NULL,
                        pending_slot_requested_at = NULL
                    WHERE session_uuid = :session
                """)
                .param("session", sessionUuid)
                .update();
    }

    public record PendingSlot(
            String name,
            String operation,
            Instant requestedAt) { }

    // ------------------------------------------------------------------ internals ---

    private ConversationDtos.SessionRow findRow(String sessionUuid) {
        return jdbc.sql("""
                    SELECT s.id, s.session_uuid, s.user_id, s.actor_role, s.title,
                           s.created_at, s.last_activity_at, s.expired,
                           (SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id) AS message_count
                    FROM chat_session s
                    WHERE s.session_uuid = :uuid
                """)
                .param("uuid", sessionUuid)
                .query((rs, n) -> new ConversationDtos.SessionRow(
                        rs.getLong("id"),
                        rs.getString("session_uuid"),
                        (Long) rs.getObject("user_id"),
                        rs.getString("actor_role"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_activity_at").toInstant(),
                        rs.getBoolean("expired"),
                        rs.getInt("message_count")))
                .optional()
                .orElse(null);
    }

    /** Persists one message of the transcript. Content must already be redacted. */
    public void appendMessage(String sessionUuid, String role, String contentRedacted,
                              Long answerRecordId, String traceId) {
        Long sessionId = jdbc.sql("SELECT id FROM chat_session WHERE session_uuid = :uuid")
                .param("uuid", sessionUuid).query(Long.class).optional().orElse(null);
        if (sessionId == null) {
            return;
        }

        if ("USER".equalsIgnoreCase(role)) {
            jdbc.sql("""
                        UPDATE chat_session
                        SET title = :title
                        WHERE id = :id AND (title IS NULL OR TRIM(title) = '')
                    """)
                    .param("title", conversationTitle(contentRedacted))
                    .param("id", sessionId)
                    .update();
        }

        int seq = jdbc.sql("SELECT COALESCE(MAX(seq), -1) + 1 FROM chat_message WHERE session_id = :id")
                .param("id", sessionId).query(Integer.class).single();

        jdbc.sql("""
                    INSERT INTO chat_message (session_id, seq, role, content_redacted, answer_record_id, trace_id)
                    VALUES (:sessionId, :seq, :role, :content, :answerId, :traceId)
                """)
                .param("sessionId", sessionId)
                .param("seq", seq)
                .param("role", role)
                .param("content", contentRedacted)
                .param("answerId", answerRecordId, java.sql.Types.BIGINT)
                .param("traceId", traceId)
                .update();
    }

    static String conversationTitle(String text) {
        String clean = text == null ? "Conversation" : text.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return "Conversation";
        }
        if (clean.length() <= 52) {
            return clean;
        }
        String shortened = clean.substring(0, 52);
        int lastSpace = shortened.lastIndexOf(' ');
        return (lastSpace > 30 ? shortened.substring(0, lastSpace) : shortened).trim() + "…";
    }
}
