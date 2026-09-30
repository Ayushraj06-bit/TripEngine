-- Rate cards (PROJECT_SPEC section 7), seeded with the section 9 sample values as
-- version 1.
--
-- Rows are never updated. A price change is a new version with a later
-- effective_from, and every trip records the rate_card_id it was quoted on, so a
-- fare can always be explained by the exact rates that produced it.

CREATE TABLE rate_cards (
    id                     uuid        PRIMARY KEY,
    vehicle_type           text        NOT NULL,
    version                int         NOT NULL,
    base_paise             bigint      NOT NULL,
    per_km_paise           bigint      NOT NULL,
    per_min_paise          bigint      NOT NULL,
    min_fare_paise         bigint      NOT NULL,
    waiting_per_min_paise  bigint      NOT NULL,
    cancellation_fee_paise bigint      NOT NULL,
    effective_from         timestamptz NOT NULL,

    CONSTRAINT rate_cards_type_version_unique UNIQUE (vehicle_type, version),
    CONSTRAINT rate_cards_vehicle_type_valid CHECK (vehicle_type IN ('BIKE', 'AUTO', 'CAR')),
    CONSTRAINT rate_cards_version_positive CHECK (version > 0),
    CONSTRAINT rate_cards_amounts_non_negative CHECK (
        base_paise >= 0 AND per_km_paise >= 0 AND per_min_paise >= 0
        AND min_fare_paise >= 0 AND waiting_per_min_paise >= 0
        AND cancellation_fee_paise >= 0)
);

-- The active card per type is the latest effective_from not in the future.
CREATE INDEX rate_cards_active_idx ON rate_cards (vehicle_type, effective_from DESC);

INSERT INTO rate_cards (id, vehicle_type, version, base_paise, per_km_paise, per_min_paise,
                        min_fare_paise, waiting_per_min_paise, cancellation_fee_paise,
                        effective_from)
VALUES
    ('01940000-0000-7000-8000-000000000001', 'BIKE', 1, 2000,  700, 100, 3000, 150, 3000,
     '2026-01-01T00:00:00Z'),
    ('01940000-0000-7000-8000-000000000002', 'AUTO', 1, 3000, 1300, 150, 4000, 200, 4000,
     '2026-01-01T00:00:00Z'),
    ('01940000-0000-7000-8000-000000000003', 'CAR',  1, 5000, 1600, 200, 8000, 300, 5000,
     '2026-01-01T00:00:00Z');
