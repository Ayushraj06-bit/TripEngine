# Security review

RideFlow against the OWASP Top 10 (2021), done in Phase 10 on 2026-10-22. For each category:
- what protects the system
- what this review found
- what was changed, and the test that keeps it changed

Findings fixed in earlier phases are listed where they belong, so the page shows the whole
picture.

| Category | Verdict | Changed in Phase 10 |
|---|---|---|
| A01 Broken access control | Sound | WebSocket handshake same-origin only |
| A02 Cryptographic failures | Sound, with deployment duties | None |
| A03 Injection | Sound | None |
| A04 Insecure design | Sound | `sim` profile refused alongside `prod` |
| A05 Security misconfiguration | Fixed | Actuator on an internal port; Swagger off in prod; live map CSP |
| A06 Vulnerable components | Fixed | Tomcat, Jackson and lz4-java upgraded past known CVEs |
| A07 Authentication failures | Fixed | Login time no longer reveals which phone numbers are registered |
| A08 Integrity failures | Fixed | Maven wrapper checks the distribution's checksum |
| A09 Logging and monitoring | Sound, one accepted risk | Log-masking test; alerts |
| A10 SSRF | Not exposed | None |

## A01 Broken access control

**In place.**
- Every endpoint states its roles with `@PreAuthorize`. Who the caller is always comes from the
  JWT subject, never from the request (PROJECT_SPEC section 10).
- Another user's trip answers 404, so its existence is not revealed.
- `SecurityMatrixTest` checks every endpoint against every kind of caller: 114 combinations.
- STOMP clients may send only to their role's `/app` destinations and subscribe only to their
  own `/user/queue/**`. The admin live-map topic is ADMIN-only (`StompSecurityIntegrationTest`).

**Found.** The WebSocket endpoint accepted handshakes from any origin (`*`). The token travels in
the CONNECT frame, not a cookie, so a hostile page could not act as a visitor. But it had no
reason to be allowed to try.

**Changed.** Same origin only, plus the patterns in `rideflow.web.allowed-origins` (empty by
default). Apps and the simulator send no `Origin` header and are unaffected.
`refusesAHandshakeFromAnotherSitesPage` covers it.

## A02 Cryptographic failures

**In place.**
- Access tokens are RS256 JWTs.
- Passwords use BCrypt.
- Refresh tokens are stored as hashes, rotated on use. A replayed token revokes its whole family.
- Gateway webhooks are HMAC-SHA256 signed, with a timestamp tolerance against replay.

**Deployment duties** (`docs/runbook.md`):
- **Keys:** set `RIDEFLOW_RSA_PRIVATE_KEY`/`PUBLIC_KEY` and `RIDEFLOW_GATEWAY_WEBHOOK_SECRET`. Without
  them each process makes its own and logs a warning, so a restart signs everyone out.
- **TLS:** terminate it at the load balancer. The local nginx listens on plain HTTP. Spring
  Security adds HSTS only to requests that arrived over HTTPS.

## A03 Injection

**In place.**
- SQL goes through JPA or `JdbcTemplate` with bound parameters. No query is built from input.
- Redis Lua scripts take user values as `ARGV`, never as script text.
- Jackson runs without polymorphic typing, so a payload cannot choose a class to instantiate.
- Logs are structured (ECS JSON), so a value cannot forge a log line.

**Found.** Nothing.

## A04 Insecure design

**In place.**
- **Rate limits:** per address on login and registration, per phone on login, and per rider on
  quotes.
- **Idempotency:** an `Idempotency-Key` on every write that books or moves money.
- **Trips:** partial unique indexes allow one active trip per rider and per driver.
- **OTP:** locks after three wrong codes.
- **Mock gateway:** off unless turned on.

**Found.** The `sim` profile lifts the auth rate limits and turns on the mock gateway, whose page
credits any wallet. Activated in production by mistake, it would remove brute-force protection
and hand out money.

**Changed.** With `prod` and `sim` both active, the application refuses to start
(`SimProfileGuard`, `SimProfileGuardTest`).

## A05 Security misconfiguration

**Found, and changed.**
- **Actuator:** metrics were protected only by the deny-all chain on the public port, so Prometheus
  could not scrape them at all. Actuator now runs on its own port, 8090, which no load balancer
  publishes. The public port offers only `/livez` and `/readyz` (`ManagementPortIntegrationTest`).
- **API docs:** Swagger UI and `/v3/api-docs` were on in every profile. They are off in `prod`.
- **Live map page:** it had no Content-Security-Policy. Its script moved to `live-map.js`, and the
  page forbids inline scripts. Scripts come only from this server and Leaflet's CDN, the latter
  pinned with subresource integrity. The page also cannot be framed. Without this, an injected
  script could read the admin's token from the page.

**Checked, and fine.**
- **Headers:** every answer carries `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY` and `Cache-Control: no-store`.
- **CORS:** there is no CORS configuration, so browsers keep other origins out
  (`SecurityHeadersTest`).
- **Errors:** an unexpected exception answers a generic 500; its message and stack stay in the
  log.

## A06 Vulnerable and outdated components

The resolved runtime dependencies of both applications, 239 packages, were checked against the
OSV database (osv.dev). `osv-scanner` on the poms could not resolve versions inherited from
Spring Boot's BOM and skipped every package, so the list came from `mvn dependency:list`.

| Package (Boot 4.1.1's version) | Known issues | Now |
|---|---|---|
| Tomcat 11.0.24 | 3 critical: CVE-2026-65905, -65182, -68525 (authentication bypass, access control) | 11.0.27 |
| Jackson 3.1.5 (`tools.jackson`) | 5 high/moderate, mostly denial of service on crafted input | 3.1.7 |
| Jackson 2.21.5 (`com.fasterxml`) | the same, in the 2.x line | 2.21.7 |
| lz4-java 1.10.1 (Kafka client) | 6, one high: native library extraction to a shared temp directory | 1.11.4 |

After the upgrades: 239 packages, none with a known vulnerability. Each version is the newest
fixed release at least two weeks old. They are set in the root `pom.xml`, to be dropped once
Spring Boot's own versions catch up. Dependabot checks weekly.

## A07 Identification and authentication failures

**In place.**
- **Lockout:** after 10 failed logins, saying when it lifts.
- **The browser's refresh token:** an `HttpOnly`, `SameSite=Strict` cookie scoped to the sign-in
  endpoints, so no script - injected or not - can read it (ADR 0017).
- **Rate limits:** per address and per phone.
- **Token lifetimes:** access tokens live 15 minutes. A WebSocket session closes when the token
  it was opened with expires.

**Found, and changed: which numbers are registered showed in the response time.** A login for
an unknown phone answered at once, while a known phone cost a BCrypt check, about 60 ms on the
load-test laptop. The error code was the same for both (`INVALID_CREDENTIALS`), but the timing
told an attacker which numbers have accounts. An unknown phone is now checked against a hash made
at startup by the same encoder, so both cost the same work
(`checksAPasswordEvenForANumberNobodyRegistered`).

**Accepted: registration still tells.** Signing up with a registered number answers
`PHONE_ALREADY_REGISTERED` (409), which states the same fact outright: a sign-up form has to say
the number is taken.
- **What limits it:** the per-address registration limit caps how fast numbers can be probed.
- **What closes it:** verifying the phone by OTP before answering, which is outside this
  project's scope.

**Found earlier.** In the Phase 9 review, the per-address limit trusted a client-written
`X-Forwarded-For`, so any client could claim a new address per request. Fixed then: the address
is the socket's, resolved by Tomcat from trusted proxies only.

## A08 Software and data integrity failures

**In place.**
- **Webhooks:** signed.
- **Ledger:** append-only, enforced by database triggers.
- **Leaflet:** loaded with subresource integrity.
- **CI:** builds and tests every push.

**Found.** The Maven wrapper downloaded Maven without checking it.

**Changed.** `distributionSha256Sum` is set, and was checked against the SHA-512 Apache publishes.

## A09 Security logging and monitoring failures

**In place.**
- **Logs:** structured, carrying trace ids.
- **Masking:** never a password, token, OTP or full phone number. `LogMaskingIntegrationTest`
  runs a whole trip at DEBUG, wrong password and wrong OTP included, and checks the output.
- **Alerts:** five Prometheus alerts, tested with `promtool`.

**Accepted risk.** Spring logs at ERROR every STOMP frame a client is not allowed to send. A
misbehaving client can therefore add error lines. Hiding that logger would hide real failures
too. It stays, and an alert on error rate is the guard.

## A10 Server-side request forgery

The backend makes one outbound HTTP call: the mock gateway's webhook, to a configured URL. No
request can name a URL for the server to fetch.
