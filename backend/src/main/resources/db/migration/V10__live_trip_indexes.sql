-- Flagged trips for admin review (Phase 6), newest first. Partial, because flags are
-- rare: the index holds only the trips anyone will ever list.
CREATE INDEX trips_flagged_idx ON trips (id DESC) WHERE flagged_reason IS NOT NULL;

-- Trips in progress and not yet flagged, oldest first, for the silent-driver check
-- that runs every minute. Partial, so it stays as small as the rides under way now.
CREATE INDEX trips_in_progress_idx ON trips (started_at, id)
    WHERE status = 'IN_PROGRESS' AND flagged_reason IS NULL;
