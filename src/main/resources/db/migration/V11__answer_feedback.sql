-- Privacy-safe answer quality signals. Deliberately stores no question or answer text.
CREATE TABLE answer_feedback (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    answer_record_id BIGINT       NOT NULL,
    trace_id         CHAR(36)     NOT NULL,
    user_id          BIGINT       NOT NULL,
    actor_role       VARCHAR(32)  NOT NULL,
    rating           VARCHAR(8)   NOT NULL,
    reason           VARCHAR(32)  NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_feedback_trace_user (trace_id, user_id),
    KEY idx_feedback_created (created_at),
    KEY idx_feedback_role_rating (actor_role, rating),
    CONSTRAINT fk_feedback_answer FOREIGN KEY (answer_record_id)
        REFERENCES answer_record (id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT chk_feedback_rating CHECK (rating IN ('UP', 'DOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
