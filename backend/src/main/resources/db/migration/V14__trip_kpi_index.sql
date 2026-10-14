-- Phase 9: the admin live map counts the trips requested in the last few minutes, every
-- two seconds. Every other index on requested_at leads with a rider, a driver, or a status.
CREATE INDEX trips_requested_at_idx ON trips (requested_at);
