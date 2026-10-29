# 21. Prices and booking in the web app: the server's clock, one key per attempt

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

A fare is quoted for five minutes (`quote-ttl-minutes`), and booking it is the one request in the
app that spends money and starts something in the world. Two things can go wrong that the rider
cannot see. A phone's clock can be minutes out, so a countdown on the phone's clock would offer
prices the server has already let go. And a booking can be sent twice: a double tap, a retry
after the connection dropped, or a reload in the middle. The backend books once per
`Idempotency-Key` (ADR 0008), but only if the client sends the same key each time.

## Decision

### Prices count down on the server's clock

- **Every response's `Date` header** tells the client what time it is on the server. The
  difference from the device's clock is kept (`api/serverClock.ts`), with half a second added
  because the header is cut to the whole second.
- **A set of prices is pinned to the device's clock when it arrives**, using the offset from that
  same response, so later responses never move the countdown.
- **When the countdown ends**, the prices dim and the only way on is "Get new prices". The
  backend still has the last word: a booking with an expired quote is refused with
  `QUOTE_EXPIRED`, and the app fetches new prices.

### One Idempotency-Key per booking attempt

- **An attempt is a key plus the exact request** it was made for, kept in the tab's draft
  (session storage), so a reload keeps it.
- **The same request reuses the key**: after no answer (status 0), a server error (5xx), or
  `IDEMPOTENCY_KEY_IN_FLIGHT`. In those cases the app first asks `GET /trips/active` whether the
  booking was placed after all, and opens it if so.
- **A request the server refused outright gets a new key next time.** The backend stores the
  refusal with the key (`IdempotencyStore`), so the same key would only be refused again.
- **A changed request (another ride type or payment) is a new attempt**, so it can never be
  mistaken for `IDEMPOTENCY_KEY_REUSED`.
- **Keys are version 4 UUIDs.** `crypto.randomUUID` is used where the page is secure, and
  `getRandomValues` elsewhere.

### What the rider is told

- **Prices show the backend's own numbers**: the fare, its breakdown, the trip's distance and
  time, and surge when there is any. There is no arrival time, because the backend gives none.
- **Payment is cash unless the rider chooses the wallet.** A wallet lower than the fare is
  allowed, as the backend allows it, with a note: the wallet goes below zero, and the rider tops
  up before their next ride.
- **An unpaid earlier ride blocks every booking, cash included** (`OUTSTANDING_BALANCE`). The
  message says so.

## Consequences

- **The countdown is right to within about half a second**, whatever the phone's clock says.
- **A rider can tap Confirm as often as they like, or lose their connection mid-booking**, and get
  one ride.
- **The draft holds an Idempotency-Key between attempts.** It is not a secret: it only lets the
  same rider repeat the same booking.
