# 19. The web client: typed against the API, one design system, the backend as the source of truth

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

Riders and drivers get a web app in `Frontend/`, beside the backend and the simulator, built in stages
against the backend as it stands. It must not drift from the API, must look like one product
rather than a pile of defaults, and must stay correct when the network, the socket or the session
misbehaves.

## Decision

### Stack

React 19, TypeScript in strict mode, Vite, React Router, TanStack Query for everything the server
owns, Zustand for the little the client owns (the session, a booking draft), Tailwind CSS v4,
Leaflet with OpenStreetMap tiles, and Vitest, Testing Library and MSW for tests.
- **Versions:** pinned exactly, each at least a week old when chosen.
- **TypeScript 5.9, not 7:** the lint rules, the type generator and MSW all require 5.x.
- **ESLint 9, not 10:** the accessibility plugin, which enforces the app's accessibility floor, does
  not yet support 10. ESLint 9 is past its support window; it runs only on developers' machines and
  in CI, never in the shipped app. Move when the plugin does.
- **No SockJS:** the backend's `/ws` is a plain WebSocket.

### The client cannot drift from the API

- **A committed snapshot** of the backend's OpenAPI document (`Frontend/src/api/openapi.json`), with the
  types generated from it (`schema.d.ts`). `npm run api:update` refreshes both.
- **Two checks keep them honest:** CI regenerates the types and fails on any difference, and CI's
  image job compares the snapshot with the running backend. An API change has to update the
  client's copy in the same commit.
- **Calls are typed end to end:** a wrong path, a missing path parameter, a required query
  parameter left out or a wrong body does not compile.

### One way to talk to the backend

`Frontend/src/api/client.ts` attaches the access token and turns every failure into an `ApiError`
carrying the backend's `code`.
- **On a 401 it renews the session once and retries once.** Requests that fail during a renewal
  wait for that same renewal. Refresh tokens are rotated (ADR 0017), so a second, concurrent
  renewal would present a spent token, which the backend treats as theft and revokes the session.
- **Every code the backend can send has words in one table** (`errors.ts`): what happened, what to
  do, and a recovery the screen offers. A test reads the backend's sources and fails when a code
  has neither copy nor a stated reason the app never sees it.

### One design system

Eight colour tokens, a five-step type scale, two weights, three radii and one shadow, defined once
in `Frontend/src/styles/theme.css`.
- **Tailwind's own palette, sizes, radii and shadows are removed**, so only the tokens exist as
  classes.
- **A test fails** when any other file writes a colour, a type size, a weight, a radius or a shadow.
- **Archivo is self-hosted:** two files, preloaded, nothing requested from a third party on first
  paint.

### The session

The access token lives in memory only. The refresh token will be an `httpOnly`, `SameSite=Strict`
cookie scoped to the refresh path (Stage 1), which no script can read, so a reload restores the
session silently.

## Consequences

- An API change is two commits' work in one: the backend change, and `npm run api:update`.
- A new error code from the backend fails the web build until it has copy.
- The map is its own chunk, loaded with the first map screen.
- The design system is reviewable in one place, at `/design-system`.
