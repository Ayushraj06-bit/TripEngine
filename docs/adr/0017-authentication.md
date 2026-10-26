# 17. Authentication: short JWTs, refresh-token families, a lockout and shared rate limits

- **Status:** Accepted
- **Date:** 2026-10-24
- **Recorded late:** decided in Phase 1 and changed since; written down in Phase 11, when the review
  found these decisions had no ADR.

## Context

Riders, drivers and admins sign in with a phone number and a password (PROJECT_SPEC section 3:
Spring Security's OAuth2 resource server, no hand-written JWT filters, BCrypt). Clients are a
mobile app, the simulator and the admin's browser. They talk REST and STOMP over WebSocket, and
several backend instances sit behind one load balancer. Passwords can be guessed, and tokens can
be stolen.

## Decision

### Two kinds of token

- **Access token:** a JWT signed with RS256, valid 15 minutes. Spring's resource server verifies it
  on every request, with nothing stored. Its subject is the user's id and it carries the role, so
  "who am I" always comes from it, never from the request (section 10).
- **Refresh token:** opaque, random, valid 7 days. Only its SHA-256 hash is stored, so a database
  read does not yield usable tokens. Each use spends it and issues the next.

Every instance must hold the same RSA key pair (`RIDEFLOW_RSA_PRIVATE_KEY` and `..._PUBLIC_KEY`).
Without one, an instance generates an ephemeral pair and warns, which suits a single process on a
laptop and nothing else.

### In a browser, the refresh token is a cookie no script can read

Added with the web app (Stage 1 of the front end, ADR 0019). A token in a JSON body ends up where
script can reach it, and one injected script would then hold a seven-day session. A browser sends
`X-RideFlow-Client: web`, and the refresh token comes back as a cookie instead of in the body:
- **`HttpOnly`:** never readable by script.
- **`SameSite=Strict`:** never sent with a request another site starts, so a forged request can
  neither renew nor end a session.
- **`Path=/api/v1/auth`:** sent to the sign-in endpoints and nowhere else.
- **`Secure`:** whenever the request arrived over HTTPS, as it always does behind the production
  proxy. Plain HTTP on a developer's machine leaves it off, since browsers would refuse it.

Refresh and logout read the cookie when no body is sent, and logout clears it. Every other client -
the simulator, the load tests - sends and receives the token in JSON, unchanged. The access token
stays in the web app's memory only, so a reload renews it silently from the cookie.

### Refresh-token families

A login starts a family, and each refresh continues it. Presenting a token that was already spent
or revoked means a replay or a theft, which cannot be told apart, so the whole family is revoked and
everyone holding it must log in again. Logout revokes the family too.

### Guessing passwords

- **Lockout:** ten wrong passwords lock the account for fifteen minutes. The count is recorded in a
  short transaction that locks the user's row, so ten guesses sent at once count as ten.
  - **Saying when it lifts:** `ACCOUNT_LOCKED` carries `lockedUntil` and a `Retry-After`, and the
    tenth failure itself answers with it, not the attempt after. Neither reveals more than the
    next attempt already did.
- **Rate limits:**
  - **Per phone:** 15 login attempts a minute. Deliberately above the lockout, so the lockout is
    what a guesser meets.
  - **Per client address:** 20 logins a minute and 3 registrations an hour.
  - **Where:** Bucket4j buckets in Redis, keyed by hashed phone or address, on the injected clock.
    Every instance counts against the same budget.
- **Timing:** an unknown phone is checked against a hash made at startup, so a login costs the same
  BCrypt work either way.
- **BCrypt outside transactions:** the hash runs with no database connection held; a burst of
  logins would otherwise take the whole pool (`docs/performance.md`).

### WebSocket

A STOMP client authenticates in its CONNECT frame with the access token. It may SEND only to its
role's `/app` destinations, and SUBSCRIBE only to its own `/user/queue`; admins may also subscribe to
the live map's topic. A session closes when the token it opened with expires, so a revoked user
does not keep a socket for days.

## Alternatives rejected

- **Sessions with cookies:** state on the server, and CSRF to defend, for clients that are mostly
  not browsers. The browser's refresh cookie is not that: it carries the same rotating token, is
  sent only to the sign-in endpoints, and SameSite=Strict keeps other sites from using it.
- **Long-lived access tokens:** nothing to revoke them with short of a deny list, which is the state
  JWTs exist to avoid.
- **An external identity provider:** the right answer for a real product, and out of scope here
  (section 1).
- **Rate limits in memory per instance:** the original implementation. Two instances behind a load
  balancer each allowed the whole limit, and Phase 11 made two instances the default demo.

## Consequences

- A stolen access token works until it expires, at most 15 minutes.
- Revoking a family logs out every device in it, including the innocent one, which is the price of
  telling theft from replay.
- Logins and registrations need Redis for their rate limits. While it is away they answer 503, as
  every request that needs Redis does, rather than run unlimited.
- A registration still says when a number is taken, which tells an attacker the account exists.
  The per-address limit bounds how fast that can be probed (`docs/security.md`).
