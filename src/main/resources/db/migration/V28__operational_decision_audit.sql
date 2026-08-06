CREATE TABLE operational_decision (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    decision_uuid       CHAR(36)      NOT NULL,
    action_uuid         CHAR(36)      NULL,
    decision_type       VARCHAR(48)   NOT NULL,
    outcome             VARCHAR(32)   NOT NULL,
    actor_user_id       BIGINT        NULL,
    actor_role          VARCHAR(32)   NOT NULL,
    pnr_hash            CHAR(64)      NULL,
    pnr_display         VARCHAR(32)   NULL,
    reason              VARCHAR(1000) NULL,
    source_policy_code  VARCHAR(64)   NULL,
    source_policy_section VARCHAR(255) NULL,
    trace_id            CHAR(36)      NULL,
    session_uuid        CHAR(36)      NULL,
    detail_json         JSON          NULL,
    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decided_at          TIMESTAMP(6)  NULL,
    UNIQUE KEY uq_operational_decision_uuid (decision_uuid),
    KEY idx_operational_decision_type_time (decision_type, created_at),
    KEY idx_operational_decision_outcome (outcome, created_at),
    KEY idx_operational_decision_pnr (pnr_hash),
    KEY idx_operational_decision_actor (actor_user_id, created_at),
    CONSTRAINT fk_operational_decision_user
        FOREIGN KEY (actor_user_id) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

