CREATE TABLE flight_notification_subscription (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subscription_uuid CHAR(36)     NOT NULL,
    user_id           BIGINT       NOT NULL,
    actor_role        VARCHAR(32)  NOT NULL,
    flight_no         VARCHAR(12)  NOT NULL,
    flight_date       DATE         NOT NULL,
    last_gate         VARCHAR(16)  NULL,
    last_terminal     VARCHAR(16)  NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_flight_subscription_user
        (user_id, flight_no, flight_date),
    UNIQUE KEY uq_flight_subscription_uuid (subscription_uuid),
    KEY idx_flight_subscription_active (active, flight_date),
    CONSTRAINT fk_flight_subscription_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flight_notification_event (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_uuid        CHAR(36)     NOT NULL,
    subscription_id   BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    flight_no         VARCHAR(12)  NOT NULL,
    flight_date       DATE         NOT NULL,
    previous_gate     VARCHAR(16)  NULL,
    gate              VARCHAR(16)  NULL,
    previous_terminal VARCHAR(16)  NULL,
    terminal          VARCHAR(16)  NULL,
    detected_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_flight_notification_event_uuid (event_uuid),
    KEY idx_flight_event_user_time (user_id, detected_at),
    CONSTRAINT fk_flight_event_subscription
        FOREIGN KEY (subscription_id)
        REFERENCES flight_notification_subscription (id) ON DELETE CASCADE,
    CONSTRAINT fk_flight_event_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

