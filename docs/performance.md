# Performance

> **Measured results only.** PROJECT_SPEC section 2 is explicit: never put made-up
> numbers on this page. Every figure below must be reproducible from a k6 script
> in `load-tests/` or a recorded benchmark run, with the date, the commit and the
> hardware it ran on.

## Status

Phase 10 measured every target, on one laptop. Results are in the table below and
the runs follow it.

## Targets

The targets are copied from PROJECT_SPEC section 2. Each "Measured" figure is the
server's own, from Prometheus, unless it says k6.

| Metric | Target | Measured | Run |
|---|---|---|---|
| Simulated online drivers | 2,000 (≈ 670 location updates/sec) | 2,000 connected; 666.5 updates/s, all accepted | [Ingest](#location-ingest-2000-drivers) |
| Location ingest latency (server side) | p95 < 20 ms | **p95 2.8 ms** (p50 1.3, p99 3.9) | [Ingest](#location-ingest-2000-drivers) |
| Nearby-driver search | p95 < 10 ms | **p95 2.5 ms** (p99 3.3) over 30 minutes | [Soak](#soak-30-minutes) |
| Booking API (`POST /trips`) | p95 < 150 ms | **p95 16.6 ms** (k6: 17.2 ms) on the released code; 41.4 ms in the slowest run | [Bursts](#booking-burst-200-bookings-in-10-s) |
| Booking to first offer sent | p95 < 500 ms | **p95 17.8 ms** with drivers free nearby (18.9 ms over the soak); **missed** when the burst outran them: 11.0 s | [Bursts](#booking-burst-200-bookings-in-10-s) |
| Double-assigned drivers | exactly 0 | **0**, over 8,536 assigned trips and 1,995 drivers | [Invariants](#invariants-after-the-runs) |
| Lost domain events | exactly 0 (including Kafka down) | **0**: 76 published while Kafka was stopped for 60 s, 76 on the topic | [Chaos](#chaos) |
| Ledger balance | always 0 | **0**, over 8,727 transactions and 25,176 entries | [Invariants](#invariants-after-the-runs) |

## Setup for the Phase 10 runs

Every run below used this setup unless it says otherwise.

- **Dates:** 2026-10-22 and the early hours of 2026-10-23 (IST). Chaos tests:
  2026-10-15.
- **Code:** branch `phase-10-observability`, released as `phase-10`. Runs marked
  "before" ran the code before the fix named in [Tuning](#tuning).
- **Hardware:** Intel Core i7-13650HX (14 cores, 20 threads), 15.6 GB RAM,
  Windows 11 Home 10.0.26300.
- **Single machine:** k6, the backend, and every store ran on this one laptop and
  shared its CPU. The figures are what this laptop does, not a server's ceiling.
- **Backend:** one instance, `java -jar` on the host.
  - OpenJDK 21.0.12 (Temurin), default heap (a quarter of RAM) and G1
  - profiles `sim` (high per-address rate limits)
  - HikariCP pool 10, the default
  - plain-text logs
  - one trace in ten sampled and exported to Jaeger
  - event listeners on Boot's virtual-thread executor
- **Changed since:** the review moved event listeners to 32 platform threads
  (`ListenerExecutorConfig`), after a CI run saw them all stall at once. The
  runs here were not repeated. A listener's own work is unchanged; what could
  differ is a burst's first offer, if more than 32 listeners were ever waiting
  at once.
- **Stores:** `infra/docker-compose.yml` on Docker Desktop 29.8.0 (WSL 2 VM:
  10 CPUs, 7.75 GB): PostgreSQL 17.11, Redis 7.4.11, Kafka 4.2.1, Prometheus
  3.14.0 (scraping every 5 s), Jaeger 1.76.
- **Load:** k6 2.3.0 in Docker, reaching the backend through
  `host.docker.internal`. Every request crosses Docker Desktop's port forwarding,
  which adds to what k6 sees but not to what the server records.
- **Drivers:** for the bookings and the soak, the [simulator](../simulator/README.md),
  on the host, drives them and answers offers.

### How the server-side numbers are read

Quantiles come from the backend's histogram buckets, increased over the run's
window, so they cover every request in it:

```promql
histogram_quantile(0.95, sum by (le) (increase(location_ingest_seconds_bucket[439s])))
```

- **Resolution:** a quantile is interpolated inside its histogram bucket.
  Micrometer's buckets are 0.35 ms apart near 3 ms and 5.6 ms apart near 20 ms,
  so a figure is exact only to within its bucket.
- **Peaks:** pool, heap and threads are `max_over_time` at 5 s steps.

### Standby during the runs

Windows put the laptop into Modern Standby five times while the stack was
running. The system log's Kernel-Power events 506 and 507 record these periods
(IST, all on 2026-10-22):

| Entered standby | Left standby |
|---|---|
| 00:31:09 | 00:32:19 |
| 00:55:46 | 00:59:43 |
| 01:10:05 | 01:10:14 |
| 01:29:35 | 01:55:47 |
| 23:35:03 | 23:37:39 |

Every run's times were checked against them.
- **Kept:** the two ingest runs, bursts 1 and B, and all four login storms.
  - None overlaps a standby period, with one exception: the second ingest run's
    login setup, which finished before its measured window began.
- **Discarded:**
  - **Burst A:** it ran at 00:58:37 to 00:58:50, inside the second period.
  - **The first soak:** the backend logged nothing for 1,526 s, from 01:30:20 to
    01:55:46, and k6 and the simulator stood still with it. Thirty minutes with a
    25-minute hole cannot show drift.
- **Re-runs:** the soak and the third and fourth ingest runs were made later,
  with the laptop kept awake for their duration. A process held a Windows
  execution-state request (`SetThreadExecutionState`), which changes no power
  setting.
- **The last standby** (23:35 IST, 2026-10-22) fell between runs, when that
  request had lapsed. Docker Desktop's engine did not recover from it, so it
  was restarted before the final soak.

## Runs

### Location ingest, 2,000 drivers

`load-tests/location-ingest.js`: 2,000 drivers.
- **Each driver:** logs in, opens STOMP at `/ws`, and sends a position every 3 s
  for 10 minutes.
- **Movement:** each starts at a random point in central Bengaluru and moves a
  random step of up to about 20 m between reports.
- **Start:** connections are spread over the first minute.

```bash
docker run --rm -i --add-host host.docker.internal:host-gateway \
  -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run /scripts/location-ingest.js
```

The steady window runs from 3 minutes after the start, when every driver is
connected, to 75 s before the end, when the first sessions close: about 437 s.

The script ran four times, as faults were fixed and Redis settings tried. The
last run is the released code, except for one line: a session's first position
skipped the speed check, an exemption the review then reverted (see
[Tuning](#spoof-rejections-in-the-first-ingest-run)).
- **Leftover trips:** by the last run, 277 of the 2,000 drivers carried a trip
  left over from the booking runs. Each of their positions was also recorded on
  the route and pushed to a rider, so that run did more work per second than
  the earlier ones.

**Final run** (21:19 to 21:31 IST), server side:

```
window 435s ending 1792684772
location updates/s by result (mean):
   accepted: 666.5
location ingest: p50 1.3 ms, p95 2.8 ms, p99 3.9 ms
5xx answers: 0
peak websocket sessions: 2000
peak heap used: 523 MiB
peak live threads: 81
peak process CPU: 3% of all cores
peak hikari active / pending: 1 / 0
peak hikari acquire (max): 1.6 ms
GC pause total: 441.1 ms
peak event backlog: 0
```

k6 summary:

```
stomp_connected................: 2000   2.909129/s
stomp_errors...................: 0      0/s
location_frames_sent...........: 398000 578.916651/s
http_req_failed................: 0.00%  0 out of 2000
ws_connecting..................: avg=2.89ms  min=1.85ms  med=2.57ms  max=33.11ms  p(90)=3.45ms  p(95)=3.99ms
ws_session_duration............: avg=10m0s   min=10m0s   med=10m0s   max=10m0s    p(90)=10m0s   p(95)=10m0s
```

**Reading:**
- **Target met:** p95 2.8 ms against a 20 ms target, at 666.5 updates/s against
  about 670. CPU stayed near 3%.
- **k6's frame rate is lower:** k6's 579/s is averaged over the whole 11.5
  minutes, including ramp-up and ramp-down. The server's 666.5/s is the steady
  window.
- **Handshakes that did not connect:** in the three earlier runs, 1, 5 and 2
  handshakes timed out at k6's 30 s limit.
  - **Not the backend:** it logged nothing for them, so they never reached it.
  - **Small effect:** those drivers sent nothing, so the load was 1,995 to 1,999
    drivers.
  - **Final run:** all 2,000 connected.

**Every run**, steady window, server side:

| Run | Code | Accepted/s | Rejected as spoof/s | Ingest p50 / p95 / p99 | Peak heap |
|---|---|---|---|---|---|
| 1 | before the fixes | 524.1 | 127.9 | 1.7 / **17.7** / 48.8 ms | 1,191 MiB |
| 2 | after the fixes, with the session exemption since reverted | 664.9 | 0 | 1.4 / 2.8 / 5.9 ms | 529 MiB |
| 3 | with buffered Redis pipelines, since reverted; 277 drivers relaying | 665.8 | 0 | 1.4 / 3.6 / 8.7 ms | 532 MiB |
| 4 | as released, but with the session exemption; 277 drivers relaying | 666.5 | 0 | 1.3 / 2.8 / 3.9 ms | 523 MiB |

- **Run 1 found two problems,** described under [Tuning](#tuning):
  - one position in five was rejected as a spoof: the script's own doing,
    starting drivers kilometres from where the last run left them
  - every rejection past the fifth flagged the driver again, which logged
    111,318 warnings in ten minutes
- **Run 1, unavailable:** 0.1 positions a second (about 45 in all) were counted
  `unavailable`, meaning a Redis call failed. None were in the later runs.
- **Runs 3 and 4** are the Redis pipeline experiment under
  [Lettuce](#lettuce).

### Booking burst, 200 bookings in 10 s

`load-tests/booking-burst.js`: 200 riders, each quoting and then booking a car.
- **Arrival:** a constant 20 bookings a second, for 10 s.
- **Pickups:** within 4 km of central Bengaluru.

```bash
docker run --rm -i --add-host host.docker.internal:host-gateway \
  -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run /scripts/booking-burst.js
```

The spec measures first offer from the moment of booking, and so does the
backend's `matching_first_offer_seconds`. With no free driver in range, that time
includes the wait for one. So the burst ran with too few cars nearby, and then
with plenty:

| Run | Drivers online | Booking p95 (server / k6) | First offer p50 / p95 / p99 | Time to assign p95 | Booked |
|---|---|---|---|---|---|
| 1 | 300, simulator defaults, across the city | 41.4 / 43.9 ms | 25.4 ms / **10.95 s** / 30 s+ | 12.4 s | 200 of 201 |
| B | 2,000 at 90 to 110 km/h, in a 10 km box round the pickups (725 of them cars) | 26.1 / 28.2 ms | 18.8 / **27.2** / 78.3 ms | 3.1 s | 199 of 200 |
| C | as B, on the released code (796 cars free) | 16.6 / 17.2 ms | 13.4 / **17.8** / 21.9 ms | 3.1 s | 200 of 201 |

- **Driver speed:** fast simulated drivers finish their trips within minutes, so
  each run starts with the same supply.
- **Time to assign:** this includes the simulated driver's 1 to 3 s to accept.
- **Iterations:** k6's arrival-rate executor started 201 iterations in runs 1
  and C.
- **Simulator:** the command for each run is in the script's header.
- **Windows:** run 1: 1792610130 to 1792610250; B: 110 s to 1792611912; C: 60 s
  to 1792695320.
- **Run C** (2026-10-23 00:24 IST) is the released script, which cancels its
  trips as it ends. Runs 1 and B left theirs waiting for an OTP no k6 rider can
  give, which is where the later runs' leftover trips came from.
- **Run A, discarded:** a run between these two, with 600 fast drivers, ran
  while Windows was entering Modern Standby. Its processes were being
  suspended, so its numbers say nothing about the backend; see
  [Standby](#standby-during-the-runs).

Server side, run B:

```
booking POST /trips: p50 17.4 ms, p95 26.1 ms, p99 31.9 ms
quote POST /fares/quotes: p50 4.9 ms, p95 7.0 ms, p99 30.3 ms
booking to first offer: p50 18.8 ms, p95 27.2 ms, p99 78.3 ms
time to assign: p50 2173.7 ms, p95 3097.1 ms, p99 3196.4 ms
trips requested: 205
peak heap used: 905 MiB
peak live threads: 96
peak process CPU: 24% of all cores
peak hikari active / pending: 5 / 0
peak event backlog: 4
```

k6, run B:

```
✓ 'p(95)<150' p(95)=28.15ms
{ name:book }................: avg=21.68ms min=14.59ms med=17.95ms max=140.72ms p(90)=24.13ms p(95)=28.15ms
✗ quoted  ↳  99% — ✓ 199 / ✗ 1
```

**Reading:**
- **Booking target met:** met in every run, with the slowest p95 at 41.4 ms
  against 150 ms.
- **First offer met with supply:** p95 17.8 ms in run C and 27.2 ms in run B,
  when a free driver was within the first search radius (2 km).
- **First offer missed without supply:** when 200 bookings outnumbered the free
  cars nearby, p95 went to 11.0 s.
  - **Why it was missed:** the first offer was waiting for a car, not for the
    server. A booking with no candidate widens its radius (2, 4, 7 km) and waits
    for the recovery sweep.
  - **The wait was the drivers:** booking latency stayed flat in the same runs,
    and run B shows the same burst offered in milliseconds once the cars were
    there.
  - **No offer within 30 s:** in run 1 over 1% of bookings got none; 30 s is the
    histogram's top bucket.
- **Failed requests, server side:**
  - run 1: one booking was refused with 409, because the rider still had an
    active trip from an earlier run
  - run B: one quote was refused with 422. The server keeps no record of which
    code, but the likely one is `TRIP_TOO_SHORT`: the script's random drop
    lands within the 300 m minimum about one time in a hundred
  - run C: one booking failed in k6, and the server recorded no failed
    response for it
- **Pool:** idle in run B (5 active of 10, none waiting).
- **Pool, run C:** full for one 5 s sample at the end of the burst, 120 waiting.
  - **Why:** 226 offers were accepted within seconds, each a transaction, while
    the teardown read and cancelled the 200 trips.
  - **Effect:** accepts slowed to p95 107 ms and p99 179 ms, far inside the
    12 s offer timeout. The bookings had finished, at p95 16.6 ms.
- **Pool, run 1:** peaked at 10 active with 185 waiting. That came from the
  script's setup, which logs in 200 riders a minute before the burst, not from
  the bookings. It led to the [login fix](#bcrypt-outside-the-login-transaction).
- **Teardown fees:** 24 of run C's 200 cancellations carried a fee, because a
  fast driver had already arrived before the burst ended.

### Soak, 30 minutes

Run 2026-10-22 23:51 to 2026-10-23 00:22 IST, on the released code, with a fresh
backend.
- **The simulator** (command in `load-tests/soak.js`'s header):
  - 2,000 drivers at 90 to 110 km/h in a 10 km box
  - 600 riders booking 60 trips a minute and paying from their wallets
  - every booking goes through matching, the ride, the fare, the ledger and a
    rating, and wallets top up through the mock gateway
  - its drivers send about 667 positions a second
- **k6, `soak.js`:** 150 riders doing the rest of what the app does, about 30
  requests a second:
  - look at nearby cars and get a quote
  - follow a trip with `GET /trips/active` for half a minute
  - read the history and the wallet
  - pause 10 to 30 s
- **Windows:** the first five minutes after k6 started, against the last five.

```bash
docker run --rm -i --add-host host.docker.internal:host-gateway \
  -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run /scripts/soak.js
```

Server side, the whole 30 minutes and its two ends (p95):

| Request | Count | p50 | p95 | p99 | p95, first 5 min | p95, last 5 min |
|---|---|---|---|---|---|---|
| `GET /drivers/nearby` | 5,327 | 1.6 ms | **2.5 ms** | 3.3 ms | 2.8 ms | 2.3 ms |
| `POST /fares/quotes` | 7,077 | 2.8 ms | 4.8 ms | 5.6 ms | 5.4 ms | 4.2 ms |
| `POST /trips` | 1,745 | 13.2 ms | 18.7 ms | 34.5 ms | 20.9 ms | 16.2 ms |
| `GET /trips/active` | 37,021 | 2.0 ms | 3.0 ms | 4.0 ms | 3.5 ms | 2.8 ms |
| `GET /trips` (history) | 5,254 | 2.1 ms | 3.2 ms | 4.1 ms | 3.7 ms | 3.0 ms |
| `GET /wallet` | 7,140 | 2.5 ms | 3.8 ms | 39.5 ms | 40.6 ms | 3.4 ms |
| Booking to first offer | | 13.6 ms | 18.9 ms | | 18.7 ms | 16.5 ms |
| Time to assign | | 2.01 s | 3.03 s | | 3.01 s | 3.04 s |
| Location ingest | | 1.7 ms | 3.5 ms | | 3.8 ms | 3.4 ms |

The JVM, per five minutes:

| Minutes | Old generation, lowest | Live data after GC, highest | Heap, highest | Threads |
|---|---|---|---|---|
| 0 to 5 | 468 MiB | 479 MiB | 938 MiB | 90 |
| 5 to 10 | 470 MiB | 474 MiB | 901 MiB | 90 |
| 10 to 15 | 473 MiB | 551 MiB | 1,094 MiB | 90 |
| 15 to 20 | 465 MiB | 472 MiB | 1,220 MiB | 91 |
| 20 to 25 | 464 MiB | 570 MiB | 1,262 MiB | 93 |
| 25 to 30 | 458 MiB | 535 MiB | 1,329 MiB | 92 |

Throughout: no 5xx, no request waiting for a connection, CPU about 3%, GC
pauses about 330 ms per five minutes, and an event backlog of at most 8.

k6 summary:

```
checks_succeeded...: 100.00% 43792 out of 43792
{ name:nearby }..............: avg=3.59ms min=1.33ms med=2.46ms max=83.46ms  p(90)=3.5ms  p(95)=4.06ms
{ name:quote }...............: avg=4.69ms min=1.19ms med=3.62ms max=67.29ms  p(90)=5.2ms  p(95)=5.94ms
http_req_failed................: 0.03%  20 out of 55190
http_reqs......................: 55190  30.128447/s
```

**Reading:**
- **No latency drift:** every request's p95 in the last five minutes is at or
  below its p95 in the first five.
- **Nearby target met:** p95 2.5 ms against the spec's 10 ms.
- **The wallet's slow start:** p95 40.6 ms, in the first five minutes only.
  - **Why:** on their first pass the 150 k6 riders reached the wallet step
    together, 16 reads a second for 15 seconds, on a backend two minutes old.
  - **After that:** their random pauses spread them out, and the wallet held
    3.4 ms.
- **No memory drift:** the old generation's floor after collection stayed
  between 458 and 473 MiB, and ended lower than it began.
  - **The rising peak** is G1 growing the young generation within its 3.9 GB
    limit, not memory that stays.
- **Threads:** steady at 90 to 93.
- **k6's 20 failed requests:** quotes refused with 422 because the random drop
  was too close to the pickup, which the script expects.

**Earlier attempts:** three soaks came before this one, and each found
something:
1. **Interrupted:** Windows standby froze it for 25 minutes; see
   [Standby](#standby-during-the-runs).
2. **Supply-limited, then abandoned:** k6's riders booked cars against 600
   drivers. Two faults in the test design showed:
   - in the first wave, 95 of 150 bookings found no car
   - a k6 rider cannot hand its driver the OTP, which the simulator passes
     between its own riders and drivers, so every trip stopped at
     `DRIVER_ARRIVED` for good

   Bookings moved to the simulator's riders. The trips the earlier runs had
   stranded were cancelled through the API by their riders, or completed by
   their drivers, before this run.
3. **Tokens expired:** it ran its 30 minutes, but the script logged in once,
   and access tokens live 15 minutes. From then on every k6 request answered
   401. The script now renews each rider's token after 10 minutes, as an app
   does.

### Login storm

`load-tests/login-storm.js` logs in riders at a fixed rate while one more rider
polls `GET /trips/active` 20 times a second. That canary needs a database
connection, like most requests.

```bash
docker run --rm -i --add-host host.docker.internal:host-gateway -v "$PWD/load-tests:/scripts" \
  grafana/k6:2.3.0 run -e LOGINS=600 -e PER_SECOND=200 /scripts/login-storm.js
```

Each case below is one run, measured by k6. Pool and CPU figures are server side.

| Case | Login p95 | Canary p95 | Pool peak (active / waiting) | Longest wait for a connection | CPU peak |
|---|---|---|---|---|---|
| Before the fix, 100 logins/s | 71.4 ms | 4.37 ms | 7 / 0 | — | — |
| Before the fix, 200 logins/s | 1.07 s | **1.02 s** | 10 / 126 | 1.84 s | 21% |
| After the fix, pool 10, 200 logins/s | 482 ms | **182 ms** | 10 / 40 | 578 ms | 31% |
| After the fix, pool 30, 200 logins/s | 657 ms | 298 ms | — | 510 ms | 48% |

**Reading:**
- **100 logins a second:** fine before the fix.
- **200 logins a second:** before the fix, the canary waited a second for a
  connection.
- **After the fix:** the canary waits 182 ms. The remaining wait is CPU, not the
  pool: BCrypt is meant to be slow, and 200 hashes a second is several cores of
  work on the same laptop as everything else.
- **Pool size:** a pool of 30 made it worse, as described under
  [Tuning](#connection-pool-size).

### Chaos

`./mvnw -Pchaos verify` runs `FullStackChaos` against the containerised stack
from the E2E tests.

| Test | Result |
|---|---|
| Redis paused for 20 s with 20 drivers streaming | Readiness 503 within 20 s; `GET /drivers/nearby` refused with 503 in under 5 s; `/livez` under 1 s throughout; positions counted `unavailable`; live threads 101 before and 101 after 20 s; all recovered after the unpause |
| Kafka stopped for 60 s while trips complete | Trips kept completing; 76 trip events published, 76 records on the topic, 76 distinct; every completed trip charged once |
| Backend killed and restarted while a trip waited for a driver | Restarted in 23.9 s; the trip was offered and assigned |

### Invariants after the runs

After every run above, 2026-10-23 00:26 IST, the database was checked for
what spec section 2 says must never happen.
- **Covers:** every trip and ledger entry the runs left behind (9,841 trips,
  7,777 completed).
- **Query:** `load-tests/explain/invariants.sql`, run against the application's
  database; its two key checks are below.

| Check | Result |
|---|---|
| Two trips of one driver whose time with that driver overlaps (assignment to completion or cancellation) | **0**, over 8,536 trips ever assigned and 1,995 drivers |
| Drivers holding more than one active trip | **0** |
| All ledger entries, credits minus debits | **0 paise**, over 8,727 transactions and 25,176 entries |
| Transactions whose entries do not balance | **0** |
| Accounts whose cached balance differs from their entries | **0**; all balances sum to 0 |

```sql
-- Double assignment: two trips of one driver whose time with the driver overlaps.
SELECT count(*) FROM trips a JOIN trips b ON a.driver_id = b.driver_id AND a.id < b.id
WHERE a.assigned_at IS NOT NULL AND b.assigned_at IS NOT NULL
  AND tstzrange(a.assigned_at, coalesce(a.completed_at, a.cancelled_at, 'infinity'))
   && tstzrange(b.assigned_at, coalesce(b.completed_at, b.cancelled_at, 'infinity'));
-- The ledger: every entry, credits minus debits.
SELECT sum(CASE direction WHEN 'CREDIT' THEN amount_paise ELSE -amount_paise END)
FROM ledger_entries;
```

## Tuning

Each change below was measured before and after, with the setup above.

### Spoof rejections in the first ingest run

- **Symptom:** in ingest run 1, one position in five was `rejected_spoof`, and
  each rejection was a strike.
- **Cause: the test.**
  - **What it did:** each run started every driver at a new random point,
    kilometres from where the run before had left it minutes earlier. That is a
    teleport, which the speed check exists to refuse.
  - **Why it stuck:** the rejected position was not stored, so every later fix
    was judged against the old one too.
- **First response, reverted:** Phase 10 first exempted a session's first fix
  from the speed check.
  - **Why reverted:** the review found that a spoofing app could then teleport by
    reconnecting before each fake position.
  - **Now:** a reconnect's first fix is judged against the last position like
    any other. The time since that position is in the division, so a driver who
    drove with the app off arrives at an ordinary speed.
- **The fix:** `location-ingest.js` now starts each driver at the same point
  every run.
- **Runs 2 to 4:** they ran with the exemption. It skipped one comparison on
  each driver's first fix, so their latency stands. Their lack of rejections
  owes something to it.

| | Accepted/s | Rejected as spoof/s | Ingest p95 | p99 | Peak heap |
|---|---|---|---|---|---|
| Run 1 | 524.1 | 127.9 | 17.7 ms | 48.8 ms | 1,191 MiB |
| Run 2 | 664.9 | 0 | 2.8 ms | 5.9 ms | 529 MiB |

Run 2 also had the flagging fix below, which removed most of the latency.

### Flagging a spoofer once

- **Problem:** every strike past the fifth flagged the driver again. That was a
  transaction, and a warning, per rejected position: 111,318 log lines in
  ingest run 1.
- **Change:** flagging now happens at the fifth strike, and again only at each
  further multiple of five.
- **Why multiples:** flagging is idempotent, so a repeat changes nothing once a
  flag is stored. The repeats are there in case an attempt failed, for example a
  connection timeout.
- **After:** no flag lines in ingest run 2. The longest wait for a connection
  fell from 790 ms to 5.5 ms.

### BCrypt outside the login transaction

- **Problem:** `login` and `register` were `@Transactional`, so each held a
  database connection across its BCrypt hash. The fastest login in the
  100-a-second run took 60.8 ms. At 200 logins a second, logins held the whole
  pool, and every other request queued behind them.
- **Change:** the hash now runs with no transaction open.
  - **Login:** reads the user, checks the password, then records the outcome in
    a short transaction. That transaction re-reads the user, so a lock that
    landed in between still counts.
  - **Register:** hashes before its transaction opens.
- **Timing:** an unknown phone is checked against a dummy hash. Otherwise its
  faster answer would tell an attacker which numbers have accounts.
- **Result:** at 200 logins a second, the canary's p95 fell from 1.02 s to
  182 ms, and the longest wait for a connection from 1.84 s to 578 ms.

### Connection pool size

- **Measured:** with the login fix in place, a pool of 30 against 10.
- **Result:** at 200 logins a second, every figure was worse with 30. The canary
  p95 went from 182 to 298 ms, logins from 482 to 657 ms, and CPU from 31% to
  48%.
- **Why:** more connections let more transactions run at once inside a
  PostgreSQL limited to the same cores.
- **Decision:** the pool stays at Hikari's default of 10. In every other run the
  pool never had a request waiting: ingest, bursts A and B, and the soak.

### Lettuce

The backend uses Spring Data Redis's Lettuce defaults, plus Phase 10's 1 s
command and connect timeouts.
- **Ordinary commands:** they share one multiplexed connection. Lettuce writes
  each one without waiting for the replies before it, so commands from every
  thread pipeline themselves over that connection.
- **Pipelines** (`executePipelined`): each takes a connection of its own, and
  with no pool on the classpath that is a new one per call.
- **Flushing:** a pipeline writes each command to the socket as it is queued
  (`PipeliningFlushPolicy.flushEachCommand`).

**What the runs showed, at 2,000 drivers:**
- **The shared connection keeps up:** the ingest script ran about 760 times a
  second at p50 0.5 ms and p99 1.6 ms, through `EVALSHA`.
- **The big pipeline is the live map's:** every 2 s it reads each live
  driver's state, 2,000 `HMGET`s in one pipeline, about 970 a second.
  - Each read opens a connection: `HELLO`, the connection handshake, ran 0.5
    times a second.
  - With one write per command, each `HMGET` took 16 to 22 ms at p50 from
    queueing to reply.

**Tried: buffered pipeline flushes.** `PipeliningFlushPolicy.buffered(256)` sends
the commands in writes of 256. It was measured as an A/B: runs 3 and 4 of the
ingest test, on the same data, differing only in this setting.

| Flush policy | Live map `HMGET` p50 / p95 / p99 | Ingest `EVALSHA` p95 / p99 | Location ingest p95 / p99 |
|---|---|---|---|
| One write per command (default) | 16.5 / 27.3 / 32.8 ms | 1.04 / 1.60 ms | **2.8 / 3.9 ms** |
| Writes of 256 | **6.9 / 12.1 / 17.7 ms** | 1.20 / 1.80 ms | 3.6 / 8.7 ms |

**Reading:**
- **Buffering helped the snapshot:** reads became about 2.4 times faster.
- **It hurt ingest:** the ingest tail, which is what the spec measures, doubled
  at p99.
  - **Why:** a write of 256 commands arrives at Redis all at once, and Redis
    serves it before the ingest scripts queued behind it on the other
    connection. One write per command interleaves the two more finely.
- **Decision:** the live map's snapshot is a background job every 2 s, and a
  10 ms faster read of it is worth less than a driver's position. The default
  stays.
- **Not done:** pooling the pipelines' connections would save the connection
  setup every 2 s.
  - **The saving:** one handshake, which took 0.6 ms at p50.
  - **The cost:** `commons-pool2`, a dependency the spec does not list.
  - **Add it when:** pipelines run often enough that the handshakes show in the
    `HELLO` rate.

### JPA fetch plans

The N+1 budgets in `QueryBudgetIntegrationTest` count every statement each read
sends, with datasource-proxy. Each holds at one or two queries, whatever the size
of the page:

| Endpoint | Statements | What they are |
|---|---|---|
| `GET /trips` (20 trips) | 1 | the page, on the history index |
| `GET /trips/active` | 1 | the active trip |
| `GET /wallet` (20 entries) | 2 | the balance and the page of entries |

`Trip` has no lazy associations (other modules are referenced by id), so there is
nothing to fetch-join. No change was needed.

### Indexes, checked with EXPLAIN ANALYZE

Every query a scheduled job or a list endpoint sends was read with
`EXPLAIN (ANALYZE, BUFFERS)` on a year of data for a mid-size city. The data sat
in a separate database on the same PostgreSQL 17.11, so no run was disturbed.
- **Trips:** 1,000,600 (407 MB): 20,000 riders, one of them with 5,000 trips;
  2,000 drivers, about 500 trips each; 100 waiting for a driver, 500 riding.
- **Trip events:** 2,001,100 audit rows.
- **Foreign keys:** dropped in that copy. They never change a read plan.
- **Runs:** each query ran twice; the warm run is the one shown.
- **Scripts:** `load-tests/explain/`, with the commands in
  [`load-tests/README.md`](../load-tests/README.md).

| Query | Plan | Rows read | Buffers | Time |
|---|---|---|---|---|
| Rider history, first page | Index scan `trips_rider_history_idx`, incremental sort on id | 22 | 25 | 0.08 ms |
| Driver history, first page | Index scan `trips_driver_history_idx`, incremental sort on id | 22 | 25 | 0.07 ms |
| Recovery sweep (waiting over 30 s) | Index scan `trips_waiting_idx`; per trip, one backward index probe of `trip_events_trip_idx` | 99 | 796 | 0.55 ms |
| Silent-driver check, first batch of 100 | Bitmap AND of `trips_one_active_per_driver` and `trips_in_progress_idx` | 432 | 21 | 0.25 ms |
| Live map: active trips | Index-only scan of `trips_one_active_per_rider` | 600 | 5 | 0.08 ms |
| Live map: last 15 minutes | Index scan `trips_requested_at_idx` | 436 | 18 | 0.17 ms |
| **History, page 2, before** | Bitmap OR, then a sort of every older trip | **4,980** | **201** | **1.48 ms** |
| **History, page 2, after** | Index scan `trips_rider_history_idx`, stops after a page | **22** | **4** | **0.05 ms** |

**Reading:**
- **Indexes:** every query uses one, and every first page reads about a page of
  rows. No index was added.
- **The fault: pages after the first read the whole older history.** The keyset
  condition `requested_at < :at OR (requested_at = :at AND id < :id)` was
  planned as a bitmap OR.
  - **What it read:** for a rider with 5,000 trips, page 2 read and sorted all
    4,980 older trips to return 21. The cost grew with the length of the
    history, which a keyset cursor exists to avoid.
- **The fix:** the same condition, written as
  `requested_at <= :at AND (requested_at < :at OR id < :id)`.
  - **Why it works:** the first term bounds the index scan, which stops after a
    page.
  - **Results:** identical.
  - **Cost:** no index or migration.
  - **Test:** `pagesThroughTripsBookedInTheSameInstant` covers the tie on
    `requested_at` that the id breaks.
- **Real users:** a rider's history is short, so most see no difference. A busy
  driver's history is in the thousands within a year.

## How to record a run

Each entry gets its own section above, with:

- **Date and commit:** the commit at the time of the run (`git rev-parse --short HEAD`).
- **Hardware:** CPU, cores, RAM, and whether the stack ran on one machine.
- **Topology:** single backend or the two-instance scale profile.
- **Command:** the exact k6 invocation, so it can be repeated.
- **Raw output:** the k6 summary, unedited.
- **Reading:** what the numbers mean, including where a target was missed.

A missed target is recorded as a missed target. It is not rounded, re-run until
it passes, or quietly dropped.

### Surge tick, 2026-10-10

- **Date and commit:** 2026-10-10, branch `phase-8-surge` as tagged `phase-8`.
- **Hardware:** Intel Core i7-13650HX (14 cores, 20 threads), 16 GB RAM,
  Windows 11. Redis 7.4 in Docker Desktop through Testcontainers, on the same
  machine.
- **Topology:** one backend, in the integration-test context.
- **Command:** `./mvnw -pl backend test -Dtest=SurgeIntegrationTest`. The test is
  `pricesThreeHundredBusyCellsWellInsideATick`, and the result is in its log
  line "Surge tick over ...".
- **Workload:**
  - 2,000 available drivers spread across Bengaluru, a third in each
    vehicle-type GEO set
  - 300 distinct geohash-6 cells with two requests each
  - 1,112 cells priced: the busy cells and their neighbours

**Raw output:**

```
Surge tick over 300 busy cells, 1112 priced, 2,000 drivers: first 94 ms, then 75 ms
```

**Reading:** one tick takes under 0.5% of its 30-second interval. The first tick
includes loading the Lua scripts. The test fails above 3,000 ms (a tenth of a
tick), which leaves a wide margin for a busier city or a slower Redis.
