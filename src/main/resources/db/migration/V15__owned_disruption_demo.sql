-- Secure passenger-owned booking for the disruption-recovery demonstration.
INSERT INTO sim_booking (pnr, user_id, passenger_name, surname, contact_email, contact_phone,
                         flight_instance_id, fare_id, status, seat_number,
                         ffp_number, ffp_tier, amount_paid_inr)
SELECT 'R8CV2N',
       (SELECT id FROM app_user WHERE email = 'passenger@unitedair.demo'),
       'Ananya Rao', 'RAO', 'passenger@unitedair.demo', '+919876543210',
       i.id, sf.id, 'CONFIRMED', '8A', 'ZA-12345678', 'GOLD', sf.price_inr
FROM sim_flight_instance i
JOIN sim_flight f  ON f.id = i.flight_id AND f.flight_no = 'UA202'
JOIN sim_fare sf ON sf.flight_instance_id = i.id AND sf.fare_class = 'M'
WHERE i.flight_date = DATE_ADD(CURDATE(), INTERVAL 2 DAY)
  AND NOT EXISTS (SELECT 1 FROM sim_booking WHERE pnr = 'R8CV2N')
LIMIT 1;
