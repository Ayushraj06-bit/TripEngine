// PROJECT_SPEC 2: booking (POST /trips) p95 under 150 ms, and booking to first offer p95 under
// 500 ms - 200 bookings in 10 seconds, with simulator drivers online to be offered them. First
// offer is measured by the server (matching_first_offer_seconds in Prometheus).
//
// Accounts and drivers: run the simulator with --simulator.drivers=300 --simulator.riders=200
// --simulator.demand.requests-per-minute=0 and leave it running: its drivers stay online and
// answer offers; its riders only lend this script their accounts. That is run 1 in
// docs/performance.md, where the burst outruns the cars nearby. Run B has enough of them: the
// simulator command in soak.js's header, with --simulator.drivers=2000. Then (load-tests/README.md):
//   docker run --rm -i --add-host host.docker.internal:host-gateway \
//     -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run /scripts/booking-burst.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { BASE_URL, authorized, loginAll, near, riderPhone } from './lib/rideflow.js';

const BOOKINGS = Number(__ENV.BOOKINGS || 200);
const FIRST_RIDER = Number(__ENV.FIRST_RIDER || 0);
const SECONDS = Number(__ENV.SECONDS || 10);
const booked = new Counter('trips_booked');

export const options = {
  setupTimeout: '5m',
  scenarios: {
    burst: {
      executor: 'constant-arrival-rate',
      rate: BOOKINGS / SECONDS,
      timeUnit: '1s',
      duration: `${SECONDS}s`,
      preAllocatedVUs: BOOKINGS,
    },
  },
  thresholds: {
    'http_req_duration{name:book}': ['p(95)<150'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  return { tokens: loginAll(riderPhone, BOOKINGS, FIRST_RIDER) };
}

export default function (data) {
  // Each booking by a different rider: one active trip per rider.
  const token = data.tokens[exec.scenario.iterationInTest % data.tokens.length];
  const pickup = near(12.975, 77.62, 4000);
  const drop = near(pickup.lat, pickup.lng, 3000);
  const quotes = http.post(`${BASE_URL}/api/v1/fares/quotes`, JSON.stringify({ pickup, drop }), {
    ...authorized(token),
    tags: { name: 'quote' },
  });
  if (!check(quotes, { quoted: (r) => r.status === 200 })) {
    return;
  }
  const car = quotes.json('quotes').find((q) => q.vehicleType === 'CAR');
  const key = `burst-${exec.scenario.iterationInTest}-${Date.now()}`;
  const trip = http.post(
    `${BASE_URL}/api/v1/trips`,
    JSON.stringify({ quoteId: car.quoteId, vehicleType: 'CAR', paymentMethod: 'CASH' }),
    { ...authorized(token, { 'Idempotency-Key': key }), tags: { name: 'book' } },
  );
  if (check(trip, { booked: (r) => r.status === 202 })) {
    booked.add(1);
  }
}

// Simulated drivers would wait at each pickup for an OTP no k6 rider can give, holding the trip and
// the driver for good. Each trip is cancelled as soon as the burst ends instead, so the next run
// finds riders and drivers free. Most cancellations are free: the trip is still waiting, or within
// the grace period after assignment. One whose driver has already arrived costs the rider the fee.
export function teardown(data) {
  const active = http.batch(
    data.tokens.map((token) => [
      'GET',
      `${BASE_URL}/api/v1/trips/active`,
      null,
      { ...authorized(token), tags: { name: 'teardown' } },
    ]),
  );
  const cancels = [];
  active.forEach((response, i) => {
    if (response.status === 200) {
      cancels.push([
        'POST',
        `${BASE_URL}/api/v1/trips/${response.json('id')}/cancel`,
        JSON.stringify({ reason: 'Load test over' }),
        { ...authorized(data.tokens[i]), tags: { name: 'teardown' } },
      ]);
    }
  });
  http.batch(cancels);
}
