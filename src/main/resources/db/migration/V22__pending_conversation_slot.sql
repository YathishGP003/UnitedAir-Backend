ALTER TABLE chat_session
    ADD COLUMN pending_slot_name VARCHAR(40) NULL,
    ADD COLUMN pending_slot_requested_at TIMESTAMP(6) NULL;

CREATE INDEX idx_chat_session_pending_slot
    ON chat_session (user_id, pending_slot_name, expired);
