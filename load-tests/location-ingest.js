// PROJECT_SPEC 2: 2,000 online drivers, about 670 location updates a second, server-side ingest
// p95 under 20 ms. Each driver holds one STOMP connection and sends a position every 3 s for
// 10 minutes. Ingest latency is measured by the server (location_ingest_seconds in Prometheus):
// a STOMP SEND has no reply to time.
//
// Accounts first: run the simulator once with --simulator.drivers=2000 --simulator.riders=0, then
// stop it. Then, from the repository root (load-tests/README.md):
//   docker run --rm -i --add-host host.docker.internal:host-gateway \
//     -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run /scripts/location-ingest.js
import { WebSocket } from 'k6/websockets';
import { Counter } from 'k6/metrics';
import { BASE_URL, driverPhone, frame, loginAll } from './lib/rideflow.js';

const DRIVERS = Number(__ENV.DRIVERS || 2000);
const MINUTES = Number(__ENV.MINUTES || 10);
const RAMP_SECONDS = 60;
const EVERY_MS = 3000;

const sent = new Counter('location_frames_sent');
const errors = new Counter('stomp_errors');
const connected = new Counter('stomp_connected');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    drivers: {
      executor: 'per-vu-iterations',
      vus: DRIVERS,
      iterations: 1,
      maxDuration: `${MINUTES + 3}m`,
    },
  },
  thresholds: {
    stomp_errors: ['count==0'],
  },
};

export function setup() {
  return { tokens: loginAll(driverPhone, DRIVERS) };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  // Spread the connections over the first minute, as drivers coming online would be.
  const startDelay = ((__VU - 1) % RAMP_SECONDS) * 1000 + Math.random() * 1000;
  const until = Date.now() + startDelay + MINUTES * 60 * 1000;
  // Around central Bengaluru, moving a few metres between reports. Each driver starts at the same
  // point every run: a random one would be a jump of kilometres from where the last run left
  // them, minutes ago, which the backend rightly refuses as a spoof.
  let lat = 12.95 + (((__VU * 7919) % 1000) / 1000) * 0.05;
  let lng = 77.58 + (((__VU * 104729) % 1000) / 1000) * 0.06;
  let seq = 0;

  setTimeout(() => {
    const socket = new WebSocket(BASE_URL.replace(/^http/, 'ws') + '/ws');
    socket.addEventListener('open', () => {
      socket.send(
        frame('CONNECT', {
          'accept-version': '1.2',
          host: 'rideflow',
          Authorization: `Bearer ${token}`,
          'heart-beat': '0,0',
        }),
      );
    });
    socket.addEventListener('message', (event) => {
      const text = typeof event.data === 'string' ? event.data : '';
      if (text.startsWith('ERROR')) {
        errors.add(1);
        return;
      }
      if (!text.startsWith('CONNECTED')) {
        return;
      }
      connected.add(1);
      const timer = setInterval(() => {
        if (Date.now() > until) {
          clearInterval(timer);
          socket.close();
          return;
        }
        lat += (Math.random() - 0.5) * 0.0004;
        lng += (Math.random() - 0.5) * 0.0004;
        seq += 1;
        socket.send(
          frame(
            'SEND',
            { destination: '/app/driver/location', 'content-type': 'application/json' },
            JSON.stringify({
              lat,
              lng,
              accuracyMeters: 8,
              seq,
              clientTime: new Date().toISOString(),
            }),
          ),
        );
        sent.add(1);
      }, EVERY_MS);
    });
    socket.addEventListener('error', () => errors.add(1));
  }, startDelay);
}
