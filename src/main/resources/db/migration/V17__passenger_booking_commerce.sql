-- Passenger registration, resumable booking, simulated payment and ticketing.

CREATE TABLE passenger_profile (
    user_id       BIGINT       NOT NULL PRIMARY KEY,
    phone         VARCHAR(32)  NULL,
    traveller_json JSON        NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_passenger_profile_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE booking_draft (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    draft_uuid          CHAR(36)      NOT NULL,
    user_id             BIGINT        NOT NULL,
    session_uuid        CHAR(36)      NULL,
    state               VARCHAR(24)   NOT NULL DEFAULT 'COLLECTING',
    origin              CHAR(3)       NULL,
    destination         CHAR(3)       NULL,
    travel_date         DATE          NULL,
    cabin               VARCHAR(20)   NULL,
    flight_instance_id  BIGINT        NULL,
    fare_id             BIGINT        NULL,
    seat_number         VARCHAR(5)    NULL,
    traveller_json      JSON          NULL,
    contact_json        JSON          NULL,
    quoted_total_inr    DECIMAL(10,2) NULL,
    idempotency_key     CHAR(36)      NOT NULL,
    version             INT           NOT NULL DEFAULT 0,
    expires_at          TIMESTAMP     NOT NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_booking_draft_uuid (draft_uuid),
    UNIQUE KEY uq_booking_draft_idempotency (idempotency_key),
    KEY idx_booking_draft_user_state (user_id, state),
    KEY idx_booking_draft_session (session_uuid),
    CONSTRAINT fk_booking_draft_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_draft_instance
        FOREIGN KEY (flight_instance_id) REFERENCES sim_flight_instance (id),
    CONSTRAINT fk_booking_draft_fare
        FOREIGN KEY (fare_id) REFERENCES sim_fare (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sim_payment (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    payment_uuid        CHAR(36)      NOT NULL,
    draft_id            BIGINT        NOT NULL,
    booking_id          BIGINT        NULL,
    user_id             BIGINT        NOT NULL,
    method              VARCHAR(12)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    masked_account      VARCHAR(48)   NOT NULL,
    provider_reference  VARCHAR(48)   NULL,
    amount_inr          DECIMAL(10,2) NOT NULL,
    currency            CHAR(3)       NOT NULL DEFAULT 'INR',
    idempotency_key     CHAR(36)      NOT NULL,
    authorized_at       TIMESTAMP     NULL,
    captured_at         TIMESTAMP     NULL,
    refunded_at         TIMESTAMP     NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_sim_payment_uuid (payment_uuid),
    UNIQUE KEY uq_sim_payment_idempotency (idempotency_key),
    KEY idx_sim_payment_user (user_id),
    KEY idx_sim_payment_booking (booking_id),
    CONSTRAINT fk_sim_payment_draft
        FOREIGN KEY (draft_id) REFERENCES booking_draft (id),
    CONSTRAINT fk_sim_payment_booking
        FOREIGN KEY (booking_id) REFERENCES sim_booking (id),
    CONSTRAINT fk_sim_payment_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sim_ticket (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_number  VARCHAR(16)  NOT NULL,
    booking_id     BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    snapshot_json  JSON         NOT NULL,
    issued_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at   TIMESTAMP    NULL,
    UNIQUE KEY uq_sim_ticket_number (ticket_number),
    UNIQUE KEY uq_sim_ticket_booking (booking_id),
    KEY idx_sim_ticket_user (user_id),
    CONSTRAINT fk_sim_ticket_booking
        FOREIGN KEY (booking_id) REFERENCES sim_booking (id) ON DELETE CASCADE,
    CONSTRAINT fk_sim_ticket_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE support_callback (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_uuid        CHAR(36)     NOT NULL,
    user_id          BIGINT       NOT NULL,
    booking_id       BIGINT       NULL,
    reason           VARCHAR(80)  NOT NULL,
    requested_channel VARCHAR(16) NOT NULL DEFAULT 'PHONE',
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    resolution_note  VARCHAR(500) NULL,
    resolved_by      BIGINT       NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at      TIMESTAMP    NULL,
    UNIQUE KEY uq_support_callback_uuid (case_uuid),
    KEY idx_support_callback_user_status (user_id, status),
    CONSTRAINT fk_support_callback_user
        FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_support_callback_booking
        FOREIGN KEY (booking_id) REFERENCES sim_booking (id),
    CONSTRAINT fk_support_callback_resolver
        FOREIGN KEY (resolved_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A second Passenger demo account. Password: Demo!2026.
INSERT INTO app_user (email, password_hash, display_name, role, active)
VALUES ('passenger2@unitedair.demo',
        '$2a$10$RsRLRdvL/4LMDQNvoOUjSua0LuaGEpxH9lTyvBmKpu3w4t1u9UIDO',
        'Arjun Mehta', 'PASSENGER', TRUE)
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    display_name = VALUES(display_name),
    role = 'PASSENGER',
    active = TRUE;

INSERT IGNORE INTO passenger_profile (user_id, phone)
SELECT id, CASE email
    WHEN 'passenger@unitedair.demo' THEN '+919876543210'
    ELSE '+919811223344'
END
FROM app_user
WHERE email IN ('passenger@unitedair.demo', 'passenger2@unitedair.demo');

INSERT INTO sim_airport (code, city, name, country, tz, domestic)
VALUES ('GOI', 'Goa', 'Manohar International Airport', 'India', 'Asia/Kolkata', TRUE)
ON DUPLICATE KEY UPDATE city=VALUES(city), name=VALUES(name);

INSERT IGNORE INTO sim_flight
    (flight_no, origin, destination, dep_time_local, arr_time_local, duration_min, aircraft, international)
VALUES
    ('UA701','BLR','BOM','07:10:00','08:55:00',105,'Airbus A320neo',FALSE),
    ('UA702','BOM','BLR','18:20:00','20:05:00',105,'Airbus A320neo',FALSE),
    ('UA703','HYD','MAA','16:15:00','17:35:00',80,'ATR 72-600',FALSE),
    ('UA704','BLR','GOI','09:30:00','10:45:00',75,'Airbus A320neo',FALSE),
    ('UA705','GOI','BLR','17:40:00','18:55:00',75,'Airbus A320neo',FALSE),
    ('UA706','DEL','BOM','14:10:00','16:20:00',130,'Airbus A321neo',FALSE),
    ('UA707','DEL','CCU','06:45:00','08:55:00',130,'Airbus A320neo',FALSE),
    ('UA708','CCU','DEL','19:10:00','21:25:00',135,'Airbus A320neo',FALSE),
    ('UA709','LHR','DEL','10:25:00','23:45:00',530,'Boeing 787-9',TRUE),
    ('UA710','BOM','DXB','05:40:00','07:25:00',195,'Boeing 787-8',TRUE),
    ('UA711','DXB','BOM','20:15:00','00:45:00',180,'Boeing 787-8',TRUE),
    ('UA712','LHR','DXB','08:10:00','18:55:00',405,'Boeing 787-9',TRUE),
    ('UA713','DXB','LHR','02:25:00','07:15:00',470,'Boeing 787-9',TRUE);

INSERT IGNORE INTO sim_flight_instance
    (flight_id, flight_date, status, seats_total, terminal, gate)
SELECT f.id, DATE_ADD(CURDATE(), INTERVAL n.num DAY), 'ON_TIME',
       CASE WHEN f.international THEN 256 ELSE 180 END,
       CASE WHEN f.international THEN 'T3' ELSE 'T1' END,
       CONCAT(CASE WHEN f.international THEN 'B' ELSE 'A' END, 1 + (f.id % 18))
FROM sim_flight f
CROSS JOIN (
    SELECT a.d + b.t * 10 AS num
    FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
    CROSS JOIN (SELECT 0 t UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) b
) n
WHERE f.flight_no BETWEEN 'UA701' AND 'UA713' AND n.num BETWEEN 0 AND 45;

INSERT INTO sim_fare
    (flight_instance_id, fare_class, cabin, fare_brand, base_fare_inr, taxes_inr,
     price_inr, refundable, changeable, change_fee_inr, cancel_fee_inr,
     checked_baggage_kg, cabin_baggage_kg, seats_available, ffp_accrual_pct)
SELECT i.id, p.fare_class, p.cabin, p.fare_brand,
       ROUND(p.base * mult.factor),
       ROUND(1136 + 0.05 * (p.base * mult.factor) + mult.surcharge),
       ROUND(p.base * mult.factor) + ROUND(1136 + 0.05 * (p.base * mult.factor) + mult.surcharge),
       p.refundable, p.changeable, p.change_fee,
       CASE WHEN p.cancel_fee < 0 THEN ROUND(p.base * mult.factor) ELSE p.cancel_fee END,
       CASE WHEN f.international AND p.cabin='ECONOMY' THEN 25
            WHEN p.cabin='BUSINESS' THEN 35 ELSE 15 END,
       CASE WHEN p.cabin='BUSINESS' THEN 10 ELSE 7 END,
       p.seats, p.ffp
FROM sim_flight_instance i
JOIN sim_flight f ON f.id=i.flight_id AND f.flight_no BETWEEN 'UA701' AND 'UA713'
JOIN (
  SELECT 'Q' fare_class,'ECONOMY' cabin,'Super Saver' fare_brand,2200 base,FALSE refundable,FALSE changeable,0 change_fee,-1 cancel_fee,9 seats,10 ffp
  UNION ALL SELECT 'M','ECONOMY','Value',3500,TRUE,TRUE,2000,2000,22,50
  UNION ALL SELECT 'B','ECONOMY','Flex',4200,TRUE,TRUE,1000,1000,17,75
  UNION ALL SELECT 'C','BUSINESS','Business Flex',12000,TRUE,TRUE,0,0,4,150
) p ON TRUE
JOIN (
  SELECT f2.id,
         CASE WHEN f2.origin='LHR' OR f2.destination='LHR' THEN 8.4
              WHEN f2.origin='DXB' OR f2.destination='DXB' THEN 4.2 ELSE 1.0 END factor,
         CASE WHEN f2.origin='LHR' OR f2.destination='LHR' THEN 5400
              WHEN f2.origin='DXB' OR f2.destination='DXB' THEN 2600 ELSE 0 END surcharge
  FROM sim_flight f2
) mult ON mult.id=f.id
WHERE NOT EXISTS (SELECT 1 FROM sim_fare existing WHERE existing.flight_instance_id=i.id);

-- A distinct owned booking for the second Passenger.
INSERT INTO sim_booking
    (pnr, user_id, passenger_name, surname, contact_email, contact_phone,
     flight_instance_id, fare_id, status, seat_number, amount_paid_inr)
SELECT 'A7R2JN', u.id, 'Arjun Mehta', 'MEHTA', u.email, '+919811223344',
       i.id, sf.id, 'CONFIRMED', '18C', sf.price_inr
FROM app_user u
JOIN sim_flight f ON f.flight_no='UA706'
JOIN sim_flight_instance i ON i.flight_id=f.id
    AND i.flight_date=DATE_ADD(CURDATE(), INTERVAL 12 DAY)
JOIN sim_fare sf ON sf.flight_instance_id=i.id AND sf.fare_class='M'
WHERE u.email='passenger2@unitedair.demo'
  AND NOT EXISTS (SELECT 1 FROM sim_booking b WHERE b.pnr='A7R2JN')
LIMIT 1;
