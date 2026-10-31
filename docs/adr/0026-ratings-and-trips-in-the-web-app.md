# 26. Ratings and past trips in the web app: asked once, and the backend's answer trusted

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

Stage 8 lets each side of a completed trip rate the other, and gives both a list of their trips.
The backend takes one rating per side per trip, for 7 days after the trip is completed
(`rideflow.rating.window-days`). It answers `TRIP_NOT_COMPLETED`, `RATING_WINDOW_CLOSED` or
`ALREADY_RATED` otherwise. No trip says whether it has been rated: the only way to know is to send
a rating. `GET /trips` lists the caller's trips, as rider or as driver, newest first, a page at a
time. A driver's profile carries their average rating and how many ratings it is from.

## Decision

### Rating

- **Offered on the completed trip**, under the fare and what to collect, while the window is
  open. "Not now" is as easy to reach as sending.
- **One to five stars as real radio buttons** (one tab stop; the arrow keys choose), and up to 500
  characters of comment, counted.
- **The 7 days are copied into the app** and held to `application.yml` by the same test as the
  trip timers (ADR 0023).
- **The backend is not changed to say whether a trip has been rated.** Its refusal is the answer:
  `ALREADY_RATED` is shown as "You've rated this trip", and the form is not offered again in that
  tab. A new tab may offer it once more, and is answered the same way.

### Past trips

- **Rider and driver share one list**: how each trip ended, the ride, when, and what it came to
  (the fare, or a cancellation fee). Each row opens the trip itself, where a completed one can
  still be rated.
- **"Your trips" sits on the greeting's line in the rider's home sheet**, not in the bar. The
  sheet's lowest height has room for nothing more, and a third action does not fit beside the
  name at 390 px. On the driving screen, it sits beside "Your vehicle".
- **The driving screen says what riders made of the driver**: "Rated 4.8 by riders, from 23
  ratings", as the backend has added it up.

### A finished trip stops being the trip under way

- **The moment a trip on screen ends, the app forgets it as the user's trip under way.** Home
  opens the trip under way from what it remembers while it asks again. Without this, "Home" and
  "Back to driving" opened the finished trip once more. This was found in the end-to-end run.
- **Opening an old completed trip leaves the ride being planned alone.** The booking draft is
  cleared once a driver is on the way, and not for a trip that is over.

## Consequences

- **Trips list no place names.** The backend keeps the pickup and drop as points. A row says how
  the trip ended, the ride, when, and what it came to.
- **A trip rated on another device is offered for rating once more**, and refused politely.
- **The driver's average is the backend's**, and can lag a rating by however long the backend
  takes to add it up.
