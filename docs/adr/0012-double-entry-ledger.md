# 12. A double-entry ledger, with the database as its guard

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Phase 7 moves money: riders top up wallets, trips and cancellation fees are
charged, drivers earn, the platform takes commission, and admins give refunds.
PROJECT_SPEC section 1 sets the goal "the ledger always balances to 0".

The money has to be right when everything goes wrong at once:

- trip events and gateway webhooks arrive more than once
- a top-up and a charge hit the same wallet at the same moment
- a rider's wallet does not cover their fare

It also has to be explainable afterwards, to the rider, the driver and the
auditor.

## Decision

**Double entry.** Every movement is one `ledger_transactions` row with two or
more `ledger_entries`, whose debits equal their credits. There are four
account types:

- `RIDER_WALLET`
- `DRIVER_EARNINGS`
- `PLATFORM_REVENUE`
- `PLATFORM_CLEARING`, the money the gateway has taken in

The platform's accounts are owned by the nil id. Every account is opened on
first use.

| Transaction | Debit | Credit |
|---|---|---|
| Top-up | clearing | rider wallet |
| Wallet trip | rider wallet (fare) | driver earnings (fare − commission), revenue (commission) |
| Cash trip | driver earnings (commission) | revenue (commission) |
| Cancellation fee | rider wallet | as a wallet trip |
| Refund | every credit of the original | every debit of the original |

**One sign rule.** Every balance is credits minus debits. Each transaction
balances, so the balances of all accounts always sum to zero. Reconciliation
checks exactly that.

**Commission** is the fare's `commission-percent`, rounded HALF_EVEN; the driver
gets the rest. The two parts always add up to the fare (PROJECT_SPEC section 7).

**Idempotent by type and reference.** `(type, reference_id)` is unique: the
trip for a charge, the top-up for a top-up, the original transaction for a
refund. The transaction row is inserted first with `ON CONFLICT DO NOTHING`.
A second posting inserts nothing and is skipped. A unique-violation exception
would instead abort the whole database transaction, including the caller's
other work.

**Balances are a cache, under a version.** `balance_paise` is updated in the
same transaction as the entries, through JPA's `@Version`.
- Two postings that read the same balance cannot both write theirs. The second
  fails at commit and runs again on the new balance, after a short random pause,
  up to `posting-max-attempts` times.
- Updates are flushed in primary-key order (`hibernate.order_updates`), so two
  postings touching the same accounts wait for each other instead of
  deadlocking.

**The database is the final guard.** V11 adds two triggers:
- one refuses any UPDATE or DELETE of an entry or a transaction, so corrections
  can only be new, reversing transactions
- one runs at commit and refuses any transaction whose entries do not balance,
  or that has fewer than two

A bug in the code can fail a posting. It cannot commit a wrong one.

**Insufficient balance is a debt, not a refusal.** A wallet charge is posted
even when the wallet cannot cover it.
- The wallet goes below zero, and the driver is paid regardless.
- The trip is marked `PENDING_SETTLEMENT`. Booking answers 422
  `OUTSTANDING_BALANCE` while any such trip exists.
- When a top-up or a refund brings the wallet back to zero or above, every
  pending trip of that rider is marked PAID.

Holding the charge back instead would need a queue of pending charges, would
make the driver wait for the rider, and would hide the debt from the ledger.

**Cancellation fees always come from the wallet**, whatever the payment
method: no cash changes hands for a ride that never happened.

**Trip payment status is the trip's.** `payment` decides it and calls `trip`'s
`TripPayments` inside its own transaction, so the ledger and the trip commit
together.
- The status is written by a conditional UPDATE that leaves the trip's version
  alone, like flags in Phase 6.
- `trip` publishes `TripPaymentStatusChanged`. It goes to Kafka with the other
  trip events, and to the rider and driver live.

**Reconciliation** runs in one REPEATABLE READ snapshot, nightly under ShedLock
and on demand for admins. It checks that:
- every transaction balances
- every cached balance equals the sum of its account's entries
- all balances total zero

Problems are logged and counted on the gauge `ledger_reconciliation_mismatches`.

## Consequences

- A rider can owe money, and the ledger says how much: their wallet's negative
  balance. Nothing has to be reconstructed from trip statuses.
- Every posting writes the single platform-revenue row. Under heavy load,
  postings queue on it and some retry. When load tests show it, the upgrade is
  to split revenue into sub-accounts by a hash of the trip, summed for reports.
- Reconciliation reads the whole ledger. That is fine nightly and on request,
  and it is kept off every hot path.
- The append-only rule has no override short of dropping the trigger. That is
  the intent: a fix is a reversing transaction, which leaves a trail.
