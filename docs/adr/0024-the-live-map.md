# 24. The live map: the backend's positions, drawn smoothly, never guessed

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

Stage 6 puts the trip on a map. While a driver is on a trip, the backend forwards each accepted
position to the rider at most every 2 seconds, on `/user/queue/driver-location`. Each one carries
the trip's `seq`, the phase (to the pickup or to the drop) and an ETA. The ETA is a straight line
stretched by the road factor at the vehicle's average city speed, the same estimate a quote uses.
It is null when the vehicle type is unknown. The driver's own phone knows where it is from the
device.

## Decision

### The trip screens become the map

- **While a trip is under way, both trip screens are the map with the sheet over it**, the rider's
  home layout (ADR 0019). The sheet holds what the dark screen held: the code, the timers and the
  actions.
- **Once the trip is over, the screen goes back to the dark one:** the fare, or who cancelled.
  There is nothing left to follow.

### Positions are the backend's, in order, and never guessed

- **Ordered by `seq`, counted apart from the trip's own updates.** The two travel on different
  destinations, and the order between destinations is not kept. Compared on one counter, a
  position could hide a trip update that arrived after it.
- **Only this driver's positions are drawn.** One heard before the current assignment belongs to a
  driver who backed out.
- **No dead reckoning.** Between positions, the car glides for 1 second from where it is drawn to
  where it was reported, and never past it. With reduced motion, it moves at once.
- **Stale after 30 seconds,** the same time after which the backend treats a driver's position as
  stale. The car stays where it was last seen, dimmed, and the sheet says since when: "Your
  driver's position hasn't updated since 4:32 pm". No ETA is shown while the position is stale.

### What the rider is told

- **"Arriving in about 4 min" or "About 12 min to the drop"**: rounded to whole minutes, never
  less than one.
- **Only while the phase matches the trip's step.** A position sent just before the trip moved on
  is about the step before, and is not shown as a time.
- **No time line when the backend has no estimate.**
- **A polite live region**, so the time is read out as it changes, not on every position.

### The map follows without taking over

- **Fitted when the stops change, the car first appears, or the sheet moves.**
- **After that, only when the car nears the edge of the map the sheet leaves uncovered.** It is
  never refitted on every position, which would take the map away from anyone looking around it.
- **Leaflet moves the car itself, frame by frame; React says only where it is going.**
  react-leaflet moves a marker back to any position it is handed anew, so the car's first position
  is handed once, as the same array on every render.

### Directions are the phone's maps app's job

- **The driver's screen shows their own position, the pickup and the drop.**
- **"Open in Maps" hands the next stop to the phone's maps app:** Apple Maps on Apple devices,
  Google Maps elsewhere. Each opens in its own app where one is installed.
- **The app builds no routing of its own.**

## Consequences

- **The ETA is an estimate, and is said to be one** ("about"). It agrees with the quote because
  both use the same estimate, not because either knows the roads.
- **No route line is drawn.** The backend has no road routing, and a straight line would suggest a
  route that does not exist.
- **The car is drawn up to 1 second behind its last reported position**, half the time between
  positions, for movement that reads as driving rather than jumping.
- **Opening Maps sends the stop's coordinates to Apple or Google.** That is the driver's choice,
  made with one tap; the app sends nothing on its own.
