// The live map's script (Phase 9). A file of its own so the page's Content-Security-Policy can
// allow scripts from this server and Leaflet's CDN only - never inline ones (Phase 10).
'use strict';

const COLOURS = { AVAILABLE: '#2e7d32', TO_PICKUP: '#ef8f00', TO_DROP: '#1565c0', OFFLINE: '#9e9e9e' };

const map = L.map('map', { preferCanvas: true }).setView([12.975, 77.62], 12);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
}).addTo(map);
// Drawn in this order, so drivers sit on top of their trip lines, and both on top of surge.
const surgeLayer = L.layerGroup().addTo(map);
const tripLayer = L.layerGroup().addTo(map);
const driverLayer = L.layerGroup().addTo(map);

let tokens = null;
let socket = null;

const $ = id => document.getElementById(id);

$('login').addEventListener('submit', async event => {
  event.preventDefault();
  $('login-error').textContent = '';
  const response = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: $('phone').value, password: $('password').value })
  });
  if (!response.ok) {
    $('login-error').textContent = 'Sign-in failed (' + response.status + ')';
    return;
  }
  tokens = await response.json();
  $('password').value = '';
  $('login').style.display = 'none';
  $('kpis').style.display = 'block';
  $('legend').style.display = 'block';
  connect();
});

/** Spring closes the socket when the access token expires: refresh it, then connect again. */
async function reconnect() {
  $('status').textContent = 'reconnecting...';
  const response = await fetch('/api/v1/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: tokens.refreshToken })
  }).catch(() => null);
  if (response && response.ok) {
    tokens = await response.json();
  }
  setTimeout(connect, 2000);
}

/** STOMP 1.2 over a plain WebSocket: a few lines, so no client library. */
function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(scheme + '://' + location.host + '/ws', ['v12.stomp']);
  socket.binaryType = 'arraybuffer';
  socket.onopen = () => send('CONNECT', {
    'accept-version': '1.2',
    host: location.hostname,
    Authorization: 'Bearer ' + tokens.accessToken,
    'heart-beat': '0,0'
  });
  socket.onmessage = event => {
    // Text frames normally; a binary one is decoded rather than dropped.
    const data = typeof event.data === 'string' ? event.data : new TextDecoder().decode(event.data);
    const frame = parse(data);
    if (!frame) {
      return;
    }
    if (frame.command === 'CONNECTED') {
      $('status').textContent = 'connected';
      send('SUBSCRIBE', { id: 'live-map', destination: '/topic/admin/live-map' });
    } else if (frame.command === 'MESSAGE') {
      draw(JSON.parse(frame.body).payload);
    } else if (frame.command === 'ERROR') {
      // A token without the ADMIN role ends here: the subscription is refused.
      $('status').textContent = 'refused: ' + (frame.headers.message || 'error');
      socket.onclose = null;
    }
  };
  socket.onclose = reconnect;
}

function send(command, headers) {
  const lines = Object.entries(headers).map(([name, value]) => name + ':' + value);
  socket.send(command + '\n' + lines.join('\n') + '\n\n\0');
}

function parse(data) {
  const split = data.indexOf('\n\n');
  if (split < 0) {
    return null; // A heartbeat.
  }
  const [command, ...lines] = data.slice(0, split).split('\n');
  const headers = {};
  lines.forEach(line => {
    const colon = line.indexOf(':');
    headers[line.slice(0, colon)] = line.slice(colon + 1);
  });
  const body = data.slice(split + 2);
  return { command, headers, body: body.endsWith('\0') ? body.slice(0, -1) : body };
}

function draw(snapshot) {
  surgeLayer.clearLayers();
  snapshot.surgeCells.forEach(cell => {
    const heat = Math.min(1, (cell.multiplier - 1) / 1.5);
    if (heat <= 0) {
      return;
    }
    L.rectangle([[cell.minLat, cell.minLng], [cell.maxLat, cell.maxLng]], {
      stroke: false, fillColor: '#d32f2f', fillOpacity: 0.15 + 0.45 * heat
    }).addTo(surgeLayer);
  });

  tripLayer.clearLayers();
  driverLayer.clearLayers();
  let online = 0;
  snapshot.drivers.forEach(driver => {
    const state = driver.tripPhase || driver.status || 'OFFLINE';
    if (driver.status !== 'OFFLINE') {
      online++;
    }
    if (driver.targetLat != null) {
      L.polyline([[driver.lat, driver.lng], [driver.targetLat, driver.targetLng]], {
        color: COLOURS[state], weight: 2, opacity: 0.7, dashArray: state === 'TO_PICKUP' ? '4 4' : null
      }).addTo(tripLayer);
    }
    L.circleMarker([driver.lat, driver.lng], {
      radius: 4, weight: 1, color: '#fff', fillColor: COLOURS[state] || COLOURS.OFFLINE, fillOpacity: 1
    }).addTo(driverLayer);
  });

  const kpis = snapshot.kpis;
  $('k-online').textContent = online;
  $('k-active').textContent = kpis.activeTrips;
  $('k-tpm').textContent = kpis.tripsPerMinute.toFixed(1);
  $('k-assign').textContent = kpis.averageSecondsToAssign == null
    ? '-' : kpis.averageSecondsToAssign.toFixed(1) + ' s';
  $('k-nodriver').textContent = (100 * kpis.noDriverRate).toFixed(1) + ' %';
  $('k-cancel').textContent = (100 * kpis.cancelRate).toFixed(1) + ' %';
  $('surge-off').style.display = snapshot.surgeOff ? 'block' : 'none';
  $('status').textContent = new Date(snapshot.at).toLocaleTimeString();
}
