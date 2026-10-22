// Shared by the load tests: logging in the simulator's accounts, and STOMP frames over a plain
// WebSocket. The accounts come from the simulator, whose setup is idempotent (simulator/README.md):
// driver i is +9170 and i in eight digits, rider i +9171..., all with the same password.
import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const PASSWORD = __ENV.PASSWORD || 'simulated-rider-or-driver';

export function driverPhone(i) {
  return '+9170' + String(i).padStart(8, '0');
}

export function riderPhone(i) {
  return '+9171' + String(i).padStart(8, '0');
}

/**
 * Logs in accounts {first} to {first + count - 1}, {batch} at a time in parallel, and returns
 * their access tokens. A fresh {first} gives a run riders with no trip left over from another.
 */
export function loginAll(phoneOf, count, first = 0, batch = 50) {
  const tokens = [];
  for (let from = first; from < first + count; from += batch) {
    const requests = [];
    for (let i = from; i < Math.min(first + count, from + batch); i++) {
      requests.push([
        'POST',
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ phone: phoneOf(i), password: PASSWORD }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
      ]);
    }
    for (const response of http.batch(requests)) {
      if (response.status !== 200) {
        throw new Error(`login failed with ${response.status}: ${response.body}`);
      }
      tokens.push(response.json('accessToken'));
    }
  }
  return tokens;
}

/** One STOMP 1.2 frame. */
export function frame(command, headers, body = '') {
  const lines = Object.entries(headers).map(([name, value]) => `${name}:${value}`);
  return `${command}\n${lines.join('\n')}\n\n${body}\0`;
}

export function authorized(token, extra = {}) {
  return {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra },
  };
}

/** A point within {radiusMeters} of {lat},{lng}, uniformly over the disc. */
export function near(lat, lng, radiusMeters) {
  const r = radiusMeters * Math.sqrt(Math.random());
  const bearing = Math.random() * 2 * Math.PI;
  return {
    lat: lat + (r * Math.cos(bearing)) / 111195,
    lng: lng + (r * Math.sin(bearing)) / (111195 * Math.cos((lat * Math.PI) / 180)),
  };
}
