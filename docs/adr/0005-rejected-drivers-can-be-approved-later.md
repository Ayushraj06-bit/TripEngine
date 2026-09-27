# 5. A rejected driver can be approved later

- **Status:** Accepted
- **Date:** 2026-09-27

## Context

An admin reviewing a driver can reject them, and PROJECT_SPEC section 7 records
`approval_status` and a `rejection_reason`. What the spec does not say is whether
`REJECTED` is terminal.

Most rejections are not judgements about a person. They are a blurred photo of a
licence, a document that expired last week, a plate number typed wrong. Treating
those as permanent would be both wrong and expensive to undo.

Making it terminal also has a concrete side effect. A phone number is unique in
`users`, and a driver's id *is* their user id. A driver who had to re-register
could not reuse their own number, so support would end up deleting rows by hand
to let a legitimate driver back in — which is worse than allowing the transition
in the first place.

## Decision

`REJECTED` is not terminal. An admin may approve a previously rejected driver,
and `PENDING → APPROVED`, `PENDING → REJECTED`, `REJECTED → APPROVED` and
`APPROVED → REJECTED` are all permitted.

Approving clears `rejection_reason`, because the migration constrains
`(approval_status = 'REJECTED') = (rejection_reason IS NOT NULL)` — a stale
reason on an approved driver would fail that check, which is the database
enforcing the same rule the code does.

Rejecting forces the driver `OFFLINE` and publishes `DriverStatusChanged`, so a
driver rejected mid-shift leaves the live map rather than continuing to receive
offers.

Approval is idempotent. An admin double-clicking must not be an error, and the
review queue is a human interface where that will happen.

`approval_changed_at` records when the decision last moved, so the audit trail
survives even though `rejection_reason` is cleared on approval.

## Consequences

- A fixable document problem costs one admin action, not a re-registration.
- The audit story is weaker than it could be: only the most recent decision is
  kept, so a driver rejected twice for different reasons shows only the second.
  A full history belongs in an append-only table, which is worth adding if
  disputes ever need it. It is not worth it yet.
- Nothing stops an admin flip-flopping. There is no cooling-off period and no
  record of who decided, because there is no admin-action audit log yet. When
  one exists, it should cover this.
