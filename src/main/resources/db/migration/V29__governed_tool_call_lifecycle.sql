-- SRS 2.5 / 4.1.4: preserve every stage between a model proposal and execution.
CREATE TABLE tool_call_lifecycle (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_uuid     CHAR(36)     NULL,
    trace_id         CHAR(36)     NOT NULL,
    tool_family      VARCHAR(64)  NOT NULL,
    operation        VARCHAR(64)  NOT NULL,
    actor_role       VARCHAR(32)  NULL,
    lifecycle_stage  VARCHAR(32)  NOT NULL,
    arguments_json   JSON         NULL,
    detail           VARCHAR(1000) NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_tool_lifecycle_trace (trace_id),
    KEY idx_tool_lifecycle_session (session_uuid),
    KEY idx_tool_lifecycle_family_operation (tool_family, operation),
    KEY idx_tool_lifecycle_stage (lifecycle_stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
