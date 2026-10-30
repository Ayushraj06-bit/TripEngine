# 23. The trip in the web app: the backend's rules, asked first, shared the whole drive

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

Stage 5 takes a trip from "driver on the way" to "trip completed". The driver marks arrival,
starts the trip with the code the rider reads out, and completes it at the drop. They may also
leave the trip before arriving, or cancel for a no-show after waiting. The rider watches each step
on their own phone. The backend enforces every rule (PROJECT_SPEC section 6). But several of
them are timers no response states: free cancelling, free waiting, and the no-show wait.

## Decision

### The rules the app counts down to are the backend's, held to it by a test

- **Copied from `rideflow.trip`:** the rider's free-cancel time (120 s after assignment), free
  waiting (3 min), the no-show wait (5 min) and the arrival distance (150 m), into
  `trip/rules.ts`.
- **A test reads the backend's `application.yml`** and fails when they differ, as the service
  area's does (ADR 0020).
- **The backend still decides.** A refusal (`NOT_AT_PICKUP` with the distance, `NO_SHOW_TOO_EARLY`
  with the time it becomes allowed) is shown in its own words.

### The driver's position is shared for the whole drive

- **Sharing runs around every driver screen** (`DriverPresence`), whenever the driver is online
  or on a trip, not only on the home screen.
- **Two backend checks depend on it:** arrival is checked against the latest position, and the
  final fare is worked out from the route driven.

### What cannot be undone is asked first

- **Asked first, with what follows from it:** leaving a trip ("RideFlow will find the rider
  another driver"), cancelling for a no-show ("the rider will be charged the cancellation fee"),
  and completing ("do it at the drop: the fare is worked out from the route you drove").
- **Both ways out are equally easy to reach**, the same as the rider's paid cancel (ADR 0022).

### The code

- **One numeric field.** It opens the number keyboard and accepts the code as it is read out,
  spaces included. Four digits are sent.
- **A wrong code says how many tries are left.** After three, the lock counts down on the button.

### Each side is told what the other did

- **The trip moves on both phones as it moves on the server**, pushed live (ADR 0022).
- **A driver backing out** sends the trip back to matching. The rider is told why they are being
  matched again, not only shown "Finding your driver".
- **The rider sees** free waiting counted down, then that waiting is charged.
- **At the end:** the final fare, and how it was paid. The driver is told what to collect in cash.

## Consequences

- **The copied rules can never quietly drift** from the backend's; a change there fails the web
  app's tests until they match.
- **The final fare can differ from the quote**, by design: it comes from the route driven. The
  driver's screen says so before the trip, and the rider's shows what was charged.
- **Turn-by-turn navigation to the pickup is not part of this.** The live map is Stage 6.
