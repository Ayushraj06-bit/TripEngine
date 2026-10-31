# 25. The wallet in the web app: the server's balance, and a payment's outcome asked, not guessed

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

Stage 7 gives riders their wallet and drivers their earnings. The backend keeps both in its
double-entry ledger (ADR 0012), and top-ups go through a gateway (ADR 0013). `GET /wallet` answers
the caller's own account, its balance and its movements, a page at a time. A rider tops up with
`POST /wallet/topups` under an Idempotency-Key. The answer carries a payment link, and the gateway
confirms the payment to the backend by webhook.

The demo's gateway is a test one. Its "payment page" is an endpoint on the backend, paid with a
`POST`. Nothing is pushed to the app when a top-up settles.

## Decision

### The balance is the server's

- **Read from `GET /wallet`, never added up from the movements, and never shown optimistically.**
  After a top-up, the wallet is read again.
- **Movements are newest first, a page at a time**, with "Show earlier" while the backend has
  more. Each one is named (ride, top-up, cancellation fee, refund, RideFlow's commission on a cash
  ride) and signed.
- **A balance below zero says what follows from it.** The backend refuses every booking until it
  is back, cash ones too. The rider is told how much brings it back. The driver is told it is
  commission owed on cash rides.

### A top-up's outcome is asked, not guessed

- **The top-up is asked again under the same Idempotency-Key**, every 2 seconds for up to 30
  seconds. The backend answers the same top-up with its status, so the app learns whether it
  succeeded or failed. It never infers that from the balance.
- **After 30 seconds, it says the payment is still being confirmed.** It does not say the payment
  failed.
- **The key is kept across retries of the same amount**, after a lost answer or the gateway being
  briefly down, so the rider pays once. Once the backend has answered how the top-up ended, the
  next one gets a new key.

### The gateway

- **The demo's test gateway is paid in place**, through this origin: the dev server and the demo's
  proxy forward `/mock-gateway` as they forward `/api`.
- **Any other payment link is opened as a page**, as a real gateway's would be.
- **Amounts are ₹100, ₹200, ₹500 or the rider's own, in whole rupees.** The limits are the
  backend's, and a refusal names them in rupees.

### Getting there

- **"Wallet" sits in the bar over the rider's map, and "Earnings" on the driver's screen.**
- **"Add money" appears wherever a wallet below zero is mentioned:** the refused booking ("an
  earlier ride is still unpaid") and a fare that took the wallet below zero.

## Consequences

- **A slow gateway is said to be slow, not to have failed.** The balance shows the money once the
  webhook has arrived; the wallet is read again whenever it is opened.
- **Paying a real gateway on its own page is not built further.** Coming back from that page
  re-reads the wallet. It does not resume asking how the top-up ended, because no real gateway is
  configured.
- **The earnings screen is read-only.** The backend has no way to pay drivers out, so the screen
  shows the account and nothing more.
