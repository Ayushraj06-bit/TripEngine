# 13. A mock payment gateway, and webhooks signed with HMAC

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Riders top up their wallets through a payment gateway. PROJECT_SPEC section 2
puts a real gateway out of scope, but the flow around one is in scope:
- start a payment and send the rider to pay
- learn the outcome from a webhook
- survive webhooks that arrive late, twice, forged, or not at all

## Decision

**The flow a hosted gateway uses.**
1. `POST /api/v1/wallet/topups` creates a PENDING top-up, asks the gateway for
   a payment, and returns its URL.
2. The rider pays there.
3. The gateway calls `POST /api/v1/webhooks/payment-gateway` with the outcome.

The wallet is credited only by the webhook, never by the client saying it paid.

**`PaymentGateway` is the seam.** `MockPaymentGateway` is its only
implementation. A real gateway replaces it there and nothing else changes. The
mock behaves like a hosted gateway's test mode:
- Its payment page, `POST /mock-gateway/payments/{ref}/pay`, makes it send a
  signed webhook over HTTP to our own endpoint, through the same filters and
  checks a real one would pass.
- Whoever pays can choose the outcome and the delay, the way test card numbers
  do. `mock-gateway.outcome` and `delay-ms` are the defaults.
- `mock-gateway.unavailable` makes it refuse to start payments, which answers
  503 with a Retry-After.
- The page hands out money to whoever calls it, so it only exists while
  `mock-gateway.enabled` is true.

**Starting a top-up is idempotent by the rider's `Idempotency-Key`**, stored on
the top-up row and unique per rider:
- The same key finds the same top-up.
- The same key with another amount answers 422 `IDEMPOTENCY_KEY_REUSED`.
- The top-up id is the gateway's own idempotency key, so asking twice, even
  after a 503, never takes two payments.
- The gateway call is made outside any database transaction (PROJECT_SPEC
  section 11).

**Webhooks are signed.** The header `X-Gateway-Signature: v1=<hex>` carries the
HMAC-SHA256 of the timestamp, a dot, and the raw body, under a shared secret.
`X-Gateway-Timestamp` carries the timestamp in epoch seconds.
- The signature is checked against the raw body before anything is parsed, and
  compared in constant time.
- A timestamp more than `webhook-tolerance-seconds` (5 minutes) from now is
  refused. The timestamp is signed, so an old delivery cannot be replayed with a
  new one.
- Anything wrong answers 401 `INVALID_SIGNATURE`.
- The endpoint needs no user token. Its own security chain, ahead of the
  `/api/**` chain, lets it through; the signature is its only credential.

**Webhooks are idempotent.** Gateways retry until they get a 2xx.
- A conditional UPDATE moves the top-up from PENDING to SUCCEEDED or FAILED. Of
  any number of deliveries, one finds it PENDING.
- That one posts the TOPUP transaction, which is itself unique by top-up.
- Every delivery answers 204, so the retries stop.
- A failed payment is final: a later success for it changes nothing.

**The secret.** It is set with `RIDEFLOW_GATEWAY_WEBHOOK_SECRET` and never
committed.
- Left blank, each process generates its own and logs a warning. That works
  for one instance only.
- The two-instance compose file refuses to start without one.

## Consequences

- Top-ups can be developed and tested end to end, failures and delays
  included, with no third party.
- A top-up whose webhook never comes stays PENDING. Nothing expires it yet; the
  runbook says what to check.
- Replacing the mock means implementing `PaymentGateway` and verifying the real
  gateway's signature scheme in `WebhookSignatures`. Idempotency, crediting and
  settlement stay as they are.
