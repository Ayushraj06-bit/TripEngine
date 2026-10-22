# Load tests

k6 scripts that measure the targets in PROJECT_SPEC section 2. Results go in
[`docs/performance.md`](../docs/performance.md): measured numbers only, never
estimates.

| Script | What it measures | Spec target |
|---|---|---|
| `location-ingest.js` | 2,000 drivers on STOMP, a position each every 3 s for 10 minutes | ~670 updates/s; ingest p95 < 20 ms |
| `booking-burst.js` | 200 bookings in 10 s, simulator drivers online | booking p95 < 150 ms; first offer p95 < 500 ms |
| `soak.js` | 30 minutes of normal rider traffic | no drift in latency or memory; nearby p95 < 10 ms |
| `login-storm.js` | logins at a fixed rate, while one rider keeps polling | none: shows whether logins starve other requests of connections |

## Running

k6 runs from its Docker image, pinned, so there is nothing to install:

```bash
docker compose -f infra/docker-compose.yml up -d        # Postgres, Redis, Kafka, Prometheus, Grafana
# the backend on the sim profile, with its bootstrap admin:
SPRING_PROFILES_ACTIVE=sim RIDEFLOW_ADMIN_PHONE=+919999900001 RIDEFLOW_ADMIN_PASSWORD=... \
  java -jar backend/target/rideflow-backend-0.1.0-SNAPSHOT.jar

docker run --rm -i --add-host host.docker.internal:host-gateway \
  -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run /scripts/<script>.js
```

- **Accounts:** the scripts log in as the simulator's users, which the simulator
  creates idempotently. Each script's header says which simulator run to start
  first, and whether to keep it running for its drivers.
- **Server-side numbers:** they come from Prometheus at http://localhost:9090,
  which scrapes the backend's management port, 8090.
  Ingest latency, first offer and server request time are recorded by the
  backend, not by k6: k6 sees the network too, and a STOMP SEND has no answer
  to time. The queries used are in `docs/performance.md`.

Override any default with `-e`: `-e DRIVERS=500 -e MINUTES=2` for a quick try.

## Query plans at a year's volume

`explain/seed.sql` fills a separate database with a million trips, and
`explain/queries.sql` runs `EXPLAIN (ANALYZE, BUFFERS)` on the history pages,
the matching sweep, the silent-driver check and the live map's counts. The
application's own database is not touched:

```bash
docker exec rideflow-postgres psql -U rideflow -d postgres -c "CREATE DATABASE rideflow_explain"
docker exec rideflow-postgres sh -c \
  "pg_dump -U rideflow --schema-only rideflow | psql -q -U rideflow -d rideflow_explain"
docker cp load-tests/explain/. rideflow-postgres:/tmp/explain/
docker exec rideflow-postgres psql -U rideflow -d rideflow_explain -f /tmp/explain/seed.sql
docker exec rideflow-postgres psql -U rideflow -d rideflow_explain -f /tmp/explain/queries.sql
docker exec rideflow-postgres psql -U rideflow -d postgres -c "DROP DATABASE rideflow_explain"
```

`explain/invariants.sql` checks what must never happen, against the application's
own database after a run: no driver assigned to two trips at once, and a ledger
that balances to zero:

```bash
docker cp load-tests/explain/invariants.sql rideflow-postgres:/tmp/
docker exec rideflow-postgres psql -U rideflow -d rideflow -f /tmp/invariants.sql
```
