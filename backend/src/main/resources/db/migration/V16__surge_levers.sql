-- The admins' surge levers (ADR 0014). In PostgreSQL rather than Redis, which keeps nothing on disk:
-- a restart of Redis would otherwise turn surge back on by itself.

-- At most one row a cell, so the table stays as small as the city. A row past its expiry is ignored,
-- and replaced by the cell's next override.
CREATE TABLE surge_overrides (
    cell       text        PRIMARY KEY,
    multiplier numeric     NOT NULL,
    expires_at timestamptz NOT NULL,

    CONSTRAINT surge_overrides_multiplier_valid CHECK (multiplier >= 1)
);

-- The kill switch: exactly one row. No expiry, on purpose: a switch that expired would turn surge
-- back on by itself.
CREATE TABLE surge_switch (
    id  boolean PRIMARY KEY DEFAULT true,
    off boolean NOT NULL,

    CONSTRAINT surge_switch_one_row CHECK (id)
);

INSERT INTO surge_switch (off) VALUES (false);
