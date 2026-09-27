-- ShedLock's lock table, so a @Scheduled job that must run once per cluster does.
--
-- Owned by Flyway rather than created by ShedLock, per PROJECT_SPEC section 7: every
-- schema change is a migration.
--
-- timestamptz rather than ShedLock's documented plain timestamp, per section 7's rule
-- that every timestamp is timestamptz in UTC. The provider is configured with
-- usingDbTime(), so these are compared against the database's now() and never against
-- an instance's own clock.

CREATE TABLE shedlock
(
    name       varchar(64)  NOT NULL,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL,
    PRIMARY KEY (name)
);
