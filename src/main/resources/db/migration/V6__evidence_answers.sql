-- ===========================================================================
--  V6 - Grounding evidence, answers, escalations and the audit trail
--
--  These four tables together satisfy GET /audit/{sessionId} (SRS 5): for any
--  session you can reconstruct the query, the chunks retrieved and their
--  scores, the tool calls made, the citations emitted, and whether the turn
--  was escalated.
--
--  Nothing here stores raw user text. Every free-text column holds the already
--  redacted form (SRS 4.1.6).
-- ===========================================================================

CREATE TABLE audit_event (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_uuid CHAR(36)    NULL,
    trace_id     CHAR(36)    NULL,
    -- SESSION_CREATED | QUERY_RECEIVED | PII_REDACTED | INTENT_CLASSIFIED |
    -- RETRIEVAL_COMPLETED | TOOL_INVOKED | ANSWER_GENERATED | ANSWER_EVALUATED |
    -- REPAIR_ATTEMPTED | ESCALATED | EMPTY_CONTEXT | KB_INGESTED | AUTH_LOGIN | ACTION_SETTLED
    event_type   VARCHAR(48) NOT NULL,
    actor_role   VARCHAR(32) NULL,
    user_id      BIGINT      NULL,
    payload      JSON        NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_session (session_uuid),
    KEY idx_audit_trace (trace_id),
    KEY idx_audit_type (event_type),
    KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE retrieval_run (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_uuid    CHAR(36)     NOT NULL,
    trace_id        CHAR(36)     NOT NULL,
    query_redacted  TEXT         NOT NULL,
    -- Standalone query after ChatMemory-aware rewriting
    query_rewritten TEXT         NULL,
    -- FAST | DEEP
    lane            VARCHAR(8)   NOT NULL,
    attempt         INT          NOT NULL DEFAULT 1,
    -- Metadata filters actually applied, for reproducing the run later
    filters_json    JSON         NULL,
    vector_hits     INT          NOT NULL DEFAULT 0,
    lexical_hits    INT          NOT NULL DEFAULT 0,
    fused_hits      INT          NOT NULL DEFAULT 0,
    kept_hits       INT          NOT NULL DEFAULT 0,
    top_similarity  DECIMAL(6,4) NULL,
    confidence      DECIMAL(6,4) NULL,
    duration_ms     BIGINT       NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_retrieval_session (session_uuid),
    KEY idx_retrieval_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE evidence_record (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    retrieval_run_id BIGINT       NOT NULL,
    chunk_id         BIGINT       NULL,
    -- Citation coordinates (SRS 4.1.2)
    document_code    VARCHAR(64)  NOT NULL,
    document_title   VARCHAR(255) NULL,
    section          VARCHAR(255) NULL,
    page             INT          NULL,
    -- E1, E2 ... the handle the model was told to cite
    evidence_handle  VARCHAR(8)   NOT NULL,
    vector_score     DECIMAL(6,4) NULL,
    lexical_score    DECIMAL(6,4) NULL,
    fused_score      DECIMAL(8,6) NULL,
    rank_position    INT          NOT NULL,
    used_in_answer   BOOLEAN      NOT NULL DEFAULT FALSE,
    excerpt          TEXT         NULL,
    KEY idx_evidence_run (retrieval_run_id),
    CONSTRAINT fk_evidence_run FOREIGN KEY (retrieval_run_id) REFERENCES retrieval_run (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE answer_record (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_uuid      CHAR(36)     NOT NULL,
    trace_id          CHAR(36)     NOT NULL,
    retrieval_run_id  BIGINT       NULL,
    -- GROUNDED | EMPTY_CONTEXT | ESCALATED | TOOL_GROUNDED | ERROR
    status            VARCHAR(24)  NOT NULL,
    -- KB_LOOKUP | TOOL_CALL | TOOL_PLUS_KB | ESCALATION
    intent            VARCHAR(24)  NULL,
    actor_role        VARCHAR(32)  NULL,
    answer_redacted   MEDIUMTEXT   NOT NULL,
    citations_json    JSON         NULL,
    followups_json    JSON         NULL,
    tools_used_json   JSON         NULL,
    confidence        DECIMAL(6,4) NULL,
    citation_coverage DECIMAL(6,4) NULL,
    repair_attempts   INT          NOT NULL DEFAULT 0,
    escalated         BOOLEAN      NOT NULL DEFAULT FALSE,
    model_name        VARCHAR(64)  NULL,
    ai_mode           VARCHAR(16)  NULL,
    prompt_tokens     INT          NULL,
    completion_tokens INT          NULL,
    duration_ms       BIGINT       NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_answer_session (session_uuid),
    KEY idx_answer_trace (trace_id),
    KEY idx_answer_status (status),
    CONSTRAINT fk_answer_run FOREIGN KEY (retrieval_run_id) REFERENCES retrieval_run (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE escalation_case (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_uuid        CHAR(36)     NOT NULL,
    session_uuid     CHAR(36)     NOT NULL,
    trace_id         CHAR(36)     NULL,
    user_id          BIGINT       NULL,
    -- LOW_CONFIDENCE | EMPTY_CONTEXT | BOOKING_DISPUTE | REFUND_DENIAL |
    -- FRAUD_ALLEGATION | COMPLAINT | VALIDATION_FAILURE | USER_REQUESTED
    reason           VARCHAR(32)  NOT NULL,
    -- CUSTOMER_SUPPORT_MANAGER | DGCA_GRIEVANCE_OFFICER
    target_queue     VARCHAR(40)  NOT NULL,
    priority         VARCHAR(12)  NOT NULL DEFAULT 'NORMAL',
    summary_redacted TEXT         NOT NULL,
    query_redacted   TEXT         NULL,
    confidence       DECIMAL(6,4) NULL,
    subject_pnr      CHAR(6)      NULL,
    -- OPEN | ACKNOWLEDGED | RESOLVED | CLOSED
    status           VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    resolution_note  TEXT         NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at      TIMESTAMP    NULL,
    UNIQUE KEY uq_escalation_uuid (case_uuid),
    KEY idx_escalation_session (session_uuid),
    KEY idx_escalation_status (status),
    KEY idx_escalation_queue (target_queue),
    CONSTRAINT fk_escalation_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Now that answer_record exists, complete the chat_message reference.
ALTER TABLE chat_message
    ADD CONSTRAINT fk_chat_message_answer
    FOREIGN KEY (answer_record_id) REFERENCES answer_record (id) ON DELETE SET NULL;
