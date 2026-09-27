-- Driver profile, approval and vehicle (PROJECT_SPEC section 7).

CREATE TABLE drivers (
    user_id                uuid        PRIMARY KEY REFERENCES users (id),
    approval_status        text        NOT NULL DEFAULT 'PENDING',
    status                 text        NOT NULL DEFAULT 'OFFLINE',
    -- Sum and count rather than an average, so a new rating is one increment and
    -- never has to re-read every past rating.
    rating_sum             int         NOT NULL DEFAULT 0,
    rating_count           int         NOT NULL DEFAULT 0,
    offers_received        int         NOT NULL DEFAULT 0,
    offers_accepted        int         NOT NULL DEFAULT 0,
    rejection_reason       text,
    approval_changed_at    timestamptz,
    last_status_change_at  timestamptz NOT NULL,
    -- Optimistic locking. The database is the final guard on going online or
    -- being assigned, per PROJECT_SPEC section 8.
    version                bigint      NOT NULL DEFAULT 0,

    CONSTRAINT drivers_approval_status_valid
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT drivers_status_valid
        CHECK (status IN ('OFFLINE', 'AVAILABLE', 'ON_TRIP')),
    CONSTRAINT drivers_rating_counts_non_negative
        CHECK (rating_sum >= 0 AND rating_count >= 0),
    CONSTRAINT drivers_offer_counts_sane
        CHECK (offers_received >= 0 AND offers_accepted >= 0
               AND offers_accepted <= offers_received),
    -- A rejection must say why; anything else must not carry a stale reason.
    CONSTRAINT drivers_rejection_reason_present
        CHECK ((approval_status = 'REJECTED') = (rejection_reason IS NOT NULL))
);

-- Admin listing: pending drivers, ordered by the UUIDv7 primary key, which is
-- already chronological, so the cursor is just the last id seen.
CREATE INDEX drivers_pending_idx ON drivers (user_id) WHERE approval_status = 'PENDING';

CREATE TABLE vehicles (
    id           uuid PRIMARY KEY,
    driver_id    uuid NOT NULL REFERENCES drivers (user_id),
    type         text NOT NULL,
    -- Stored normalised: uppercase, no spaces or dashes. "KA 01 AB 1234" and
    -- "ka01ab1234" therefore collide on the unique constraint.
    plate_number text NOT NULL,
    model        text NOT NULL,
    colour       text NOT NULL,

    -- One vehicle per driver for now; a driver swapping vehicles updates this row.
    CONSTRAINT vehicles_driver_unique UNIQUE (driver_id),
    CONSTRAINT vehicles_plate_unique UNIQUE (plate_number),
    CONSTRAINT vehicles_type_valid CHECK (type IN ('BIKE', 'AUTO', 'CAR')),
    CONSTRAINT vehicles_plate_normalised CHECK (plate_number ~ '^[A-Z0-9]+$')
);
