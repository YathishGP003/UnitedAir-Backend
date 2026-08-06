ALTER TABLE chat_session
    ADD COLUMN pending_operation_name VARCHAR(60) NULL;

CREATE INDEX idx_chat_session_pending_operation
    ON chat_session (user_id, pending_operation_name, expired);
