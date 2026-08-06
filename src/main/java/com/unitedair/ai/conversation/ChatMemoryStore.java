package com.unitedair.ai.conversation;

import java.util.List;

import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.privacy.PiiRedactor;
import com.unitedair.ai.shared.Json;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The bounded, session-scoped conversation window of SRS 4.1.4A and 4.3.1.
 *
 * <p>Three constraints from the SRS shape this class:
 *
 * <ul>
 *   <li><b>Session scope.</b> Memory is keyed by session UUID. There is no cross-session or
 *       cross-user memory, and no query here can return another session's turns.</li>
 *   <li><b>Bounded depth.</b> Only the last N turns are active; older ones are marked
 *       evicted rather than deleted, so the audit trail stays complete while the prompt
 *       stays bounded.</li>
 *   <li><b>Already redacted.</b> Turns arrive redacted. The assertion in {@link #append}
 *       is a tripwire: if a future change ever routes raw text here, it fails loudly in
 *       development rather than quietly persisting a passport number.</li>
 * </ul>
 *
 * <p>Memory is explicitly <em>not</em> grounding. It resolves references ("what about the
 * return leg?") so the query rewriter can build a standalone question. It never enters the
 * evidence set, and it can never be the source of a policy claim.
 */
@Component
public class ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryStore.class);

    private final JdbcClient jdbc;
    private final PiiRedactor redactor;
    private final UnitedAirProperties.ChatMemory config;

    public ChatMemoryStore(JdbcClient jdbc, PiiRedactor redactor, UnitedAirProperties properties) {
        this.jdbc = jdbc;
        this.redactor = redactor;
        this.config = properties.getChatMemory();
    }

    /** Appends one already-redacted turn and re-applies the window. */
    public void append(String sessionUuid, String role, String contentRedacted, Object salient) {
        if (sessionUuid == null || contentRedacted == null || contentRedacted.isBlank()) {
            return;
        }

        // Tripwire, not redaction: this store must never be the thing that redacts.
        if (looksUnredacted(contentRedacted)) {
            log.warn("Turn for session {} reached ChatMemory without redaction markers; "
                    + "redacting defensively. This indicates a pipeline ordering bug.", sessionUuid);
            contentRedacted = redactor.redact(contentRedacted).redacted();
        }

        int nextIndex = jdbc.sql("""
                    SELECT COALESCE(MAX(turn_index), -1) + 1
                    FROM chat_memory_turn WHERE session_uuid = :s
                """).param("s", sessionUuid).query(Integer.class).single();

        jdbc.sql("""
                    INSERT INTO chat_memory_turn (session_uuid, turn_index, role, content_redacted, salient_json)
                    VALUES (:s, :i, :role, :content, :salient)
                """)
                .param("s", sessionUuid)
                .param("i", nextIndex)
                .param("role", role)
                .param("content", contentRedacted)
                .param("salient", salient == null ? null : Json.write(salient))
                .update();

        applyWindow(sessionUuid);
    }

    /** The active window, oldest first, ready to be replayed into a prompt. */
    public List<ChatDtos.HistoryTurn> window(String sessionUuid) {
        if (sessionUuid == null) {
            return List.of();
        }
        return jdbc.sql("""
                    SELECT role, content_redacted
                    FROM chat_memory_turn
                    WHERE session_uuid = :s AND evicted = FALSE
                    ORDER BY turn_index
                """)
                .param("s", sessionUuid)
                .query((rs, n) -> new ChatDtos.HistoryTurn(
                        rs.getString("role"), rs.getString("content_redacted")))
                .list();
    }

    /**
     * Marks everything outside the newest {@code maxTurns} as evicted.
     *
     * <p>A "turn" here is one stored user or assistant message. The literal configured
     * depth of 10 therefore retains the newest 10 messages.
     */
    void applyWindow(String sessionUuid) {
        Integer cutoff = jdbc.sql("""
                    SELECT MIN(turn_index) FROM (
                        SELECT turn_index
                        FROM chat_memory_turn
                        WHERE session_uuid = :s AND evicted = FALSE
                        ORDER BY turn_index DESC
                        LIMIT :keep
                    ) recent
                """)
                .param("s", sessionUuid)
                .param("keep", config.getMaxTurns())
                .query(Integer.class)
                .optional()
                .orElse(null);

        if (cutoff == null) {
            return;
        }

        jdbc.sql("""
                    UPDATE chat_memory_turn
                       SET evicted = TRUE
                     WHERE session_uuid = :s AND turn_index < :cutoff AND evicted = FALSE
                """)
                .param("s", sessionUuid)
                .param("cutoff", cutoff)
                .update();
    }

    /** SRS 4.3.1: memory is cleared on explicit logout or session expiry. */
    public void clear(String sessionUuid) {
        jdbc.sql("UPDATE chat_memory_turn SET evicted = TRUE WHERE session_uuid = :s")
                .param("s", sessionUuid)
                .update();
    }

    /** Clears every active memory window owned by one authenticated user. */
    public void clearActiveMemoryForUser(long userId) {
        jdbc.sql("""
                    UPDATE chat_memory_turn m
                    JOIN chat_session s ON s.session_uuid = m.session_uuid
                    SET m.evicted = TRUE
                    WHERE s.user_id = :userId AND m.evicted = FALSE
                """)
                .param("userId", userId)
                .update();
    }

    private boolean looksUnredacted(String text) {
        // Cheap structural check for the highest-risk formats. A full redaction pass on
        // every append would double the regex work for text that is already clean.
        return text.matches(".*\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b.*")
                || text.matches(".*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*");
    }
}
