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

### One design system, after Apple's

Eight colour tokens, Apple's five text styles, two weights, four radii, one shadow and two frosted
materials, defined once in `Frontend/src/styles/theme.css`.
- **Tailwind's own palette, sizes, radii and shadows are removed**, so only the tokens exist as
  classes.
- **A test fails** when any other file writes a colour, a type size, a weight, a radius or a shadow.
- **The device's own font** (`system-ui`): San Francisco on Apple devices, Segoe UI on Windows,
  Roboto on Android. Nothing is downloaded, and text reads the way the rest of the device does.
  Body text is 17 px, Apple's reading size.
- **Apple's patterns, not its look:** large titles, frosted bars the page shows through (solid
  when the device asks for reduced transparency), a segmented control for one of a few choices, a
  sheet that moves on iOS's curve, and 44 px touch targets.
- **Dark to arrive, light on the map:** the welcome, sign-in and onboarding screens are ink, with
  a signal-yellow primary action; the map screens are white, so the map reads clearly.

### The session

- **The access token lives in memory only**, never in storage a script could read later.
- **The refresh token is an `httpOnly`, `SameSite=Strict` cookie** scoped to `/api/v1/auth`, which
  no script can read. The backend sets it, instead of returning the token in the body, when a
  request carries `X-RideFlow-Client: web` (ADR 0017); other clients are unchanged.
- **A reload renews the session from the cookie once**, before the first screen, behind a splash.
  However often start-up asks (React's strict mode asks twice), there is one renewal.
- **Tabs take turns renewing**, through the Web Locks API. The refresh token rotates on every use
  and a spent one revokes the session as stolen, so two tabs renewing together would sign the user
  out.
- **A session that cannot be renewed ends once**, however many requests find it over: one message,
  everything fetched for it forgotten, then sign in, which returns the user to the page they were
  on. A booking already started is kept, in the tab's session storage.
- **The server decides where a user lands after signing in:** a trip under way opens that trip; a
  driver without a vehicle adds one, and one not yet approved waits on the approval page;
  otherwise the page they came from, if it is in their own part of the app, or home. A return
  address that leads out of the app is ignored.

## Consequences

- An API change is two commits' work in one: the backend change, and `npm run api:update`.
- A new error code from the backend fails the web build until it has copy.
- The map is its own chunk, loaded with the first map screen; the welcome screen loads it only on
  a wide screen.
- The design system is reviewable in one place, at `/design-system`.
- The app looks a little different on each platform, because the font is the platform's. That is
  the point: it reads like the device it runs on.

## Decisions built on this one

- [ADR 0020](0020-places-and-location.md): places and location. OpenStreetMap's Nominatim for
  search, the device's position asked in context, and the service area held to the backend's.
- [ADR 0021](0021-prices-and-booking.md): prices and booking. Countdowns on the server's clock,
  and one Idempotency-Key per booking attempt.
- [ADR 0022](0022-live-updates-in-the-web-app.md): live updates. A small STOMP client, REST as the
  source of truth, and pushes ordered by `seq`.
- [ADR 0023](0023-the-trip-in-the-web-app.md): the trip itself. The backend's timers held to it by a
  test, the driver's position shared for the whole drive, and every step that cannot be undone
  asked first.
- [ADR 0024](0024-the-live-map.md): the live map. The backend's positions in order, glided between
  and never guessed, said to be stale when they stop, and directions left to the phone's maps app.
- [ADR 0025](0025-the-wallet-in-the-web-app.md): the wallet. The server's balance, a top-up's outcome
  asked under its own Idempotency-Key, and "Add money" wherever a wallet below zero is mentioned.
- [ADR 0026](0026-ratings-and-trips-in-the-web-app.md): ratings and past trips. Rated once, the
  backend's refusal trusted as the answer, and a finished trip forgotten as the one under way.
