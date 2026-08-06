-- ===========================================================================
--  V2 - Foundation: identity and conversation
--
--  SRS 4.1.4  every conversation session carries a unique UUID
--  SRS 4.1.6  message content is stored already-redacted, never raw
--  SRS 4.3.1  memory is session-scoped with a bounded window
-- ===========================================================================

-- --------------------------------------------------------------- identity ---
CREATE TABLE app_user (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email          VARCHAR(190) NOT NULL,
    password_hash  VARCHAR(120) NOT NULL,
    display_name   VARCHAR(120) NOT NULL,
    -- PASSENGER | AIRLINE_STAFF | ADMIN  (SRS 2.3)
    role           VARCHAR(32)  NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at  TIMESTAMP    NULL,
    UNIQUE KEY uq_app_user_email (email),
    KEY idx_app_user_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------- conversation ---
CREATE TABLE chat_session (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_uuid     CHAR(36)     NOT NULL,
    user_id          BIGINT       NULL,
    -- Actor role captured at session creation; drives KB audience filtering
    actor_role       VARCHAR(32)  NOT NULL,
    title            VARCHAR(200) NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expired          BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_chat_session_uuid (session_uuid),
    KEY idx_chat_session_user (user_id),
    KEY idx_chat_session_activity (last_activity_at),
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_message (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id        BIGINT      NOT NULL,
    seq               INT         NOT NULL,
    -- USER | ASSISTANT | SYSTEM
    role              VARCHAR(16) NOT NULL,
    -- Always the redacted form. Raw user text is never persisted (SRS 4.1.6).
    content_redacted  MEDIUMTEXT  NOT NULL,
    answer_record_id  BIGINT      NULL,
    trace_id          CHAR(36)    NULL,
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_chat_message_seq (session_id, seq),
    KEY idx_chat_message_session (session_id),
    CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id) REFERENCES chat_session (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
