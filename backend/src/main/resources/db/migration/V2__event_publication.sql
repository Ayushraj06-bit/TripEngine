-- Spring Modulith's event publication registry: the transactional outbox behind
-- PROJECT_SPEC section 5's "no event is ever lost" guarantee.
--
-- Required from Phase 0, not from the first published event: Modulith's
-- republish-outstanding-events-on-restart reads this table during startup, so the
-- application does not start without it.
--
-- Copied verbatim from spring-modulith-events-jdbc 2.1.1,
-- org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql, so it
-- matches what the library expects. Do not hand-edit the columns; take the next
-- version's file from the jar if Modulith changes the schema.

CREATE TABLE event_publication
(
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);
