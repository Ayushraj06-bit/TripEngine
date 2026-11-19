# 28. Place names on trips: what the rider chose, kept for display only

- **Status:** Accepted
- **Date:** 2026-11-17

## Context

A trip kept its pickup and drop as points. The backend has no geocoder, so the trips lists
(ADR 0026) could say only "Completed · Car" and when. The names the rider chose and saw, such as
"MG Road" and "Indiranagar", existed only in the browser, and were dropped at booking.

## Decision

- **The app sends the names with the booking**: optional `pickupName` and `dropName`, up to 200
  characters. The backend keeps each as one line: trimmed, with any run of spaces, tabs or line
  breaks inside collapsed to one space; a blank name is no name. It keeps them on the trip
  (`trips.pickup_name`, `trips.drop_name`, migration V15) and returns them in `TripView`, including
  in every update pushed over `/ws`, which replaces the trip a screen shows. The published API
  describes both fields.
- **The names are display-only.** Pricing, matching, the geofence and the fare all keep using the
  points. The backend never looks a name up, and never checks it against the point.
- **A stand-in is not a name.** "Your location" and "Pin on the map" say nothing about where a trip
  went a day later, so the app sends no name for them.
- **The names are part of the booking's request for its Idempotency-Key**, cleaned before they
  are hashed. A retry that differs only in spacing is the same booking. Different names under the
  same key are refused, as any different body is.
- **Shown wherever the trip is**: both trips lists ("MG Road → Indiranagar", "To Indiranagar"),
  the rider's sheet while the ride is under way, a finished trip opened again, and the driver's
  trip, beside the ride's other facts. The ride offer, before acceptance, is unchanged.
- **The arrow is drawn, and read aloud as "to".** A screen reader would otherwise say "MG Road
  right arrow Indiranagar".

## Consequences

- **Trips booked before this keep no names**, because there is no record of what was chosen. They
  read as before.
- **Other clients keep working.** The simulator sends no names, and its trips have none.
- **A name says no more about the rider than the point already stored**, and React escapes it
  wherever it is shown.
