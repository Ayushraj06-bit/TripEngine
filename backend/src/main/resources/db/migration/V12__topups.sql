-- Wallet top-ups through the payment gateway (Phase 7, ADR 0013).
--
-- The idempotency key lives on the row itself: the same key from the same rider
-- finds the same top-up, so a retried request never asks the gateway for a second
-- payment.
CREATE TABLE topups (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users (id),
    amount_paise    bigint      NOT NULL CHECK (amount_paise > 0),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    gateway_ref     text        UNIQUE,
    idempotency_key text        NOT NULL,
    created_at      timestamptz NOT NULL,
    completed_at    timestamptz,
    CONSTRAINT topups_idempotency_unique UNIQUE (user_id, idempotency_key)
);
