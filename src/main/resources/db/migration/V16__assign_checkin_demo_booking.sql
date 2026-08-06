-- T7QW4Z is the documented Passenger check-in walkthrough, so attach the
-- seeded record to the demo Passenger now that ownership is enforced.
UPDATE sim_booking
SET user_id = (SELECT id FROM app_user WHERE email = 'passenger@unitedair.demo'),
    passenger_name = 'Ananya Rao',
    surname = 'RAO',
    contact_email = 'passenger@unitedair.demo'
WHERE pnr = 'T7QW4Z';
