-- Identity: users and refresh tokens (PROJECT_SPEC section 7).

CREATE TABLE users (
    id                 uuid        PRIMARY KEY,
    -- Always stored normalised to E.164, so "98765 43210" and "+91-98765-43210"
    -- collide on this unique constraint rather than creating two accounts.
    phone              text        NOT NULL,
    name               text        NOT NULL,
    password_hash      text        NOT NULL,
    role               text        NOT NULL,
    failed_login_count int         NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,

    CONSTRAINT users_phone_unique UNIQUE (phone),
    -- E.164: a leading +, a non-zero country code, 8 to 15 digits in total.
    CONSTRAINT users_phone_e164 CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT users_role_valid CHECK (role IN ('RIDER', 'DRIVER', 'ADMIN')),
    CONSTRAINT users_failed_login_count_non_negative CHECK (failed_login_count >= 0)
);

CREATE TABLE refresh_tokens (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id),
    -- Only the hash is stored. A database leak must not hand out usable tokens.
    token_hash     text        NOT NULL,
    -- Every token rotated from one login shares a family. Replaying a token that was
    -- already spent revokes the whole family, which is how theft is detected.
    family_id      uuid        NOT NULL,
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    replaced_by_id uuid        REFERENCES refresh_tokens (id),
    created_at     timestamptz NOT NULL,

    CONSTRAINT refresh_tokens_hash_unique UNIQUE (token_hash)
);

CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
-- The rotation path looks a token up by hash and then revokes its family; the
-- partial index keeps that cheap as spent tokens accumulate.
CREATE INDEX refresh_tokens_live_idx ON refresh_tokens (family_id) WHERE revoked_at IS NULL;
