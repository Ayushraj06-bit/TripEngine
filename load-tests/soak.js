// Normal traffic for 30 minutes, to watch memory and latency drift. The simulator's riders book
// and ride: they hand their driver the OTP to start the trip, which no outside client can, and pay
// from their wallets. This script adds the rest of what riders' apps do - look at nearby cars, get
// quotes, follow a trip with GET /trips/active, check the history and the wallet, pause. Drift is
// read from Prometheus: request p95, heap after GC and threads, first five minutes against the
// last five (docs/performance.md).
//
// The simulator, with drivers fast and close so a ride takes minutes and the city reaches a steady
// state early. Leave it running:
//   java -jar simulator/target/rideflow-simulator-0.1.0-SNAPSHOT.jar --simulator.drivers=2000 \
//     --simulator.riders=600 --simulator.demand.requests-per-minute=60 \
//     --simulator.driver.accept-probability=1 \
//     --simulator.driver.response-delay.min=1s --simulator.driver.response-delay.max=3s \
//     --simulator.driver.min-speed-kmph=90 --simulator.driver.max-speed-kmph=110 \
//     --simulator.driver.disconnect-probability=0 --simulator.driver.spoof-probability=0 \
//     --simulator.service-area.min-lat=12.93 --simulator.service-area.max-lat=13.02 \
//     --simulator.service-area.min-lng=77.57 --simulator.service-area.max-lng=77.67
// Then (load-tests/README.md); the riders are the simulator's, read here and never booked:
//   docker run --rm -i --add-host host.docker.internal:host-gateway \
//     -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run /scripts/soak.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, authorized, loginAll, near, riderPhone } from './lib/rideflow.js';

const RIDERS = Number(__ENV.RIDERS || 150);
const FIRST_RIDER = Number(__ENV.FIRST_RIDER || 0);
const MINUTES = Number(__ENV.MINUTES || 30);

export const options = {
  setupTimeout: '5m',
  scenarios: {
    riders: {
      executor: 'constant-vus',
      vus: RIDERS,
      duration: `${MINUTES}m`,
    },
  },
  // As seen by k6, through Docker's port forwarding; the server's own figures are in Prometheus.
  thresholds: {
    'http_req_duration{name:nearby}': ['p(95)<50'],
    'http_req_duration{name:quote}': ['p(95)<50'],
    checks: ['rate>0.99'],
  },
};

// Access tokens live 15 minutes and the soak runs 30. An app renews its token before it expires,
// and so does each rider here, by logging in again after 10 minutes.
const RENEW_AFTER_MS = 10 * 60 * 1000;
let riderToken = null;
let tokenSince = 0;

export function setup() {
  return { tokens: loginAll(riderPhone, RIDERS, FIRST_RIDER) };
}

function currentToken(data) {
  if (riderToken === null) {
    riderToken = data.tokens[__VU - 1];
    tokenSince = Date.now();
  } else if (Date.now() - tokenSince > RENEW_AFTER_MS) {
    riderToken = loginAll(riderPhone, 1, FIRST_RIDER + __VU - 1)[0];
    tokenSince = Date.now();
  }
  return riderToken;
}

export default function (data) {
  const token = currentToken(data);
  const pickup = near(12.975, 77.62, 5000);
  const nearby = http.get(
    `${BASE_URL}/api/v1/drivers/nearby?lat=${pickup.lat}&lng=${pickup.lng}&type=CAR`,
    { ...authorized(token), tags: { name: 'nearby' } },
  );
  const drop = near(pickup.lat, pickup.lng, 4000);
  const quotes = http.post(`${BASE_URL}/api/v1/fares/quotes`, JSON.stringify({ pickup, drop }), {
    ...authorized(token),
    tags: { name: 'quote' },
  });
  // A drop within 300 m of the pickup is refused as too short (422), as it should be.
  check(nearby, { 'nearby answered': (r) => r.status === 200 });
  check(quotes, { 'quoted, or too short': (r) => r.status === 200 || r.status === 422 });

  // Half a minute on the trip screen, refreshing as the app's resync does.
  for (let poll = 0; poll < 6; poll++) {
    sleep(5);
    const active = http.get(`${BASE_URL}/api/v1/trips/active`, {
      ...authorized(token),
      tags: { name: 'active' },
    });
    check(active, { 'active answered': (r) => r.status === 200 || r.status === 204 });
  }
  http.get(`${BASE_URL}/api/v1/trips?limit=20`, { ...authorized(token), tags: { name: 'history' } });
  http.get(`${BASE_URL}/api/v1/wallet?limit=20`, { ...authorized(token), tags: { name: 'wallet' } });
  sleep(10 + Math.random() * 20);
}
