-- ===========================================================================
--  V4 - Airline simulator
--
--  Stands in for the GDS/NDC and reservations systems the SRS describes. The
--  tool layer talks to provider interfaces, so replacing this with a real GDS
--  means writing one adapter class and changing unitedair.providers.* to point
--  at it. Nothing above the provider boundary knows the data is simulated.
--
--  Per SRS 1.2 the system never executes payments; sim_booking therefore holds
--  fare amounts for display and rule evaluation only.
-- ===========================================================================

CREATE TABLE sim_airport (
    code      CHAR(3)      NOT NULL PRIMARY KEY,
    city      VARCHAR(80)  NOT NULL,
    name      VARCHAR(160) NOT NULL,
    country   VARCHAR(80)  NOT NULL DEFAULT 'India',
    tz        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Kolkata',
    domestic  BOOLEAN      NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sim_flight (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    flight_no      VARCHAR(10) NOT NULL,
    origin         CHAR(3)     NOT NULL,
    destination    CHAR(3)     NOT NULL,
    dep_time_local TIME        NOT NULL,
    arr_time_local TIME        NOT NULL,
    duration_min   INT         NOT NULL,
    aircraft       VARCHAR(32) NOT NULL,
    international  BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_sim_flight_no (flight_no),
    KEY idx_sim_flight_route (origin, destination),
    CONSTRAINT fk_sim_flight_origin FOREIGN KEY (origin) REFERENCES sim_airport (code),
    CONSTRAINT fk_sim_flight_dest   FOREIGN KEY (destination) REFERENCES sim_airport (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A dated occurrence of a flight. Instances are generated on demand by the
-- simulator for any requested date, so searches never return "no data" purely
-- because a date was not pre-seeded.
CREATE TABLE sim_flight_instance (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    flight_id     BIGINT      NOT NULL,
    flight_date   DATE        NOT NULL,
    -- ON_TIME | DELAYED | CANCELLED | DIVERTED | DEPARTED | ARRIVED
    status        VARCHAR(16) NOT NULL DEFAULT 'ON_TIME',
    delay_minutes INT         NOT NULL DEFAULT 0,
    gate          VARCHAR(8)  NULL,
    terminal      VARCHAR(8)  NULL,
    belt          VARCHAR(8)  NULL,
    seats_total   INT         NOT NULL DEFAULT 180,
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_sim_instance (flight_id, flight_date),
    KEY idx_sim_instance_date (flight_date),
    CONSTRAINT fk_sim_instance_flight FOREIGN KEY (flight_id) REFERENCES sim_flight (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sim_fare (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    flight_instance_id BIGINT       NOT NULL,
    -- Booking class code Y/B/M/K/H/Q/V/W (FR-016)
    fare_class         CHAR(1)      NOT NULL,
    -- ECONOMY | PREMIUM_ECONOMY | BUSINESS | FIRST
    cabin              VARCHAR(20)  NOT NULL,
    fare_brand         VARCHAR(40)  NOT NULL,
    base_fare_inr      DECIMAL(10,2) NOT NULL,
    taxes_inr          DECIMAL(10,2) NOT NULL,
    price_inr          DECIMAL(10,2) NOT NULL,
    refundable         BOOLEAN      NOT NULL DEFAULT FALSE,
    changeable         BOOLEAN      NOT NULL DEFAULT TRUE,
    change_fee_inr     DECIMAL(10,2) NOT NULL DEFAULT 0,
    cancel_fee_inr     DECIMAL(10,2) NOT NULL DEFAULT 0,
    checked_baggage_kg INT          NOT NULL DEFAULT 15,
    cabin_baggage_kg   INT          NOT NULL DEFAULT 7,
    seats_available    INT          NOT NULL DEFAULT 0,
    ffp_accrual_pct    INT          NOT NULL DEFAULT 25,
    KEY idx_sim_fare_instance (flight_instance_id),
    KEY idx_sim_fare_cabin (cabin),
    CONSTRAINT fk_sim_fare_instance FOREIGN KEY (flight_instance_id) REFERENCES sim_flight_instance (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sim_booking (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    -- 6-character alphanumeric GDS record locator
    pnr                CHAR(6)      NOT NULL,
    user_id            BIGINT       NULL,
    passenger_name     VARCHAR(120) NOT NULL,
    surname            VARCHAR(60)  NOT NULL,
    contact_email      VARCHAR(190) NULL,
    contact_phone      VARCHAR(32)  NULL,
    flight_instance_id BIGINT       NOT NULL,
    fare_id            BIGINT       NOT NULL,
    -- CONFIRMED | CANCELLED | REFUND_PENDING | REFUNDED | FLOWN | NO_SHOW
    status             VARCHAR(24)  NOT NULL DEFAULT 'CONFIRMED',
    seat_number        VARCHAR(5)   NULL,
    ffp_number         VARCHAR(24)  NULL,
    ffp_tier           VARCHAR(16)  NULL,
    special_service    VARCHAR(64)  NULL,
    amount_paid_inr    DECIMAL(10,2) NOT NULL DEFAULT 0,
    refund_amount_inr  DECIMAL(10,2) NULL,
    booked_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at       TIMESTAMP    NULL,
    UNIQUE KEY uq_sim_booking_pnr (pnr),
    KEY idx_sim_booking_user (user_id),
    KEY idx_sim_booking_surname (surname),
    CONSTRAINT fk_sim_booking_instance FOREIGN KEY (flight_instance_id) REFERENCES sim_flight_instance (id),
    CONSTRAINT fk_sim_booking_fare FOREIGN KEY (fare_id) REFERENCES sim_fare (id),
    CONSTRAINT fk_sim_booking_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sim_checkin (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    booking_id    BIGINT      NOT NULL,
    checked_in_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    seat          VARCHAR(5)  NOT NULL,
    boarding_gate VARCHAR(8)  NULL,
    -- WEB | MOBILE | KIOSK | COUNTER
    channel       VARCHAR(12) NOT NULL DEFAULT 'WEB',
    sequence_no   INT         NULL,
    UNIQUE KEY uq_sim_checkin_booking (booking_id),
    CONSTRAINT fk_sim_checkin_booking FOREIGN KEY (booking_id) REFERENCES sim_booking (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seat inventory for FR-009 (seat selection, types and fees)
CREATE TABLE sim_seat (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    flight_instance_id BIGINT       NOT NULL,
    seat_number        VARCHAR(5)   NOT NULL,
    cabin              VARCHAR(20)  NOT NULL,
    -- WINDOW | MIDDLE | AISLE
    seat_type          VARCHAR(10)  NOT NULL,
    extra_legroom      BOOLEAN      NOT NULL DEFAULT FALSE,
    exit_row           BOOLEAN      NOT NULL DEFAULT FALSE,
    fee_inr            DECIMAL(8,2) NOT NULL DEFAULT 0,
    occupied           BOOLEAN      NOT NULL DEFAULT FALSE,
    blocked            BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_sim_seat (flight_instance_id, seat_number),
    KEY idx_sim_seat_instance (flight_instance_id),
    CONSTRAINT fk_sim_seat_instance FOREIGN KEY (flight_instance_id) REFERENCES sim_flight_instance (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
