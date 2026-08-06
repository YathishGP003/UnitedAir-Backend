-- ===========================================================================
--  V5 - Tool invocations and action requests
--
--  SRS 4.1.4: "Every tool invocation must be logged against that session UUID
--  for end-to-end audit traceability." tool_invocation is that log, written
--  before the call is dispatched and updated when it settles, so a crashed or
--  timed-out call still leaves evidence.
--
--  action_request implements the two-phase mutation rule of SRS 4.2.3: nothing
--  that changes a booking happens on the strength of a model turn alone. The
--  assistant proposes; a human confirms.
-- ===========================================================================

CREATE TABLE tool_invocation (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_uuid  CHAR(36)     NOT NULL,
    trace_id      CHAR(36)     NOT NULL,
    tool_name     VARCHAR(64)  NOT NULL,
    -- Arguments after PII redaction
    request_json  JSON         NULL,
    -- PENDING | SUCCESS | FAILED | TIMEOUT | DENIED
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    result_json   JSON         NULL,
    error_message VARCHAR(1000) NULL,
    duration_ms   BIGINT       NULL,
    invoked_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at  TIMESTAMP    NULL,
    KEY idx_tool_session (session_uuid),
    KEY idx_tool_trace (trace_id),
    KEY idx_tool_name (tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE action_request (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    action_uuid   CHAR(36)     NOT NULL,
    session_uuid  CHAR(36)     NOT NULL,
    trace_id      CHAR(36)     NULL,
    user_id       BIGINT       NULL,
    -- CANCEL_BOOKING | RESCHEDULE_BOOKING | SEAT_CHANGE | CHECK_IN | REFUND_REQUEST
    type          VARCHAR(32)  NOT NULL,
    -- PENDING | CONFIRMED | CANCELLED | EXPIRED | FAILED
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    subject_pnr   CHAR(6)      NULL,
    -- Human-readable description of exactly what will happen on confirm
    summary_json  JSON         NOT NULL,
    -- KB citations that justify the fees/rules quoted in the summary
    citations_json JSON        NULL,
    result_json   JSON         NULL,
    failure_reason VARCHAR(1000) NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at    TIMESTAMP    NULL,
    settled_at    TIMESTAMP    NULL,
    UNIQUE KEY uq_action_uuid (action_uuid),
    KEY idx_action_session (session_uuid),
    KEY idx_action_status (status),
    KEY idx_action_user (user_id),
    CONSTRAINT fk_action_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
