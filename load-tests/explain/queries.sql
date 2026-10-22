-- The queries as Hibernate and JdbcTemplate send them (bind values inlined), each run twice so the
-- second, warm run is the one read. Run against the database seed.sql filled (load-tests/README.md).
\pset pager off
\set heavy '''00000000-0000-0000-0000-000000000001'''
\set rider '''00000000-0000-0000-0000-000000000042'''
\set driver '''00000000-0000-0000-0001-000000000042'''

-- Page 2 of the heavy rider: the cursor is the 20th trip.
SELECT requested_at AS at, id AS tid FROM trips WHERE rider_id = :heavy
ORDER BY requested_at DESC, id DESC OFFSET 19 LIMIT 1 \gset

\echo '=== 1. Rider history, first page (typical rider)'
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips WHERE rider_id = :rider
ORDER BY requested_at DESC, id DESC FETCH FIRST 21 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips WHERE rider_id = :rider
ORDER BY requested_at DESC, id DESC FETCH FIRST 21 ROWS ONLY;

\echo '=== 2a. Rider history, page 2 of 5,000 trips, as sent before Phase 10'
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips WHERE rider_id = :heavy
  AND (requested_at < :'at' OR requested_at = :'at' AND id < :'tid')
ORDER BY requested_at DESC, id DESC FETCH FIRST 21 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips WHERE rider_id = :heavy
  AND (requested_at < :'at' OR requested_at = :'at' AND id < :'tid')
ORDER BY requested_at DESC, id DESC FETCH FIRST 21 ROWS ONLY;

\echo '=== 2b. Rider history, page 2 of 5,000 trips, as sent now'
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips WHERE rider_id = :heavy
  AND requested_at <= :'at' AND (requested_at < :'at' OR id < :'tid')
ORDER BY requested_at DESC, id DESC FETCH FIRST 21 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips WHERE rider_id = :heavy
  AND requested_at <= :'at' AND (requested_at < :'at' OR id < :'tid')
ORDER BY requested_at DESC, id DESC FETCH FIRST 21 ROWS ONLY;

\echo '=== 3. Driver history, first page'
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips WHERE driver_id = :driver
ORDER BY requested_at DESC, id DESC FETCH FIRST 21 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips WHERE driver_id = :driver
ORDER BY requested_at DESC, id DESC FETCH FIRST 21 ROWS ONLY;

\echo '=== 4. Recovery sweep (matching): trips waiting longer than 30 s'
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM (
  SELECT t.id, t.rider_id, t.vehicle_type, t.pickup_lat, t.pickup_lng, t.quoted_fare_paise,
         COALESCE((SELECT max(e.occurred_at) FROM trip_events e
                   WHERE e.trip_id = t.id AND e.to_status = 'REQUESTED'), t.requested_at) AS waiting_since
  FROM trips t WHERE t.status = 'REQUESTED' AND t.requested_at < now() - interval '30 s') w
WHERE w.waiting_since < now() - interval '30 s' ORDER BY w.waiting_since LIMIT 100;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM (
  SELECT t.id, t.rider_id, t.vehicle_type, t.pickup_lat, t.pickup_lng, t.quoted_fare_paise,
         COALESCE((SELECT max(e.occurred_at) FROM trip_events e
                   WHERE e.trip_id = t.id AND e.to_status = 'REQUESTED'), t.requested_at) AS waiting_since
  FROM trips t WHERE t.status = 'REQUESTED' AND t.requested_at < now() - interval '30 s') w
WHERE w.waiting_since < now() - interval '30 s' ORDER BY w.waiting_since LIMIT 100;

\echo '=== 5. Silent-driver check: rides started over 5 minutes ago, unflagged, first batch'
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips
WHERE status = 'IN_PROGRESS' AND flagged_reason IS NULL AND started_at < now() - interval '5 min'
ORDER BY started_at, id FETCH FIRST 100 ROWS ONLY;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM trips
WHERE status = 'IN_PROGRESS' AND flagged_reason IS NULL AND started_at < now() - interval '5 min'
ORDER BY started_at, id FETCH FIRST 100 ROWS ONLY;

\echo '=== 6. Live map KPIs: active trips, and the last 15 minutes'
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM trips
WHERE status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS');
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM trips
WHERE status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS');
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) AS requested,
       count(*) FILTER (WHERE status = 'NO_DRIVER_FOUND') AS no_driver,
       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
       avg(EXTRACT(EPOCH FROM assigned_at - requested_at)) AS seconds_to_assign
FROM trips WHERE requested_at >= now() - interval '15 min';
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) AS requested,
       count(*) FILTER (WHERE status = 'NO_DRIVER_FOUND') AS no_driver,
       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
       avg(EXTRACT(EPOCH FROM assigned_at - requested_at)) AS seconds_to_assign
FROM trips WHERE requested_at >= now() - interval '15 min';
