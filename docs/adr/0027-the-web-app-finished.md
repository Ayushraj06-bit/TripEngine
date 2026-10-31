# 27. The web app, finished: what it covers, how it was checked, and what it leaves out

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

The rider and driver web app (ADR 0019) was built in ten stages, from the design system to this
one. ADRs 0020 to 0026 record what each stage decided. This record closes the web app: what a
person can do with it, how it was checked before being called done, and what it does not do.

## Decision

### What it covers

- **A rider** signs up, chooses a pickup and a drop, sees the backend's prices and books once,
  however often they tap. They follow their driver on the map to the fare, rate the ride, top up
  their wallet, and find their past trips.
- **A driver** signs up, adds a vehicle and waits for approval. Once approved, they go online,
  take offers and drive the trip on the map: arrival, the rider's code, the drop. They rate the
  rider, see their earnings and their rating, and find their past trips.

### How it was checked

- **Every commit passes the type check, lint, formatting and every test** (372 at the end)
  before it is made.
- **Each stage ended with a run against the one-command demo**, over its real REST API and
  WebSocket, on two phones side by side. Screenshots and checks were reviewed before the stage was
  called done. Several bugs no unit test had reached were found this way: positions reaching the
  backend late, a map fitted for the wrong size, and "Home" reopening a finished trip.
- **An accessibility audit ran axe** (WCAG 2.1 A and AA, plus best practice) on 28 screens. That
  covered every step of a whole trip on both phones and the account screens, at 390 and 1280 px,
  with reduced motion on. It found no violations.
  - axe was loaded into the page inside the test container, so the project has no new dependency.
  - What axe cannot check (focus order, names read aloud, what live regions announce, 44 px
    targets) is held by the unit tests and was reviewed by hand at each stage.

### What it leaves out

- **Native apps and push notifications.** The web app keeps itself current while it is open.
  PROJECT_SPEC puts mobile apps out of scope.
- **Turn-by-turn navigation**, handed to the phone's maps app (ADR 0024).
- **Place names in past trips.** The backend keeps the pickup and the drop as points (ADR 0026).
- **A real payment gateway's own page.** Only the demo's test gateway is paid in place (ADR 0025).
- **Paying drivers out.** The backend has no way to do it.

## Consequences

- **The end-to-end runs against the demo are not part of CI.** They need the whole demo running
  and the simulator paused. CI keeps the unit tests, the API contract check and the build. The
  backend's own end-to-end suite covers the server side every night.
- **A change to the backend's rules shows up in the web app's tests.** The timers, the rating
  window and the service area are held to `application.yml`, and the API types to the OpenAPI
  document.
