# Runbook

Operational notes for running RideFlow locally and diagnosing it when it
misbehaves.

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 (LTS) | `java -version` |
| Maven | 3.9+ (or use `./mvnw`) | `./mvnw -v` |
| Docker | with Compose v2 | `docker compose version` |

Docker is not optional: the integration tests use Testcontainers, and
PROJECT_SPEC section 12 rules out H2 and embedded Redis.

## Start the stack

```bash
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps     # all services healthy?
```

| Service | URL | Credentials |
|---|---|---|
| PostgreSQL | `localhost:5432/rideflow` | `rideflow` / `rideflow` |
| Redis | `localhost:6379` | none |
| Kafka | `localhost:9092` | none |
| Prometheus | http://localhost:9090 | none |
| Grafana | http://localhost:3000 | `admin` / `admin` |
| Jaeger | http://localhost:16686 | none |

Then run the backend:

```bash
./mvnw -pl backend spring-boot:run
```

## Build and test

```bash
./mvnw verify          # unit + integration + module verification + coverage gate
./mvnw spotless:apply  # fix formatting before committing
```

`verify` starts containers through Testcontainers. The first run pulls images
and is slow; later runs reuse them.

## Two-instance setup

Used to exercise the cross-instance real-time path and to load-test.

```bash
docker compose -f infra/docker-compose.yml -f infra/docker-compose.scale.yml up -d --build
```

nginx listens on 8080 and round-robins between the instances, which are also
reachable directly on 8091 and 8092. Round robin is deliberate: real-time
delivery goes through Redis Pub/Sub, so no sticky sessions are needed. If a
client only receives messages when it happens to hit one instance, the Pub/Sub
fan-out is broken — check the `rt:events` channel first.

## Health and diagnostics

| What | Where |
|---|---|
| Liveness and readiness | `GET /actuator/health` |
| Metrics for Prometheus | `GET /actuator/prometheus` |
| Module structure | `GET /actuator/modulith` |
| API docs | http://localhost:8080/swagger-ui.html |

## Common problems

### `verify` fails with "Could not find a valid Docker environment"

Docker Desktop is not running, or the current shell cannot reach the daemon.
Start Docker Desktop and re-run. Testcontainers needs a live daemon; there is no
fallback.

### Flyway fails with a checksum mismatch

A committed migration was edited, which PROJECT_SPEC section 7 forbids. Restore
the original file and add a new migration instead. For a local database only,
`docker compose down -v` wipes the volume and replays from scratch.

### Events stop arriving

Check `event_publication` for rows with a null `completion_date`:

```sql
SELECT id, event_type, publication_date
FROM event_publication
WHERE completion_date IS NULL
ORDER BY publication_date
LIMIT 20;
```

Rows accumulating there mean a listener is failing or Kafka is unreachable. The
events are not lost — they are retried, and republished on restart. Find the
listener's exception in the logs before intervening.

### Drivers linger on the map after disconnecting

The stale-driver job removes drivers idle beyond
`rideflow.location.stale-after-seconds`. It runs under ShedLock, so exactly one
instance performs it. If it has stopped, inspect the `shedlock` table for a lock
held by a dead instance:

```sql
SELECT name, lock_until, locked_at, locked_by FROM shedlock;
```

### A booking answers 409 IDEMPOTENCY_KEY_IN_FLIGHT and never recovers

The request that claimed that `Idempotency-Key` died before answering - a crash
or a kill between claiming the key and storing the response. The claim stays
until the 24-hour purge (ADR 0009 records this gap).

1. Confirm no trip was created for it: the rider has no trip with a
   `requested_at` near the key's `created_at`.
2. Release the key, and the client's retry will go through:

   ```sql
   DELETE FROM idempotency_keys
   WHERE user_id = '<rider id>' AND key = '<key>' AND response_status IS NULL;
   ```

The quote that request consumed is gone; the rider needs a new one.

### Port already in use

Defaults: backend 8080, simulator 8081, Postgres 5432, Redis 6379, Kafka 9092,
Grafana 3000, Prometheus 9090, Jaeger 16686. Override the backend with
`SERVER_PORT`, or change the host-side port mapping in the compose file.

## Reset everything

```bash
docker compose -f infra/docker-compose.yml down -v   # -v also drops the data volumes
./mvnw clean
```
