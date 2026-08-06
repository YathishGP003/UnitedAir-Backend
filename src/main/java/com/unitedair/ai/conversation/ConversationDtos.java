package com.unitedair.ai.conversation;

import java.time.Instant;

/** Session shapes. */
public final class ConversationDtos {

    private ConversationDtos() { }

    /** Internal row projection, including the owning user id. Not returned to clients. */
    record SessionRow(
            Long id,
            String sessionUuid,
            Long userId,
            String actorRole,
            String title,
            Instant createdAt,
            Instant lastActivityAt,
            boolean expired,
            int messageCount) { }

    public record SessionView(
            String sessionUuid,
            String actorRole,
            String title,
            Instant createdAt,
            Instant lastActivityAt,
            boolean expired,
            int messageCount) { }

    public record CreateSessionRequest(String title) { }

    public record TranscriptMessage(
            int seq,
            String role,
            String content,
            String traceId,
            Instant createdAt) { }
}
