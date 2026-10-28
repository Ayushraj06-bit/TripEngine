# 20. Places and location in the web app: OpenStreetMap's search, the device's position, the map

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

A rider has to say where they are and where they are going before a fare can be quoted. The
backend takes a pickup and a drop as coordinates (`QuoteRequest`) and has no way to turn a name
into a place: there is no geocoding endpoint, and the spec adds none. People think in names, so
the app needs one.

## Decision

### Three ways to set a stop

- **The device's position**, for the pickup. It is asked for when the rider opens "Where to?",
  where the reason is obvious, and not at sign-in. A refusal, a timeout or no position at all each
  has its own words, and the rider can carry on by searching or using the map.
- **Search**, through OpenStreetMap's Nominatim.
- **The map**: the rider moves the map under a fixed pin, as in the ride apps they already know.
  The arrow keys move it too, and the place under the pin is read out.

### Nominatim, on its own terms

Nominatim is free, keyless, and built from the same OpenStreetMap data as the map's tiles. Its
[usage policy](https://operations.osmfoundation.org/policies/nominatim/) shapes how the app uses
it:

- **A search only when the rider presses Search.** The policy forbids search-as-you-type, so the
  fields never send anything while the rider is typing.
- **At most one request a second.** Requests queue for their turn.
- **Every answer is kept for the tab's life**, so the same question is never asked twice.
- **Results are steered to the service area** (`viewbox`), limited to India.
- **The map's attribution** to OpenStreetMap stays visible at all times.

### The service area, copied and checked

The app keeps a copy of the backend's service-area polygon, so a point outside it is refused
before anything is sent, with words that say why. A test reads the backend's `application.yml`
and fails when the two differ. Places found outside the area stay in the list, greyed, with the
reason, so a rider looking for the airport learns it is outside the area instead of thinking the
search is broken.

### Drivers nearby are shown as the backend reports them

`GET /drivers/nearby` returns rough positions only, by vehicle type. The app asks for every type
around the pickup every 15 seconds, draws them, and counts them in words. Nothing is guessed when
there are none.

## Consequences

- **What a rider types into a search goes to the OpenStreetMap Foundation's servers**, and so
  does a pin's position when the app looks up its address. The device's own position is not
  looked up: it is shown as "Your location".
- **The draft booking**, places included, lives in the tab's session storage and ends with the
  tab.
- **Nominatim's public server is for light use.** If RideFlow outgrows it, the same calls can go
  to a self-hosted Nominatim or a paid provider. Only `places/nominatim.ts` changes.
- **A Content-Security-Policy for the web app**, when there is one, must allow
  `connect-src https://nominatim.openstreetmap.org` alongside the tile server.
- **The 300 m minimum trip is checked by the backend**, by road, when the fare is quoted (Stage 3).
  The app only refuses a drop within 100 m of the pickup, which is the same place.
