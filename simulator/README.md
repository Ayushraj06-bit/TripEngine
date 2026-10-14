# RideFlow simulator

Fake drivers and riders that use the backend exactly as the apps would: REST, STOMP
over WebSocket, and Kafka as an external consumer. It never imports a backend class.
Design: [ADR 0015](../docs/adr/0015-simulator-and-live-map.md).

## Run it

The backend needs the `sim` profile. It raises the per-address login and
registration limits, since every simulated user comes from one address, and it
turns on the mock gateway, which tops up rider wallets. It also needs a
bootstrap admin to approve the drivers.

```bash
export RIDEFLOW_ADMIN_PHONE=+919999900001 RIDEFLOW_ADMIN_PASSWORD='choose-one'

# Terminal 1: the backend on its local profile (Compose started for you), plus sim
SPRING_PROFILES_INCLUDE=sim ./mvnw -pl backend spring-boot:run

# Terminal 2: the simulator
./mvnw -pl simulator spring-boot:run
```

Then open the live map at http://localhost:8080/admin/live-map.html and sign in
as the admin.

Restarting the simulator reuses its accounts. Driver *i* is always
`+9170` followed by *i* in eight digits, and rider *i* is `+9171...`.

## Configure it

Everything is in [`application.yml`](src/main/resources/application.yml) under
`simulator.*`. Any value can be overridden on the command line:

```bash
./mvnw -pl simulator spring-boot:run \
  -Dspring-boot.run.arguments="--simulator.drivers=500 --simulator.demand.requests-per-minute=120"
```

| Setting | Meaning |
|---|---|
| `backend-url` | the backend; STOMP is at `/ws` on the same host |
| `drivers`, `riders` | how many of each |
| `demand.requests-per-minute` | ride requests, as a Poisson process |
| `demand.hotspots`, `hotspot-share` | where pickups gather, by weight, and how many do |
| `demand.vehicle-mix` | BIKE, AUTO and CAR weights, for bookings and drivers' vehicles |
| `demand.payment-method` | `WALLET` or `CASH` |
| `demand.cancel-probability` | riders who cancel, `cancel-after` a delay |
| `driver.accept-probability` | offers accepted; the rest are half rejected, half ignored |
| `driver.response-delay` | how long a driver looks at an offer |
| `driver.cancel-probability` | accepted trips the driver drops before the pickup |
| `driver.disconnect-probability` | per tick, for every app; offline for `offline-for` |
| `driver.spoof-probability` | per tick, a position 3 km from the truth |
| `driver.min-speed-kmph`, `max-speed-kmph` | 20 to 35 km/h between random waypoints |
| `gps-noise-meters` | standard deviation of each reported position's error |
| `service-area` | the box everyone stays in |

## Scenarios

`--simulator.scenario=...` applies a preset on top of the values above.

| Scenario | What changes |
|---|---|
| `normal` | nothing |
| `rush-hour` | three times the demand around hotspots; surge should rise there |
| `driver-shortage` | a quarter of the drivers; offers run out, no-driver trips appear |
| `chaos` | ten times the disconnects and spoofs; riders cancel 30%, drivers 15% |

## Watch it

| What | Where |
|---|---|
| Live map | http://localhost:8080/admin/live-map.html |
| Kafka KPIs | logged every 30 s: trips requested and completed, completion rate |
| Metrics | http://localhost:8081/actuator/prometheus |

| Metric | Meaning |
|---|---|
| `simulator_requests_total{operation}` | REST calls made |
| `simulator_errors_total{type}` | failures by the backend's problem `code`, `IO` with no answer, `SIMULATOR` for a bug here |
| `simulator_offer_response_seconds{decision}` | from an offer arriving to the answer |
| `simulator_offers_total{decision}` | accepted, rejected, ignored |
| `simulator_trips_total{outcome}` | how riders' trips ended |
| `simulator_positions_total{spoofed}` | positions sent |
| `simulator_disconnects_total` | connections dropped on purpose |
| `simulator_kafka_trips_per_minute`, `simulator_kafka_completion_rate` | from `rideflow.trip-events` |

## Test it

```bash
./mvnw -pl simulator verify          # movement, Poisson arrivals, presets, the Kafka consumer
./mvnw -Pe2e verify                  # plus the full stack end to end (from the repository root)
```

The E2E tests start PostgreSQL, Redis and Kafka in Testcontainers and run the
backend jar the build just packaged on a plain JRE. Then:
- they take one ride through the public API, from sign-up to both ratings
- they run 20 drivers and 10 riders for a minute, then check:
  - trips completed
  - no driver ever held two trips at once
  - every completed trip has a balanced ledger transaction
  - the backend logged no errors
