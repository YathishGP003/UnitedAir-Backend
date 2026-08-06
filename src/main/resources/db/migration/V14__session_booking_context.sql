CREATE TABLE session_booking_context (
    session_id BIGINT NOT NULL PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_session_booking_context_booking (booking_id),
    CONSTRAINT fk_session_booking_context_session
        FOREIGN KEY (session_id) REFERENCES chat_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_session_booking_context_booking
        FOREIGN KEY (booking_id) REFERENCES sim_booking (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
