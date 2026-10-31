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
    http.get('/api/v1/offers/current', () => new HttpResponse(null, { status: 204 })),
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

type VehicleType = 'BIKE' | 'AUTO' | 'CAR';

/** A quote as the backend sends it: 4.2 km, 18 minutes, valid for five minutes from now. */
export function quote(
  vehicleType: VehicleType,
  totalPaise: number,
  extra: Partial<Schemas['QuoteView']> = {},
): Schemas['QuoteView'] {
  return {
    quoteId: `quote-${vehicleType.toLowerCase()}`,
    vehicleType,
    totalPaise,
    distanceMeters: 4200,
    durationSeconds: 1080,
    surgeMultiplier: 1,
    expiresAt: new Date(Date.now() + 300_000).toISOString(),
    breakdown: { basePaise: 3000, distancePaise: totalPaise - 4000, timePaise: 1000 },
    ...extra,
  };
}

/** Prices are `quotes`, or each call's answer from `answer`. */
export function quotesAre(answer: Schemas['QuoteView'][] | (() => Response)) {
  server.use(
    http.post('/api/v1/fares/quotes', async ({ request }) => {
      await record(request);
      return typeof answer === 'function' ? answer() : HttpResponse.json({ quotes: answer });
    }),
  );
}

export function walletHas(balancePaise: number) {
  server.use(
    http.get('/api/v1/wallet', () =>
      HttpResponse.json({ account: 'RIDER_WALLET', balancePaise, movements: [] }),
    ),
  );
}

let movementIds = 0;

/** A line in the wallet's activity. */
export function movement(
  type: NonNullable<Schemas['Movement']['type']>,
  direction: 'CREDIT' | 'DEBIT',
  amountPaise: number,
): Schemas['Movement'] {
  movementIds += 1;
  return {
    entryId: `entry-${String(movementIds)}`,
    type,
    direction,
    amountPaise,
    createdAt: new Date().toISOString(),
  };
}

/** GET /wallet a page at a time: the first without a cursor, each next by the one before's. */
export function walletIs(
  ...pages: { balancePaise: number; movements: Schemas['Movement'][]; account?: string }[]
) {
  server.use(
    http.get('/api/v1/wallet', ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      const index = cursor === null ? 0 : Number(cursor);
      return HttpResponse.json({
        account: 'RIDER_WALLET',
        ...pages[index],
        nextCursor: index + 1 < pages.length ? String(index + 1) : undefined,
      });
    }),
  );
}

/** Bookings answered by `answer`, each one recorded with its Idempotency-Key. */
export function bookingAnswers(answer: () => Response) {
  server.use(
    http.post('/api/v1/trips', async ({ request }) => {
      await record(request);
      return answer();
    }),
  );
}

export function booked(id: string): Response {
  return HttpResponse.json({ id, status: 'REQUESTED', paymentMethod: 'CASH' }, { status: 202 });
}

/** GET /trips/{id} answers with this trip; /trips/active is left as it was. */
export function tripIs(id: string, status: Schemas['TripView']['status']) {
  server.use(
    http.get('/api/v1/trips/:tripId', ({ params }) =>
      params['tripId'] === 'active' ? undefined : HttpResponse.json({ id, status }),
    ),
  );
}

/** A trip as the backend sends it, with only what a test cares about filled in. */
export function trip(
  id: string,
  status: Schemas['TripView']['status'],
  extra: Partial<Schemas['TripView']> = {},
) {
  return { id, status, vehicleType: 'AUTO', ...extra } as Schemas['TripView'];
}

/** GET /trips/{id} answers with `view` (or each call's answer); cancelling records and answers. */
export function tripAnswers(view: Schemas['TripView'] | (() => Schemas['TripView'])) {
  server.use(
    http.get('/api/v1/trips/:tripId', ({ params }) =>
      params['tripId'] === 'active'
        ? undefined
        : HttpResponse.json(typeof view === 'function' ? view() : view),
    ),
  );
}

export function cancellingAnswers(answer: () => Response) {
  server.use(
    http.post('/api/v1/trips/:tripId/cancel', async ({ request }) => {
      await record(request);
      return answer();
    }),
  );
}

export function offerIs(offer: Schemas['OfferView'] | null) {
  server.use(
    http.get('/api/v1/offers/current', () =>
      offer ? HttpResponse.json(offer) : new HttpResponse(null, { status: 204 }),
    ),
  );
}

/** An offer of an auto ride near MG Road, open for twelve seconds from now. */
export function anOffer(extra: Partial<Schemas['OfferView']> = {}): Schemas['OfferView'] {
  return {
    offerId: 'offer-1',
    pickup: { lat: 12.976, lng: 77.607 },
    vehicleType: 'AUTO',
    estimatedFarePaise: 14800,
    expiresAt: new Date(Date.now() + 12_000).toISOString(),
    ...extra,
  };
}

export function offerAnswers(action: 'accept' | 'reject', answer: () => Response) {
  server.use(
    http.post(`/api/v1/offers/:offerId/${action}`, async ({ request }) => {
      await record(request);
      return answer();
    }),
  );
}

/** A driver's step on a trip - arrive, start, complete - recorded and answered by `answer`. */
export function tripAction(action: 'arrive' | 'start' | 'complete', answer: () => Response) {
  server.use(
    http.post(`/api/v1/trips/:tripId/${action}`, async ({ request }) => {
      await record(request);
      return answer();
    }),
  );
}
