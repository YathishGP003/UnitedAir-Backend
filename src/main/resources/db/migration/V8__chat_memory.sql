-- ===========================================================================
--  V8 - ChatMemory window
--
--  SRS 4.1.4A / 4.3.1. ChatMemory is bounded (10 turns), session-scoped, and
--  in-session only. It is stored rather than held in heap so that a backend
--  restart mid-conversation does not silently lose context and start answering
--  follow-ups without their antecedent.
--
--  Turn content is already redacted before it lands here (SRS 4.3.1, "PII in
--  Memory"). The window never contributes to grounding; it exists to resolve
--  references such as "what about the return leg", and the retrieval layer
--  ignores it entirely.
-- ===========================================================================

CREATE TABLE chat_memory_turn (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_uuid      CHAR(36)    NOT NULL,
    turn_index        INT         NOT NULL,
    -- USER | ASSISTANT
    role              VARCHAR(16) NOT NULL,
    content_redacted  MEDIUMTEXT  NOT NULL,
    -- Salient facts extracted from the turn (selected flight, PNR token, dates)
    -- so the query rewriter can resolve references without re-reading prose.
    salient_json      JSON        NULL,
    -- Set when the turn falls outside the configured window; retained for audit
    -- but excluded from prompt assembly.
    evicted           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_memory_turn (session_uuid, turn_index),
    KEY idx_memory_session_active (session_uuid, evicted),
    KEY idx_memory_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
