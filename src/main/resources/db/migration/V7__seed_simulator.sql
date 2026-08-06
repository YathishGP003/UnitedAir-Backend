-- ===========================================================================
--  V7 - Simulator seed data
--
--  Routes reproduce the reference environment. Fare classes, cabins and brands
--  are taken from KB_05 section 2.2 (Booking Class Codes and Revenue Bands) and
--  the fee columns from KB_04 section 2.1 (Cancellation Fee Matrix), so the
--  numbers the tools return agree with the policy the assistant cites. If these
--  drifted apart the system would quote a price under one rule and justify it
--  with another.
--
--  Premium Economy is deliberately not seeded: KB_05 documents it as a cabin
--  but publishes no booking class for it, and inventing one would put
--  uncited data in front of users.
-- ===========================================================================

-- ---------------------------------------------------------------- airports ---
INSERT INTO sim_airport (code, city, name, country, tz, domestic) VALUES
    ('DEL', 'New Delhi',  'Indira Gandhi International Airport',      'India',  'Asia/Kolkata',  TRUE),
    ('BOM', 'Mumbai',     'Chhatrapati Shivaji Maharaj International','India',  'Asia/Kolkata',  TRUE),
    ('BLR', 'Bengaluru',  'Kempegowda International Airport',         'India',  'Asia/Kolkata',  TRUE),
    ('HYD', 'Hyderabad',  'Rajiv Gandhi International Airport',       'India',  'Asia/Kolkata',  TRUE),
    ('MAA', 'Chennai',    'Chennai International Airport',            'India',  'Asia/Kolkata',  TRUE),
    ('CCU', 'Kolkata',    'Netaji Subhas Chandra Bose International', 'India',  'Asia/Kolkata',  TRUE),
    ('DXB', 'Dubai',      'Dubai International Airport',              'UAE',    'Asia/Dubai',    FALSE),
    ('LHR', 'London',     'Heathrow Airport',                         'UK',     'Europe/London', FALSE),
    ('SIN', 'Singapore',  'Changi Airport',                           'Singapore','Asia/Singapore', FALSE);

-- ----------------------------------------------------------------- flights ---
INSERT INTO sim_flight (flight_no, origin, destination, dep_time_local, arr_time_local, duration_min, aircraft, international) VALUES
    ('UA101', 'BLR', 'DEL', '06:15:00', '09:05:00', 170, 'Airbus A320neo',  FALSE),
    ('UA102', 'BLR', 'DEL', '13:40:00', '16:30:00', 170, 'Airbus A321neo',  FALSE),
    ('UA103', 'BLR', 'DEL', '19:25:00', '22:15:00', 170, 'Boeing 737 MAX 8',FALSE),
    ('UA104', 'DEL', 'BLR', '07:50:00', '10:45:00', 175, 'Airbus A320neo',  FALSE),
    ('UA105', 'DEL', 'BLR', '17:10:00', '20:05:00', 175, 'Airbus A321neo',  FALSE),
    ('UA202', 'MAA', 'HYD', '08:30:00', '09:50:00',  80, 'ATR 72-600',      FALSE),
    ('UA301', 'BOM', 'DEL', '09:00:00', '11:10:00', 130, 'Airbus A320neo',  FALSE),
    ('UA404', 'DEL', 'LHR', '02:35:00', '07:20:00', 585, 'Boeing 787-9',    TRUE),
    ('UA505', 'DEL', 'SIN', '23:15:00', '07:40:00', 335, 'Airbus A330-300', TRUE),
    ('UA606', 'BLR', 'DXB', '04:10:00', '07:05:00', 265, 'Boeing 787-8',    TRUE);

-- -------------------------------------------------------- dated departures ---
-- Instances for the next 45 days. The simulator materialises any further date
-- on demand, so a search never fails purely because a date was not pre-seeded.
INSERT INTO sim_flight_instance (flight_id, flight_date, status, seats_total, terminal, gate)
SELECT f.id,
       DATE_ADD(CURDATE(), INTERVAL n.num DAY),
       'ON_TIME',
       CASE WHEN f.international THEN 256 ELSE 180 END,
       CASE WHEN f.international THEN 'T3' ELSE 'T1' END,
       CONCAT(CASE WHEN f.international THEN 'B' ELSE 'A' END, 1 + (f.id % 18))
FROM sim_flight f
CROSS JOIN (
    SELECT a.d + b.t * 10 AS num
    FROM      (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
               UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
    CROSS JOIN (SELECT 0 t UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) b
) n
WHERE n.num BETWEEN 0 AND 45;

-- A few realistic irregular operations so FR-014 (live status, gate changes)
-- and FR-019 (disruption handling) have something to report.
UPDATE sim_flight_instance i
   JOIN sim_flight f ON f.id = i.flight_id
   SET i.status = 'DELAYED', i.delay_minutes = 55, i.gate = 'A12'
 WHERE f.flight_no = 'UA102' AND i.flight_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY);

UPDATE sim_flight_instance i
   JOIN sim_flight f ON f.id = i.flight_id
   SET i.status = 'CANCELLED', i.gate = NULL
 WHERE f.flight_no = 'UA202' AND i.flight_date = DATE_ADD(CURDATE(), INTERVAL 2 DAY);

-- ------------------------------------------------------------------- fares ---
-- Seven fare products per departure. Base fares scale by route type; taxes are
-- the KB_01 section 2.3 components (YQ 600 + PSF 236 + UDF 300 + GST 5%), with
-- an international surcharge where applicable.
INSERT INTO sim_fare (flight_instance_id, fare_class, cabin, fare_brand,
                      base_fare_inr, taxes_inr, price_inr,
                      refundable, changeable, change_fee_inr, cancel_fee_inr,
                      checked_baggage_kg, cabin_baggage_kg, seats_available, ffp_accrual_pct)
SELECT i.id,
       p.fare_class,
       p.cabin,
       p.fare_brand,
       ROUND(p.base_mult * mult.factor)                                        AS base_fare,
       ROUND(1136 + 0.05 * (p.base_mult * mult.factor) + mult.intl_surcharge)  AS taxes,
       ROUND(p.base_mult * mult.factor)
         + ROUND(1136 + 0.05 * (p.base_mult * mult.factor) + mult.intl_surcharge) AS total,
       p.refundable,
       p.changeable,
       p.change_fee,
       -- Saver and Super Saver forfeit the whole base fare (KB_04 2.1)
       CASE WHEN p.cancel_fee < 0 THEN ROUND(p.base_mult * mult.factor) ELSE p.cancel_fee END,
       CASE WHEN f.international AND p.cabin = 'ECONOMY'  THEN 25
            WHEN p.cabin = 'BUSINESS' THEN 35
            ELSE 15 END,
       CASE WHEN p.cabin = 'BUSINESS' THEN 10 ELSE 7 END,
       p.seats,
       p.ffp_pct
FROM sim_flight_instance i
JOIN sim_flight f ON f.id = i.flight_id
JOIN (
    SELECT 'dom'  AS k, 1.0 AS factor,    0 AS intl_surcharge
    UNION ALL SELECT 'gulf', 4.2, 2600
    UNION ALL SELECT 'asia', 3.6, 2200
    UNION ALL SELECT 'euro', 8.4, 5400
) mult
  ON mult.k = CASE WHEN f.destination = 'DXB' OR f.origin = 'DXB' THEN 'gulf'
                   WHEN f.destination = 'SIN' OR f.origin = 'SIN' THEN 'asia'
                   WHEN f.destination = 'LHR' OR f.origin = 'LHR' THEN 'euro'
                   ELSE 'dom' END
JOIN (
    -- cancel_fee = -1 means "whole base fare forfeited" (KB_04 2.1 Saver rules)
    SELECT 'Q' AS fare_class, 'ECONOMY'  AS cabin, 'Super Saver'    AS fare_brand,
           2200 AS base_mult, FALSE AS refundable, FALSE AS changeable,
              0 AS change_fee,   -1 AS cancel_fee,  9 AS seats,  10 AS ffp_pct
    UNION ALL SELECT 'K', 'ECONOMY',  'Saver',          2600, FALSE, FALSE,    0,   -1, 14,  25
    UNION ALL SELECT 'M', 'ECONOMY',  'Value',          3500, TRUE,  TRUE,  2000, 2000, 22,  50
    UNION ALL SELECT 'B', 'ECONOMY',  'Flex',           4200, TRUE,  TRUE,  1000, 1000, 17,  75
    UNION ALL SELECT 'Y', 'ECONOMY',  'Full Flex',      5000, TRUE,  TRUE,     0,    0, 11, 100
    UNION ALL SELECT 'D', 'BUSINESS', 'Business Saver', 9500, TRUE,  TRUE,  3000, 3000,  6, 125
    UNION ALL SELECT 'C', 'BUSINESS', 'Business Flex', 12000, TRUE,  TRUE,     0,    0,  4, 150
) p ON TRUE;

-- Sold-out inventory on one popular departure so FR-002 has a zero-availability
-- case and FR-019 (waitlist / overbooking) has something realistic to explain.
UPDATE sim_fare sf
   JOIN sim_flight_instance i ON i.id = sf.flight_instance_id
   JOIN sim_flight f ON f.id = i.flight_id
   SET sf.seats_available = 0
 WHERE f.flight_no = 'UA101'
   AND i.flight_date = DATE_ADD(CURDATE(), INTERVAL 3 DAY)
   AND sf.fare_class IN ('Q', 'K');

-- --------------------------------------------------------- demo bookings ---
-- Stable PNRs used by the demo script in docs/13-DEMO-SCRIPT.md.
INSERT INTO sim_booking (pnr, user_id, passenger_name, surname, contact_email, contact_phone,
                         flight_instance_id, fare_id, status, seat_number,
                         ffp_number, ffp_tier, amount_paid_inr)
SELECT 'B6X9K2',
       (SELECT id FROM app_user WHERE email = 'passenger@unitedair.demo'),
       'Ananya Rao', 'RAO', 'passenger@unitedair.demo', '+919876543210',
       i.id, sf.id, 'CONFIRMED', '14A', 'ZA-12345678', 'GOLD', sf.price_inr
FROM sim_flight_instance i
JOIN sim_flight f  ON f.id = i.flight_id AND f.flight_no = 'UA101'
JOIN sim_fare   sf ON sf.flight_instance_id = i.id AND sf.fare_class = 'M'
WHERE i.flight_date = DATE_ADD(CURDATE(), INTERVAL 7 DAY)
LIMIT 1;

INSERT INTO sim_booking (pnr, user_id, passenger_name, surname, contact_email, contact_phone,
                         flight_instance_id, fare_id, status, seat_number,
                         ffp_number, ffp_tier, amount_paid_inr)
SELECT 'H3PL8M',
       (SELECT id FROM app_user WHERE email = 'passenger@unitedair.demo'),
       'Ananya Rao', 'RAO', 'passenger@unitedair.demo', '+919876543210',
       i.id, sf.id, 'CONFIRMED', NULL, 'ZA-12345678', 'GOLD', sf.price_inr
FROM sim_flight_instance i
JOIN sim_flight f  ON f.id = i.flight_id AND f.flight_no = 'UA404'
JOIN sim_fare   sf ON sf.flight_instance_id = i.id AND sf.fare_class = 'Q'
WHERE i.flight_date = DATE_ADD(CURDATE(), INTERVAL 21 DAY)
LIMIT 1;

-- A non-refundable Super Saver inside the check-in window, for the refund
-- eligibility walkthrough (FR-007).
INSERT INTO sim_booking (pnr, user_id, passenger_name, surname, contact_email, contact_phone,
                         flight_instance_id, fare_id, status, seat_number, amount_paid_inr)
SELECT 'T7QW4Z', NULL,
       'Rahul Sharma', 'SHARMA', 'rahul.sharma@example.com', '+919812345678',
       i.id, sf.id, 'CONFIRMED', '22C', sf.price_inr
FROM sim_flight_instance i
JOIN sim_flight f  ON f.id = i.flight_id AND f.flight_no = 'UA301'
JOIN sim_fare   sf ON sf.flight_instance_id = i.id AND sf.fare_class = 'Q'
WHERE i.flight_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY)
LIMIT 1;

-- An already-cancelled booking awaiting refund, for the refund-status path.
INSERT INTO sim_booking (pnr, user_id, passenger_name, surname, contact_email, contact_phone,
                         flight_instance_id, fare_id, status, amount_paid_inr,
                         refund_amount_inr, cancelled_at)
SELECT 'K2MN7V', NULL,
       'Meera Iyer', 'IYER', 'meera.iyer@example.com', '+919845612300',
       i.id, sf.id, 'REFUND_PENDING', sf.price_inr, sf.price_inr - 2000,
       DATE_SUB(NOW(), INTERVAL 3 DAY)
FROM sim_flight_instance i
JOIN sim_flight f  ON f.id = i.flight_id AND f.flight_no = 'UA105'
JOIN sim_fare   sf ON sf.flight_instance_id = i.id AND sf.fare_class = 'M'
WHERE i.flight_date = DATE_ADD(CURDATE(), INTERVAL 10 DAY)
LIMIT 1;
