-- Trips (PROJECT_SPEC section 7).
--
-- The two partial unique indexes are the real guard against double booking and
-- double assignment. Application checks can race; these cannot. The trip module
-- translates their violations by name, so they are named deliberately.

CREATE TABLE trips (
    id                     uuid             PRIMARY KEY,
    rider_id               uuid             NOT NULL REFERENCES users (id),
    driver_id              uuid             REFERENCES drivers (user_id),
    vehicle_type           text             NOT NULL,
    status                 text             NOT NULL,
    pickup_lat             double precision NOT NULL,
    pickup_lng             double precision NOT NULL,
    drop_lat               double precision NOT NULL,
    drop_lng               double precision NOT NULL,
    quoted_fare_paise      bigint           NOT NULL,
    surge_multiplier       numeric(8, 4)    NOT NULL,
    -- The rates used, as a snapshot: rate cards are versioned and never updated.
    rate_card_id           uuid             NOT NULL REFERENCES rate_cards (id),
    payment_method         text             NOT NULL,
    payment_status         text             NOT NULL,
    -- Plaintext, readable by the rider after a reconnect, and cleared the moment the
    -- trip starts or ends (see the CHECK below). Never logged.
    otp                    text,
    otp_attempts           int              NOT NULL DEFAULT 0,
    otp_locked_until       timestamptz,
    final_fare_paise       bigint,
    -- The quoted route estimate. Phase 6 replaces these with the actual route.
    distance_m             bigint           NOT NULL,
    duration_s             bigint           NOT NULL,
    cancelled_by           text,
    cancel_reason          text,
    cancellation_fee_paise bigint,
    requested_at           timestamptz      NOT NULL,
    assigned_at            timestamptz,
    arrived_at             timestamptz,
    started_at             timestamptz,
    completed_at           timestamptz,
    cancelled_at           timestamptz,
    no_driver_found_at     timestamptz,
    flagged_reason         text,
    version                bigint           NOT NULL DEFAULT 0,

    CONSTRAINT trips_vehicle_type_valid CHECK (vehicle_type IN ('BIKE', 'AUTO', 'CAR')),
    CONSTRAINT trips_status_valid CHECK (status IN (
        'REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS',
        'COMPLETED', 'CANCELLED', 'NO_DRIVER_FOUND')),
    CONSTRAINT trips_payment_method_valid CHECK (payment_method IN ('WALLET', 'CASH')),
    CONSTRAINT trips_payment_status_valid CHECK (payment_status IN (
        'UNPAID', 'PAID', 'PENDING_SETTLEMENT', 'REFUNDED')),
    CONSTRAINT trips_cancelled_by_valid CHECK (cancelled_by IN ('RIDER', 'DRIVER')),
    CONSTRAINT trips_amounts_non_negative CHECK (
        quoted_fare_paise >= 0 AND final_fare_paise >= 0 AND cancellation_fee_paise >= 0),
    CONSTRAINT trips_surge_at_least_one CHECK (surge_multiplier >= 1),
    CONSTRAINT trips_route_non_negative CHECK (distance_m >= 0 AND duration_s >= 0),
    CONSTRAINT trips_otp_format CHECK (otp ~ '^[0-9]{4}$'),
    CONSTRAINT trips_otp_attempts_non_negative CHECK (otp_attempts >= 0),
    -- The OTP exists only between assignment and start. Enforced here so no code
    -- path can leave one behind.
    CONSTRAINT trips_otp_only_while_waiting CHECK (
        otp IS NULL OR status IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVED')),
    -- A trip waiting for a driver has none; a trip past assignment always has one.
    CONSTRAINT trips_driver_matches_status CHECK (
        (status = 'REQUESTED' AND driver_id IS NULL)
        OR (status IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS', 'COMPLETED')
            AND driver_id IS NOT NULL)
        OR status IN ('CANCELLED', 'NO_DRIVER_FOUND'))
);

-- One active trip per rider, and one per driver.
CREATE UNIQUE INDEX trips_one_active_per_rider ON trips (rider_id)
    WHERE status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS');
CREATE UNIQUE INDEX trips_one_active_per_driver ON trips (driver_id)
    WHERE status IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS');

-- History, newest first, for riders and for drivers.
CREATE INDEX trips_rider_history_idx ON trips (rider_id, requested_at DESC);
CREATE INDEX trips_driver_history_idx ON trips (driver_id, requested_at DESC);

-- Trips still waiting for a driver, for matching's recovery sweep (Phase 5).
CREATE INDEX trips_waiting_idx ON trips (requested_at) WHERE status = 'REQUESTED';
