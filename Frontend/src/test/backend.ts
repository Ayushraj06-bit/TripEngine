import { http, HttpResponse } from 'msw';
import type { Schemas } from '@/api/types';
import type { LatLng } from '@/map/MapCanvas';
import { NOMINATIM } from '@/places/nominatim';
import type { SessionUser } from '@/state/session';
import { server } from './server';

/** The mocked backend's answers, built the way the real one sends them. */

export const RIDER: SessionUser = {
  id: '0190a000-0000-7000-8000-000000000001',
  name: 'Asha Rao',
  phone: '+919876543210',
  role: 'RIDER',
};

export const DRIVER: SessionUser = {
  id: '0190a000-0000-7000-8000-000000000002',
  name: 'Ravi Kumar',
  phone: '+919812345678',
  role: 'DRIVER',
};

/** An RFC 9457 problem with the backend's code. */
export function problem(
  status: number,
  code: string,
  extra: Record<string, unknown> = {},
  headers: Record<string, string> = {},
) {
  return HttpResponse.json(
    { type: 'about:blank', status, code, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  );
}

/** Every request the mocked backend saw, for asserting on what the app sent. */
export const seen: { method: string; path: string; headers: Headers; body: unknown }[] = [];

async function record(request: Request) {
  const text = await request.clone().text();
  seen.push({
    method: request.method,
    path: new URL(request.url).pathname,
    headers: request.headers,
    body: text ? (JSON.parse(text) as unknown) : undefined,
  });
}

/** A backend where `user` signs in with `password`, and has no trip under way. */
export function backendFor(user: SessionUser, password = 'correct-horse') {
  server.use(
    http.post('/api/v1/auth/login', async ({ request }) => {
      await record(request);
      const body = (await request.json()) as { phone: string; password: string };
      if (body.phone !== user.phone || body.password !== password) {
        return problem(401, 'INVALID_CREDENTIALS');
      }
      return HttpResponse.json({ accessToken: `access-for-${user.id}`, expiresInSeconds: 900 });
    }),
    http.post('/api/v1/auth/register/:role', async ({ request }) => {
      await record(request);
      return HttpResponse.json(
        { accessToken: `access-for-${user.id}`, expiresInSeconds: 900 },
        { status: 201 },
      );
    }),
    http.get('/api/v1/users/me', ({ request }) =>
      request.headers.get('Authorization') === `Bearer access-for-${user.id}`
        ? HttpResponse.json({ ...user, registeredAt: '2026-10-01T10:00:00Z' })
        : problem(401, 'UNAUTHENTICATED'),
    ),
    http.get('/api/v1/trips/active', () => new HttpResponse(null, { status: 204 })),
    http.post('/api/v1/auth/refresh', () => problem(401, 'REFRESH_TOKEN_INVALID')),
    http.get('/api/v1/drivers/nearby', () => HttpResponse.json([])),
    http.post('/api/v1/auth/logout', async ({ request }) => {
      await record(request);
      return new HttpResponse(null, { status: 204 });
    }),
  );
}

export function activeTripIs(id: string, status: Schemas['TripView']['status'] = 'IN_PROGRESS') {
  server.use(
    http.get('/api/v1/trips/active', () => HttpResponse.json({ id, status })),
    http.get('/api/v1/trips/:tripId', () => HttpResponse.json({ id, status })),
  );
}

export function driverProfileIs(profile: Schemas['DriverProfile']) {
  server.use(http.get('/api/v1/drivers/me', () => HttpResponse.json(profile)));
}

export const VEHICLE: Schemas['VehicleView'] = {
  type: 'CAR',
  plateNumber: 'KA01AB1234',
  model: 'Maruti Swift',
  colour: 'White',
};

/** A place as Nominatim's jsonv2 format sends it. */
export interface RawPlace {
  name: string;
  display_name: string;
  lat: string;
  lon: string;
}

export const INDIRANAGAR: RawPlace = {
  name: 'Indiranagar',
  display_name:
    'Indiranagar, Bengaluru East City Corporation, Bengaluru, Bangalore North, Bengaluru Urban, Karnataka, 560038, India',
  lat: '12.9783692',
  lon: '77.6408356',
};

export const MG_ROAD: RawPlace = {
  name: 'MG Road',
  display_name: 'MG Road, Shanthala Nagar, Ashok Nagar, Bengaluru, Karnataka, 560001, India',
  lat: '12.9756',
  lon: '77.6069',
};

/** North of the service area's edge, so it is found but cannot be chosen. */
export const AIRPORT: RawPlace = {
  name: 'Kempegowda International Airport',
  display_name:
    'Kempegowda International Airport, Airport Road, Devanahalli, Bengaluru Rural, Karnataka, 560300, India',
  lat: '13.1989089',
  lon: '77.7068182',
};

export function at(place: RawPlace): LatLng {
  return { lat: Number(place.lat), lng: Number(place.lon) };
}

/** Place search answers with `places`, whatever is asked. */
export function placesAre(places: RawPlace[]) {
  server.use(
    http.get(`${NOMINATIM}/search`, async ({ request }) => {
      await record(request);
      return HttpResponse.json(places);
    }),
  );
}

/** The address under any pin is `place`, or there is none. */
export function addressIs(place: RawPlace | null) {
  server.use(
    http.get(`${NOMINATIM}/reverse`, async ({ request }) => {
      await record(request);
      return HttpResponse.json(place ?? { error: 'Unable to geocode' });
    }),
  );
}

export function nearbyAre(byType: Partial<Record<'BIKE' | 'AUTO' | 'CAR', LatLng[]>>) {
  server.use(
    http.get('/api/v1/drivers/nearby', ({ request }) => {
      const type = new URL(request.url).searchParams.get('type') as 'BIKE' | 'AUTO' | 'CAR';
      return HttpResponse.json(byType[type] ?? []);
    }),
  );
}
