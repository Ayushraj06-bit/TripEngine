-- The trip audit log (PROJECT_SPEC section 7): one row per state change, written in
-- the same transaction as the change itself.

CREATE TABLE trip_events (
    id          uuid        PRIMARY KEY,
    trip_id     uuid        NOT NULL REFERENCES trips (id),
    -- Null only for the row that records the booking itself.
    from_status text,
    to_status   text        NOT NULL,
    actor_type  text        NOT NULL,
    -- Null when the system acted.
    actor_id    uuid,
    occurred_at timestamptz NOT NULL,
    metadata    jsonb       NOT NULL DEFAULT '{}',

    CONSTRAINT trip_events_actor_type_valid CHECK (actor_type IN ('RIDER', 'DRIVER', 'SYSTEM')),
    CONSTRAINT trip_events_actor_id_matches_type CHECK ((actor_type = 'SYSTEM') = (actor_id IS NULL))
);

CREATE INDEX trip_events_trip_idx ON trip_events (trip_id, occurred_at);

-- Append-only in fact, not just by convention: an audit log that can be edited
-- proves nothing. TRUNCATE, which tests use to reset, does not fire row triggers.
CREATE FUNCTION trip_events_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'trip_events is append-only';
END;
$$;

CREATE TRIGGER trip_events_append_only
    BEFORE UPDATE OR DELETE ON trip_events
    FOR EACH ROW EXECUTE FUNCTION trip_events_reject_change();
