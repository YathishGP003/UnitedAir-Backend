-- Idempotent cancellation refund servicing for Passenger tracking and Staff operations.

ALTER TABLE sim_booking
    ADD COLUMN refund_status VARCHAR(24) NULL AFTER refund_amount_inr;

CREATE TABLE refund_work_item (
    id                      BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_uuid               CHAR(36)       NOT NULL,
    booking_id              BIGINT         NOT NULL,
    passenger_user_id       BIGINT         NOT NULL,
    payment_id              BIGINT         NULL,
    status                  VARCHAR(24)    NOT NULL DEFAULT 'PENDING',
    fare_brand              VARCHAR(40)    NOT NULL,
    cancellation_basis      VARCHAR(500)   NOT NULL,
    amount_paid_inr         DECIMAL(10,2)  NOT NULL,
    cancellation_fee_inr    DECIMAL(10,2)  NOT NULL,
    refund_amount_inr       DECIMAL(10,2)  NOT NULL,
    payment_method          VARCHAR(20)    NULL,
    masked_payment          VARCHAR(48)    NULL,
    due_at                  TIMESTAMP      NOT NULL,
    staff_note              VARCHAR(500)   NULL,
    assigned_to             BIGINT         NULL,
    created_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                              ON UPDATE CURRENT_TIMESTAMP,
    completed_at            TIMESTAMP      NULL,
    UNIQUE KEY uq_refund_case_uuid (case_uuid),
    UNIQUE KEY uq_refund_booking (booking_id),
    KEY idx_refund_status_due (status, due_at),
    KEY idx_refund_passenger_created (passenger_user_id, created_at),
    CONSTRAINT fk_refund_booking
        FOREIGN KEY (booking_id) REFERENCES sim_booking (id),
    CONSTRAINT fk_refund_passenger
        FOREIGN KEY (passenger_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_refund_payment
        FOREIGN KEY (payment_id) REFERENCES sim_payment (id),
    CONSTRAINT fk_refund_staff
        FOREIGN KEY (assigned_to) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refund_work_item_history (
    id                      BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    refund_work_item_id     BIGINT         NOT NULL,
    from_status             VARCHAR(24)    NULL,
    to_status               VARCHAR(24)    NOT NULL,
    note                    VARCHAR(500)   NULL,
    changed_by              BIGINT         NULL,
    changed_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_refund_history_case (refund_work_item_id, changed_at),
    CONSTRAINT fk_refund_history_case
        FOREIGN KEY (refund_work_item_id) REFERENCES refund_work_item (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_refund_history_actor
        FOREIGN KEY (changed_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
