-- Stored responses for Idempotency-Key requests (PROJECT_SPEC sections 7 and 10).
--
-- A row is inserted - claimed - before any work starts, and the response is filled
-- in when the work finishes. A row with no response is a request still in flight.

CREATE TABLE idempotency_keys (
    user_id         uuid        NOT NULL,
    key             text        NOT NULL,
    -- SHA-256 of the endpoint and the request body, to spot a key reused for a
    -- different request.
    request_hash    text        NOT NULL,
    response_status int,
    response_body   jsonb,
    created_at      timestamptz NOT NULL,

    PRIMARY KEY (user_id, key),
    CONSTRAINT idempotency_keys_key_length CHECK (length(key) BETWEEN 1 AND 255),
    CONSTRAINT idempotency_keys_response_complete CHECK (
        (response_status IS NULL) = (response_body IS NULL))
);

-- For the purge of keys older than the retention period.
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);
