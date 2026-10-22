-- A throwaway copy of the schema, with a year of trips for a mid-size city, to read query plans
-- at a realistic size. Foreign keys are dropped: they never change a read plan, and the users,
-- drivers and rate cards they point at are not what is being measured.
\timing on
ALTER TABLE trips DROP CONSTRAINT IF EXISTS trips_rider_id_fkey;
ALTER TABLE trips DROP CONSTRAINT IF EXISTS trips_driver_id_fkey;
ALTER TABLE trips DROP CONSTRAINT IF EXISTS trips_rate_card_id_fkey;
ALTER TABLE trip_events DROP CONSTRAINT IF EXISTS trip_events_trip_id_fkey;
ALTER TABLE trip_events DISABLE TRIGGER USER;

-- 1,000,000 finished trips over 365 days: 20,000 riders, 2,000 drivers. Rider 1 is a heavy
-- user with 5,000 trips, to page deep into. 85% completed, 10% cancelled, 5% no driver.
INSERT INTO trips (id, rider_id, driver_id, vehicle_type, status, pickup_lat, pickup_lng,
                   drop_lat, drop_lng, quoted_fare_paise, surge_multiplier, rate_card_id,
                   payment_method, payment_status, final_fare_paise, distance_m, duration_s,
                   cancelled_by, requested_at, assigned_at, started_at, completed_at,
                   cancelled_at, no_driver_found_at)
SELECT gen_random_uuid(),
       ('00000000-0000-0000-0000-' || lpad(CASE WHEN g <= 5000 THEN 1 ELSE 2 + g % 19999 END::text, 12, '0'))::uuid,
       CASE WHEN g % 20 = 0 THEN NULL
            ELSE ('00000000-0000-0000-0001-' || lpad((g % 2000)::text, 12, '0'))::uuid END,
       (ARRAY['BIKE','AUTO','CAR'])[1 + g % 3],
       CASE WHEN g % 20 = 0 THEN 'NO_DRIVER_FOUND' WHEN g % 10 = 1 THEN 'CANCELLED' ELSE 'COMPLETED' END,
       12.97, 77.59, 12.99, 77.62, 25000, 1.0, gen_random_uuid(), 'CASH', 'PAID',
       CASE WHEN g % 20 <> 0 AND g % 10 <> 1 THEN 25000 END, 4000, 900,
       CASE WHEN g % 10 = 1 AND g % 20 <> 0 THEN 'RIDER' END,
       t, t + interval '20 s', t + interval '5 min', t + interval '20 min',
       CASE WHEN g % 10 = 1 AND g % 20 <> 0 THEN t + interval '2 min' END,
       CASE WHEN g % 20 = 0 THEN t + interval '4 min' END
FROM (SELECT g, now() - interval '365 days' + (g * interval '31.5 s') AS t
      FROM generate_series(1, 1000000) g) s;

-- What is under way now: 100 waiting for a driver, 500 riding (2 flagged).
INSERT INTO trips (id, rider_id, driver_id, vehicle_type, status, pickup_lat, pickup_lng,
                   drop_lat, drop_lng, quoted_fare_paise, surge_multiplier, rate_card_id,
                   payment_method, payment_status, distance_m, duration_s, requested_at,
                   assigned_at, started_at, flagged_reason)
SELECT gen_random_uuid(),
       ('00000000-0000-0000-0002-' || lpad(g::text, 12, '0'))::uuid,
       CASE WHEN g > 100 THEN ('00000000-0000-0000-0003-' || lpad(g::text, 12, '0'))::uuid END,
       'CAR', CASE WHEN g <= 100 THEN 'REQUESTED' ELSE 'IN_PROGRESS' END,
       12.97, 77.59, 12.99, 77.62, 25000, 1.0, gen_random_uuid(), 'CASH', 'UNPAID', 4000, 900,
       now() - g * interval '2 s',
       CASE WHEN g > 100 THEN now() - g * interval '2 s' + interval '20 s' END,
       CASE WHEN g > 100 THEN now() - g * interval '2 s' + interval '60 s' END,
       CASE WHEN g IN (300, 400) THEN 'silent driver' END
FROM generate_series(1, 600) g;

-- The audit log: booking, and one change after it, per trip.
INSERT INTO trip_events (id, trip_id, from_status, to_status, actor_type, actor_id, occurred_at)
SELECT gen_random_uuid(), id, NULL, 'REQUESTED', 'RIDER', rider_id, requested_at FROM trips;
INSERT INTO trip_events (id, trip_id, from_status, to_status, actor_type, actor_id, occurred_at)
SELECT gen_random_uuid(), id, 'REQUESTED', status, 'SYSTEM', NULL, requested_at + interval '20 s'
FROM trips WHERE status <> 'REQUESTED';

VACUUM ANALYZE trips;
VACUUM ANALYZE trip_events;
SELECT count(*) AS trips, pg_size_pretty(pg_total_relation_size('trips')) AS trips_size FROM trips;
