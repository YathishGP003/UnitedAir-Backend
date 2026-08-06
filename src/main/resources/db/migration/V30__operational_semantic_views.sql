-- Governed read-only business views for OperationalDataAgent.
-- Hidden owner_user_id columns are never selectable; server policy uses them
-- to inject authenticated passenger ownership predicates.

CREATE OR REPLACE VIEW v_ai_airport AS
SELECT code, city, name, country, tz AS timezone, domestic
FROM sim_airport;

CREATE OR REPLACE VIEW v_ai_route AS
SELECT f.flight_no, f.origin, oa.city AS origin_city,
       f.destination, da.city AS destination_city,
       f.duration_min AS duration_minutes, f.aircraft, f.international
FROM sim_flight f
JOIN sim_airport oa ON oa.code = f.origin
JOIN sim_airport da ON da.code = f.destination;

CREATE OR REPLACE VIEW v_ai_flight_schedule AS
SELECT flight_no, origin, destination,
       dep_time_local AS departure_time,
       arr_time_local AS arrival_time,
       duration_min AS duration_minutes,
       aircraft, international
FROM sim_flight;

CREATE OR REPLACE VIEW v_ai_flight_instance AS
SELECT i.id AS instance_id, f.flight_no, f.origin, f.destination,
       i.flight_date, i.status, i.delay_minutes,
       i.terminal, i.gate, i.belt, i.updated_at
FROM sim_flight_instance i
JOIN sim_flight f ON f.id = i.flight_id;

CREATE OR REPLACE VIEW v_ai_flight_inventory AS
SELECT i.id AS instance_id, f.flight_no, f.origin, f.destination,
       i.flight_date, sf.cabin, sf.fare_class, sf.fare_brand,
       sf.price_inr, sf.refundable, sf.changeable,
       sf.checked_baggage_kg, sf.cabin_baggage_kg, sf.seats_available
FROM sim_flight_instance i
JOIN sim_flight f ON f.id = i.flight_id
JOIN sim_fare sf ON sf.flight_instance_id = i.id;

CREATE OR REPLACE VIEW v_ai_seat_inventory AS
SELECT i.id AS instance_id, f.flight_no, i.flight_date,
       s.seat_number, s.cabin, s.seat_type, s.extra_legroom,
       s.exit_row, s.fee_inr,
       (NOT s.occupied AND NOT s.blocked) AS available
FROM sim_seat s
JOIN sim_flight_instance i ON i.id = s.flight_instance_id
JOIN sim_flight f ON f.id = i.flight_id;

CREATE OR REPLACE VIEW v_ai_booking AS
SELECT b.user_id AS owner_user_id, b.pnr, b.status,
       f.flight_no, f.origin, f.destination, i.flight_date,
       i.status AS flight_status, sf.cabin, sf.fare_class, sf.fare_brand,
       sf.refundable, sf.changeable, b.amount_paid_inr,
       b.refund_amount_inr, b.refund_status, b.seat_number,
       b.special_service, b.booked_at, b.cancelled_at
FROM sim_booking b
JOIN sim_flight_instance i ON i.id = b.flight_instance_id
JOIN sim_flight f ON f.id = i.flight_id
JOIN sim_fare sf ON sf.id = b.fare_id;

CREATE OR REPLACE VIEW v_ai_checkin AS
SELECT b.user_id AS owner_user_id, b.pnr, f.flight_no, i.flight_date,
       (c.id IS NOT NULL) AS checked_in, c.checked_in_at,
       COALESCE(c.seat, b.seat_number) AS seat_number,
       c.boarding_gate, c.channel, c.sequence_no AS sequence_number
FROM sim_booking b
JOIN sim_flight_instance i ON i.id = b.flight_instance_id
JOIN sim_flight f ON f.id = i.flight_id
LEFT JOIN sim_checkin c ON c.booking_id = b.id;

CREATE OR REPLACE VIEW v_ai_meal AS
SELECT f.flight_no, i.flight_date, f.origin, f.destination,
       m.meal_service, m.available_codes, m.updated_at
FROM sim_flight_meal_service m
JOIN sim_flight_instance i ON i.id = m.flight_instance_id
JOIN sim_flight f ON f.id = i.flight_id;

CREATE OR REPLACE VIEW v_ai_special_service AS
SELECT b.user_id AS owner_user_id, b.pnr, f.flight_no, i.flight_date,
       b.special_service, b.status AS booking_status
FROM sim_booking b
JOIN sim_flight_instance i ON i.id = b.flight_instance_id
JOIN sim_flight f ON f.id = i.flight_id;

CREATE OR REPLACE VIEW v_ai_payment AS
SELECT p.user_id AS owner_user_id, p.payment_uuid AS payment_reference,
       b.pnr, p.method, p.status, p.amount_inr, p.currency,
       p.authorized_at, p.captured_at, p.refunded_at,
       p.created_at, p.updated_at
FROM sim_payment p
LEFT JOIN sim_booking b ON b.id = p.booking_id;

CREATE OR REPLACE VIEW v_ai_refund_case AS
SELECT r.passenger_user_id AS owner_user_id, r.case_uuid AS case_reference,
       b.pnr, r.status, r.fare_brand, r.amount_paid_inr,
       r.cancellation_fee_inr, r.refund_amount_inr, r.payment_method,
       r.due_at, r.assigned_to, r.created_at, r.updated_at, r.completed_at
FROM refund_work_item r
JOIN sim_booking b ON b.id = r.booking_id;

CREATE OR REPLACE VIEW v_ai_refund_history AS
SELECT r.passenger_user_id AS owner_user_id, r.case_uuid AS case_reference,
       b.pnr, h.from_status, h.to_status, h.note, h.changed_at
FROM refund_work_item_history h
JOIN refund_work_item r ON r.id = h.refund_work_item_id
JOIN sim_booking b ON b.id = r.booking_id;

CREATE OR REPLACE VIEW v_ai_escalation AS
SELECT case_uuid AS case_reference, reason, target_queue, priority,
       status, created_at, resolved_at
FROM escalation_case;

CREATE OR REPLACE VIEW v_ai_operational_decision AS
SELECT decision_uuid AS decision_reference, decision_type, outcome,
       actor_role, pnr_display, reason, source_policy_code,
       source_policy_section, created_at, decided_at
FROM operational_decision;

CREATE OR REPLACE VIEW v_ai_audit_event AS
SELECT event_type, actor_role, created_at
FROM audit_event;
