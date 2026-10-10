-- The double-entry ledger (PROJECT_SPEC section 7, ADR 0012).
--
-- Every balance is credits minus debits, so the balances of all accounts always sum
-- to zero. balance_paise is a cache of the entries, kept in step in the same
-- transaction under the version; reconciliation checks the two still agree.

CREATE TABLE ledger_accounts (
    id            uuid   PRIMARY KEY,
    owner_type    text   NOT NULL CHECK (owner_type IN ('USER', 'PLATFORM')),
    owner_id      uuid   NOT NULL,
    account_type  text   NOT NULL CHECK (account_type IN
        ('RIDER_WALLET', 'DRIVER_EARNINGS', 'PLATFORM_REVENUE', 'PLATFORM_CLEARING')),
    balance_paise bigint NOT NULL DEFAULT 0,
    version       bigint NOT NULL DEFAULT 0,
    CONSTRAINT ledger_accounts_owner_unique UNIQUE (owner_type, owner_id, account_type)
);

-- reference_id is what the money moved for: a trip, a top-up, the transaction a
-- refund reverses. One transaction per type and reference makes every posting
-- idempotent: a redelivered event or webhook finds its transaction already there.
CREATE TABLE ledger_transactions (
    id           uuid        PRIMARY KEY,
    type         text        NOT NULL CHECK (type IN
        ('TOPUP', 'TRIP_CHARGE', 'CASH_COMMISSION', 'CANCELLATION_FEE', 'REFUND')),
    reference_id uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT ledger_transactions_reference_unique UNIQUE (type, reference_id)
);

CREATE TABLE ledger_entries (
    id             uuid   PRIMARY KEY,
    transaction_id uuid   NOT NULL REFERENCES ledger_transactions (id),
    account_id     uuid   NOT NULL REFERENCES ledger_accounts (id),
    direction      text   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_paise   bigint NOT NULL CHECK (amount_paise > 0)
);

CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);
-- A wallet's history, newest first: ids are UUIDv7, so id order is time order.
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id, id DESC);

-- History is never rewritten: a correction is a new, reversing transaction. Enforced
-- here rather than trusted to the code. (TRUNCATE, which tests use, fires no row
-- triggers.)
CREATE FUNCTION ledger_refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'The ledger is append-only: % on % refused', TG_OP, TG_TABLE_NAME;
END
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_refuse_change();

CREATE TRIGGER ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_refuse_change();

-- Every transaction balances, with two entries at least. Checked when the database
-- transaction commits - by then all of a ledger transaction's entries are in - so a
-- bug that writes half a posting cannot commit it.
CREATE FUNCTION ledger_check_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    checked uuid;
    net     bigint;
    legs    integer;
BEGIN
    IF TG_TABLE_NAME = 'ledger_transactions' THEN
        checked := NEW.id;
    ELSE
        checked := NEW.transaction_id;
    END IF;
    SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount_paise ELSE -amount_paise END), 0),
           count(*)
      INTO net, legs
      FROM ledger_entries
     WHERE transaction_id = checked;
    IF net <> 0 OR legs < 2 THEN
        RAISE EXCEPTION 'Ledger transaction % does not balance: net % paise over % entries',
            checked, net, legs;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();

CREATE CONSTRAINT TRIGGER ledger_transactions_balanced
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();
