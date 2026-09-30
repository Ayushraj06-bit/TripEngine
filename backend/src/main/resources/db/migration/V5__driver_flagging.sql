-- Admin review flag, raised when a driver's reported positions imply impossible
-- speeds (PROJECT_SPEC section 9: max-speed-kmph).
--
-- The strike counter itself lives in Redis, on the driver's state hash, because it
-- is hot-path state reset by the same Lua script that writes the position. Only the
-- durable outcome - this driver needs a human to look at them - belongs in Postgres,
-- where an admin can query it and where it survives a Redis flush.

ALTER TABLE drivers
    ADD COLUMN flagged_reason text,
    ADD COLUMN flagged_at     timestamptz;

ALTER TABLE drivers
    ADD CONSTRAINT drivers_flagged_together
        CHECK ((flagged_reason IS NULL) = (flagged_at IS NULL));

-- The admin review queue reads only flagged drivers, which stay a small minority.
CREATE INDEX drivers_flagged_idx ON drivers (flagged_at) WHERE flagged_reason IS NOT NULL;
