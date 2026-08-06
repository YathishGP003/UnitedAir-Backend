-- ===========================================================================
-- V24 - Verified route meal service and UnitedAir simulator baggage tariff
--
-- Values in sim_excess_baggage_rate reproduce the approved internal demo
-- tariff UA-BAG-2026-01 documented in KB-AIR-003. They are simulator values,
-- not a claim of access to a production airline pricing system.
-- ===========================================================================

CREATE TABLE sim_flight_meal_service (
    flight_instance_id   BIGINT        NOT NULL PRIMARY KEY,
    meal_service         BOOLEAN       NOT NULL DEFAULT FALSE,
    available_codes      VARCHAR(255)  NOT NULL DEFAULT '',
    source_document_code VARCHAR(32)   NOT NULL,
    updated_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                      ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_sim_meal_instance
        FOREIGN KEY (flight_instance_id)
        REFERENCES sim_flight_instance (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sim_excess_baggage_rate (
    id                   BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    route_type           VARCHAR(16)   NOT NULL,
    cabin                VARCHAR(20)   NOT NULL,
    purchase_channel     VARCHAR(16)   NOT NULL,
    fee_per_kg           DECIMAL(10,2) NOT NULL,
    currency             CHAR(3)       NOT NULL DEFAULT 'INR',
    max_single_bag_kg    INT           NOT NULL DEFAULT 32,
    effective_from       DATE          NOT NULL,
    effective_to         DATE          NULL,
    source_document_code VARCHAR(32)   NOT NULL,
    UNIQUE KEY uq_sim_baggage_rate
        (route_type, cabin, purchase_channel, effective_from),
    CONSTRAINT ck_sim_baggage_route
        CHECK (route_type IN ('DOMESTIC', 'INTERNATIONAL')),
    CONSTRAINT ck_sim_baggage_channel
        CHECK (purchase_channel IN ('ADVANCE', 'AIRPORT')),
    CONSTRAINT ck_sim_baggage_fee CHECK (fee_per_kg >= 0),
    CONSTRAINT ck_sim_baggage_single_bag CHECK (max_single_bag_kg <= 32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO sim_excess_baggage_rate
    (route_type, cabin, purchase_channel, fee_per_kg, currency,
     max_single_bag_kg, effective_from, source_document_code)
VALUES
    ('DOMESTIC',      'ECONOMY',  'ADVANCE',  500.00, 'INR', 32, '2026-07-01', 'KB-AIR-003'),
    ('DOMESTIC',      'ECONOMY',  'AIRPORT',  650.00, 'INR', 32, '2026-07-01', 'KB-AIR-003'),
    ('DOMESTIC',      'BUSINESS', 'ADVANCE',  400.00, 'INR', 32, '2026-07-01', 'KB-AIR-003'),
    ('DOMESTIC',      'BUSINESS', 'AIRPORT',  550.00, 'INR', 32, '2026-07-01', 'KB-AIR-003'),
    ('INTERNATIONAL', 'ECONOMY',  'ADVANCE', 1200.00, 'INR', 32, '2026-07-01', 'KB-AIR-003'),
    ('INTERNATIONAL', 'ECONOMY',  'AIRPORT', 1500.00, 'INR', 32, '2026-07-01', 'KB-AIR-003'),
    ('INTERNATIONAL', 'BUSINESS', 'ADVANCE',  900.00, 'INR', 32, '2026-07-01', 'KB-AIR-003'),
    ('INTERNATIONAL', 'BUSINESS', 'AIRPORT', 1200.00, 'INR', 32, '2026-07-01', 'KB-AIR-003');

INSERT INTO sim_flight_meal_service
    (flight_instance_id, meal_service, available_codes, source_document_code)
SELECT
    i.id,
    CASE WHEN f.duration_min >= 120 THEN TRUE ELSE FALSE END,
    CASE
        WHEN f.duration_min < 120 THEN ''
        WHEN f.international THEN
            'VGML,VJML,VLML,KSML,MOML,HNML,DBML,BLML,LCML,LFML,CHML,BBML,SFML'
        ELSE
            'VGML,VJML,VLML,HNML,DBML,BLML,LCML,LFML,CHML'
    END,
    'KB-AIR-006'
FROM sim_flight_instance i
JOIN sim_flight f ON f.id = i.flight_id;
