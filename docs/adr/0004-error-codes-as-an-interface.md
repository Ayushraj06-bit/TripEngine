# 4. Error codes as an interface, not a central enum

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

PROJECT_SPEC section 10 requires every error response to be an RFC 9457 problem
detail carrying an extra `code` field, and gives `TRIP_INVALID_TRANSITION` and
`QUOTE_EXPIRED` as examples. Something has to hold the mapping from a code to
its HTTP status.

The obvious implementation is one enum in `shared` listing every code in the
system. It is easy to read and trivially exhaustive.

It is also a coupling point. Every module that adds an error has to edit a file
in `shared`, so the shared kernel changes on nearly every phase, every module's
error vocabulary sits in one file far from the rule that raises it, and
extracting a module later means untangling its codes from everybody else's. That
works directly against the boundaries PROJECT_SPEC section 5 sets out.

## Decision

`ErrorCode` is an interface in `shared` declaring `code()`, `status()` and
`title()`. Each module declares its own enum implementing it, next to the rules
that raise those errors. `CommonErrorCode` in `shared` covers only the
cross-cutting cases: validation, authentication, authorisation, not found,
conflict, business rule, idempotency, rate limiting, internal error.

`RideFlowException` carries an `ErrorCode` plus an optional map of extra
properties, which are merged into the problem detail. It is deliberately not
`sealed`, because modules must be able to subclass it.

`GlobalExceptionHandler` extends Spring's `ResponseEntityExceptionHandler`
rather than only catching `Exception`. A bare `Exception` handler also catches
the exceptions Spring itself raises — an unsupported method, an unreadable
request body — and would report a client's mistake as a 500. The base class
gives those their correct statuses, and an override of `createResponseEntity`
stamps the `code` field onto them, so no error response can reach a client
without one.

## Consequences

- A module owns its error vocabulary. Adding an error touches one module.
- No single file lists every code, so the API documentation has to carry that
  list instead. springdoc output is the place for it.
- Nothing stops two modules choosing the same code string. Codes are
  `SCREAMING_SNAKE_CASE` and prefixed with the module's concept
  (`TRIP_INVALID_TRANSITION`, `QUOTE_EXPIRED`), which makes a collision
  unlikely and obvious in review, but it is a convention, not a compiler check.
- `ErrorCode` returns `HttpStatus`, which puts a web type in the shared kernel.
  This is deliberate: PROJECT_SPEC section 10 defines errors in terms of HTTP
  statuses, so pretending otherwise would just move the mapping somewhere less
  obvious.
