# 8. Fare quotes: priced once, used once, expired by our clock

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

A quote is a promise: this price, for this rider, for this vehicle type, for five
minutes (`quote-ttl-minutes`). The rider asks for quotes, picks one, and booking
(Phase 4) consumes it. PROJECT_SPEC section 7 stores it as `quote:{quoteId}` in
Redis, and section 8 has booking consume it with `GETDEL`.

Three questions come up: how the price is built, how "used once" is enforced, and
who decides when a quote has expired.

## Decision

### The price

`fare = max(minimumFare, base + perKm x km + perMinute x minutes) x surge`,
rounded HALF_UP to a whole rupee, in paise.

- The minimum fare applies **before** surge, so a short trip at 2.5x costs 2.5
  minimum fares. Surge measures demand, and a short trip at peak is still a peak
  trip.
- The breakdown always adds up to the total. Rounding to a rupee leaves up to 50
  paise unaccounted for, so the breakdown carries a signed rounding line (-49 to
  +50 paise), and `FareBreakdown` refuses to be constructed if its lines do not sum
  to the total.
- Surge is clamped to at least 1.0, and any failure to read it prices at 1.0. A
  broken surge engine should cost revenue, not stop riders booking.
- A vehicle type with no rate card in force is left out of the response and
  logged; the other types are still quoted. No type at all is a deployment fault
  and a 500.
- Rate cards are cached for `rate-card-refresh-seconds`, but which card is active
  is decided on every read, against the injected clock. A card with a future
  `effective_from` takes over at that exact instant, with no refresh having to
  happen at the right moment.

### Used once: compare-and-delete, not `GETDEL`

`GETDEL` deletes first and checks afterwards. Anyone holding somebody else's quote
id would destroy it: they get a 404, and the owner's booking then fails with
`QUOTE_EXPIRED` for no reason they can see.

`quote_consume.lua` checks expiry, owner and vehicle type, and deletes only when
all three pass. A mismatch deletes nothing. It is one script, so it is atomic: of
any number of concurrent attempts, exactly one gets the quote (tested with ten
threads).

`QuoteConsumer.restore` puts a consumed quote back if the booking transaction
fails after consuming it. It keeps the original expiry, so a restore can never
extend a quote's life.

### Expiry: our clock, not Redis's

`expiresAt` is stored in the quote, in milliseconds, and the script compares it
with the application's `Clock`, passed in as an argument. The key's Redis TTL is
the quote's life plus 30 seconds, and is garbage collection only.

Two clocks deciding one thing would disagree at the edges. Redis's clock also
cannot be moved by a test, so expiry would be untestable without sleeping, which
PROJECT_SPEC section 12 forbids. The grace period means the TTL never removes a
quote the application still considers valid.

### Two deliberate deviations from PROJECT_SPEC section 10

**410 Gone for an expired, used or unknown quote.** Section 10's list has no 410,
but it is the precise meaning here: the quote existed and is gone for good, and
the client's next step is different from a 404 or a 422 - ask for a new quote.

Another rider's quote is still a 404 `QUOTE_NOT_FOUND`, as section 10 requires.
That does make a live quote id answer differently from an unknown one. It is
accepted: quote ids are UUIDv7 with 74 random bits, so telling the two apart
requires already holding a leaked id, and all it reveals is that an unexpired
quote exists. It exposes no contents and no owner, and the quote cannot be
consumed or destroyed through it.

**No `Idempotency-Key` on `POST /api/v1/fares/quotes`.** Section 10 requires one
on every POST that creates something. Quotes are created, but repeating the
request is harmless: the rider gets fresh quotes and the old ones expire unused.
Nothing is booked or charged until a quote is consumed, and booking carries an
`Idempotency-Key`. The endpoint is rate limited to 30 requests a minute per rider
instead.

## Consequences

- Consuming a quote costs one Redis round trip, but the rules now live in Lua,
  which the compiler cannot check. The script is tested against real Redis.
  Making it delete before checking, or treating the expiry instant as still valid,
  each fails a test.
- A failed attempt with the wrong rider or vehicle type leaves the quote intact.
- Application instances must keep their clocks in sync, which they already need
  for JWT validation. Skew between instances shifts a quote's expiry by the skew.
- The per-rider quote limit is in memory, per instance, like the login limits (see
  `KeyedRateLimiter`). With several instances the effective limit multiplies until
  it moves to Bucket4j on Redis, as section 3 intends.
