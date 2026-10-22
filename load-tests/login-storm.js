// Logins in a burst - an app release, everyone signing in at once - while one rider keeps using
// the app. Measures whether logins starve other requests of database connections: the canary's
// GET /trips/active is a database read like any other request's (docs/performance.md, tuning).
//
// Accounts: the simulator's riders (see booking-burst.js). Then (load-tests/README.md):
//   docker run --rm -i --add-host host.docker.internal:host-gateway \
//     -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run /scripts/login-storm.js
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { BASE_URL, PASSWORD, authorized, loginAll, riderPhone } from './lib/rideflow.js';

const LOGINS = Number(__ENV.LOGINS || 500);
const PER_SECOND = Number(__ENV.PER_SECOND || 100);

export const options = {
  scenarios: {
    storm: {
      executor: 'constant-arrival-rate',
      rate: PER_SECOND,
      timeUnit: '1s',
      duration: `${Math.ceil(LOGINS / PER_SECOND)}s`,
      preAllocatedVUs: 200,
      exec: 'login',
    },
    canary: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: `${Math.ceil(LOGINS / PER_SECOND) + 2}s`,
      preAllocatedVUs: 20,
      exec: 'canary',
    },
  },
  // No pass mark: here to make k6 report each request type on its own.
  thresholds: {
    'http_req_duration{name:login}': ['p(95)<60000'],
    'http_req_duration{name:canary}': ['p(95)<60000'],
  },
};

export function setup() {
  return { canary: loginAll(riderPhone, 1, 599)[0] };
}

export function login() {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ phone: riderPhone(exec.scenario.iterationInTest % LOGINS), password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
  );
  check(response, { 'logged in': (r) => r.status === 200 });
}

export function canary(data) {
  http.get(`${BASE_URL}/api/v1/trips/active`, { ...authorized(data.canary), tags: { name: 'canary' } });
}
