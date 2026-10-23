# 4. A single BusinessException carrying a string code

- **Status:** Accepted
- **Date:** 2026-09-27

## Context

PROJECT_SPEC section 10 requires every error response to be an RFC 9457 problem
detail with an extra `code` field, and gives `TRIP_INVALID_TRANSITION` and
`QUOTE_EXPIRED` as examples. Something has to carry the code and the HTTP status
from the rule that raises the error to the response.

Two designs were on the table.

**One enum per module behind a shared `ErrorCode` interface.** Each module
declares its own enum implementing `code()`, `status()` and `title()`. Codes are
type-checked, a module owns its vocabulary, and nothing central has to be edited
to add one. It was built this way first.

**One `BusinessException(code, status, message)` taking a plain string.** No
interface, no enums, nothing to implement. The cost is that codes are strings:
nothing stops a typo, and there is no single place to enumerate them.

## Decision

Use `BusinessException(String code, HttpStatus status, String message)`, plus an
optional map of extra properties merged into the problem detail.

The deciding argument is that the interface bought type safety we were not
actually short of, at the price of a type hierarchy and one class per module
before any of those modules had a single error to report. A wrong code string
shows up in the first test that asserts on it. An interface with one
implementation per module would not have caught anything the tests do not.

The rejected alternative — one central enum listing every code in the system —
stays rejected. It would force every module to edit `shared` to add an error,
which turns the shared kernel into a coupling point and works against the module
boundaries of section 5. The string code avoids that as effectively as the
interface did.

`GlobalExceptionHandler` extends Spring's `ResponseEntityExceptionHandler`
rather than only catching `Exception`. A bare `Exception` handler also catches
the exceptions Spring itself raises — an unknown route, an unsupported method,
an unreadable body — and would report a client's mistake as a 500. The base
class gives those their correct statuses, and an override of
`createResponseEntity` stamps the `code` field onto them, so no error response
can reach a client without one.

## Consequences

- Adding an error is one constructor call. No new type, no registration.
- Codes are not compile-time checked. Two modules could pick the same string, or
  misspell one. The mitigation is convention (`SCREAMING_SNAKE_CASE`, prefixed
  with the module's concept) and a test asserting the code on every error path.
- There is no single list of every code in the system, so the OpenAPI
  documentation has to carry that list instead.
- `BusinessException` holds an `HttpStatus`, which puts a web type in the shared
  kernel. This is deliberate: section 10 defines errors in terms of HTTP
  statuses, so hiding that would only move the mapping somewhere less obvious.

## Later additions

Three answers came in with the infrastructure, not with a business rule. Each carries
`Retry-After`, because each means "this request, again shortly, will probably work":

| Status and code | When | Since |
|---|---|---|
| 429 `RATE_LIMITED` | a rate limit is spent; `Retry-After` is the wait | Phase 1 |
| 503 `SERVICE_UNAVAILABLE` | PostgreSQL or Redis did not answer in time, or no connection was free | Phase 10 |
| 409 `CONCURRENT_UPDATE` | a lock wait past `lock_timeout`, a deadlock, or a version that moved underneath | Phase 11 |

Before them, the last two reached the catch-all and answered 500. A 500 says the server is
broken and pages someone, while a retry would have gone through.
