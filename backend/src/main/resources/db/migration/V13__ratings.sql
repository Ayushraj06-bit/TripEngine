-- Ratings (Phase 7): each side of a completed trip rates the other once.
CREATE TABLE ratings (
    id         uuid        PRIMARY KEY,
    trip_id    uuid        NOT NULL REFERENCES trips (id),
    rater_id   uuid        NOT NULL,
    rater_role text        NOT NULL CHECK (rater_role IN ('RIDER', 'DRIVER')),
    ratee_id   uuid        NOT NULL,
    stars      smallint    NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment    text        CHECK (char_length(comment) <= 500),
    created_at timestamptz NOT NULL,
    CONSTRAINT ratings_once_per_side UNIQUE (trip_id, rater_role)
);

-- Riders with a trip waiting on a top-up cannot book: checked on every booking, and
-- read again when a top-up settles them. Partial, so it holds only those trips.
CREATE INDEX trips_outstanding_idx ON trips (rider_id) WHERE payment_status = 'PENDING_SETTLEMENT';
