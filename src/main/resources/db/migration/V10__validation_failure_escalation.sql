-- ===========================================================================
--  V10 - Answer validation failures
--
--  Backs the Evaluator-Optimizer pattern (SRS 2.2). Every evaluation verdict is
--  recorded, not just the failing ones, so we can show that an answer was
--  checked and passed rather than merely assuming it. When an answer fails and
--  the single permitted repair also fails, the escalation raised against it is
--  linked here, which makes "why was this escalated?" answerable from data.
-- ===========================================================================

CREATE TABLE answer_validation (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_uuid       CHAR(36)     NOT NULL,
    trace_id           CHAR(36)     NOT NULL,
    answer_record_id   BIGINT       NULL,
    attempt_no         INT          NOT NULL DEFAULT 1,
    -- PASSED | FAILED
    verdict            VARCHAR(12)  NOT NULL,
    -- Individual gate outcomes, so a failure names the gate that rejected it:
    -- MISSING_CITATION | UNSUPPORTED_CLAIM | LOW_CONFIDENCE |
    -- INSUFFICIENT_FOLLOWUPS | ACTOR_SCOPE_LEAK | POLICY_DEVIATION | EMPTY_ANSWER
    failed_gates       VARCHAR(512) NULL,
    citation_coverage  DECIMAL(6,4) NULL,
    confidence         DECIMAL(6,4) NULL,
    detail_json        JSON         NULL,
    -- Set when this verdict triggered the one permitted retry
    triggered_repair   BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Set when this verdict ultimately caused an escalation
    escalation_case_id BIGINT       NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_validation_session (session_uuid),
    KEY idx_validation_trace (trace_id),
    KEY idx_validation_verdict (verdict),
    CONSTRAINT fk_validation_answer    FOREIGN KEY (answer_record_id)   REFERENCES answer_record (id)   ON DELETE CASCADE,
    CONSTRAINT fk_validation_escalation FOREIGN KEY (escalation_case_id) REFERENCES escalation_case (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Escalations raised by the validator rather than by a user complaint need to
-- be distinguishable when staff triage the queue.
ALTER TABLE escalation_case
    ADD COLUMN raised_by_system BOOLEAN NOT NULL DEFAULT FALSE AFTER reason,
    ADD COLUMN failed_gates     VARCHAR(512) NULL AFTER raised_by_system;
