import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import { useSession } from '@/state/session';
import { server } from '@/test/server';
import { api, configureAuth } from './client';
import { ApiError } from './problem';
import type { Schemas } from './types';

const ACTIVE = '/api/v1/trips/active';

function trip(id: string): Schemas['TripView'] {
  return { id, status: 'REQUESTED' };
}

/** Answers 200 only to the given token, 401 to anything else, and counts what it saw. */
function protectedTrip(validToken: string) {
  const seen: (string | null)[] = [];
  server.use(
    http.get(ACTIVE, ({ request }) => {
      const header = request.headers.get('Authorization');
      seen.push(header);
      return header === `Bearer ${validToken}`
        ? HttpResponse.json(trip('t-1'))
        : HttpResponse.json(
            { status: 401, title: 'Unauthorized', code: 'UNAUTHENTICATED' },
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          );
    }),
  );
  return seen;
}

beforeEach(() => {
  useSession.getState().signedOut();
});

afterEach(() => {
  configureAuth({ refresh: () => Promise.resolve(null), onSessionExpired: () => undefined });
});

describe('the session', () => {
  it('attaches the access token', async () => {
    useSession.getState().signedIn('token-a');
    const seen = protectedTrip('token-a');

    await expect(api.get(ACTIVE)).resolves.toEqual(trip('t-1'));
    expect(seen).toEqual(['Bearer token-a']);
  });

  it('renews once on a 401 and retries the original request with the new token', async () => {
    useSession.getState().signedIn('expired');
    const seen = protectedTrip('fresh');
    const refresh = vi.fn(() => Promise.resolve('fresh'));
    configureAuth({ refresh, onSessionExpired: () => undefined });

    await expect(api.get(ACTIVE)).resolves.toEqual(trip('t-1'));
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(seen).toEqual(['Bearer expired', 'Bearer fresh']);
    expect(useSession.getState().accessToken).toBe('fresh');
  });

  it('shares one renewal between requests that fail together', async () => {
    useSession.getState().signedIn('expired');
    protectedTrip('fresh');
    let release: (token: string) => void = () => undefined;
    const refresh = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          release = resolve;
        }),
    );
    configureAuth({ refresh, onSessionExpired: () => undefined });

    const calls = [api.get(ACTIVE), api.get(ACTIVE), api.get(ACTIVE)];
    await vi.waitFor(() => {
      expect(refresh).toHaveBeenCalled();
    });
    release('fresh');

    await expect(Promise.all(calls)).resolves.toHaveLength(3);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('retries with a token someone else already renewed, without renewing again', async () => {
    useSession.getState().signedIn('expired');
    const seen: (string | null)[] = [];
    server.use(
      http.get(ACTIVE, ({ request }) => {
        const header = request.headers.get('Authorization');
        seen.push(header);
        if (header === 'Bearer expired') {
          // Another request renewed the session while this one was on the wire.
          useSession.getState().signedIn('fresh');
          return new HttpResponse(null, { status: 401 });
        }
        return HttpResponse.json(trip('t-1'));
      }),
    );
    const refresh = vi.fn(() => Promise.resolve('never-used'));
    configureAuth({ refresh, onSessionExpired: () => undefined });

    await expect(api.get(ACTIVE)).resolves.toEqual(trip('t-1'));
    expect(refresh).not.toHaveBeenCalled();
    expect(seen).toEqual(['Bearer expired', 'Bearer fresh']);
  });

  it('ends the session when it cannot be renewed', async () => {
    useSession.getState().signedIn('expired');
    protectedTrip('fresh');
    const onSessionExpired = vi.fn();
    configureAuth({ refresh: () => Promise.resolve(null), onSessionExpired });

    const failure = api.get(ACTIVE);
    await expect(failure).rejects.toBeInstanceOf(ApiError);
    await expect(failure).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' });
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
    expect(useSession.getState().accessToken).toBeNull();
  });

  it('treats a failing renewal like a refused one', async () => {
    useSession.getState().signedIn('expired');
    protectedTrip('fresh');
    const onSessionExpired = vi.fn();
    configureAuth({ refresh: () => Promise.reject(new Error('offline')), onSessionExpired });

    await expect(api.get(ACTIVE)).rejects.toMatchObject({ status: 401 });
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('never sends or renews a session for calls marked auth: false', async () => {
    useSession.getState().signedIn('token-a');
    let header: string | null = 'unset';
    server.use(
      http.post('/api/v1/auth/login', ({ request }) => {
        header = request.headers.get('Authorization');
        return HttpResponse.json(
          { code: 'INVALID_CREDENTIALS', status: 401 },
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    );
    const refresh = vi.fn(() => Promise.resolve('fresh'));
    configureAuth({ refresh, onSessionExpired: () => undefined });

    await expect(
      api.post('/api/v1/auth/login', {
        auth: false,
        body: { phone: '+919876543210', password: 'wrong-password' },
      }),
    ).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
    expect(header).toBeNull();
    expect(refresh).not.toHaveBeenCalled();
  });
});

describe('failures', () => {
  it('carry the backend code, detail and extra properties', async () => {
    server.use(
      http.post('/api/v1/trips/:tripId/arrive', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Unprocessable Content',
            status: 422,
            detail: 'You are 420 m from the pickup point',
            code: 'NOT_AT_PICKUP',
            distanceMeters: 420,
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const error = await api.post('/api/v1/trips/{tripId}/arrive', { path: { tripId: 't-9' } }).then(
      () => null,
      (e: unknown) => e,
    );
    expect(error).toBeInstanceOf(ApiError);
    const problem = error as ApiError;
    expect(problem.code).toBe('NOT_AT_PICKUP');
    expect(problem.status).toBe(422);
    expect(problem.detail).toBe('You are 420 m from the pickup point');
    expect(problem.number('distanceMeters')).toBe(420);
    expect(problem.properties).not.toHaveProperty('title');
  });

  it('read the wait from Retry-After', async () => {
    server.use(
      http.get(ACTIVE, () =>
        HttpResponse.json(
          { status: 429, code: 'RATE_LIMITED' },
          {
            status: 429,
            headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '17' },
          },
        ),
      ),
    );
    await expect(api.get(ACTIVE)).rejects.toMatchObject({
      code: 'RATE_LIMITED',
      retryAfterSeconds: 17,
    });
  });

  it('fall back to a code from the status when the body has none', async () => {
    server.use(http.get(ACTIVE, () => new HttpResponse('Bad gateway', { status: 502 })));
    await expect(api.get(ACTIVE)).rejects.toMatchObject({ status: 502, code: 'INTERNAL_ERROR' });
  });

  it('report no answer at all as NETWORK', async () => {
    server.use(http.get(ACTIVE, () => HttpResponse.error()));
    await expect(api.get(ACTIVE)).rejects.toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it('let a deliberate cancellation through as itself, not as a failure', async () => {
    server.use(http.get(ACTIVE, () => HttpResponse.json(trip('t-1'))));
    const controller = new AbortController();
    controller.abort();
    await expect(api.get(ACTIVE, { signal: controller.signal })).rejects.toMatchObject({
      name: 'AbortError',
    });
  });
});

describe('requests', () => {
  it('fill path parameters, encoded, and skip query parameters that are not set', async () => {
    let url = '';
    server.use(
      http.get('/api/v1/trips', ({ request }) => {
        url = request.url;
        return HttpResponse.json({ trips: [] });
      }),
      http.get('/api/v1/trips/:tripId', ({ request }) => {
        url = request.url;
        return HttpResponse.json(trip('a/b'));
      }),
    );

    await api.get('/api/v1/trips/{tripId}', { path: { tripId: 'a/b' } });
    expect(new URL(url).pathname).toBe('/api/v1/trips/a%2Fb');

    await api.get('/api/v1/trips', { query: { limit: 20 } });
    expect(new URL(url).search).toBe('?limit=20');
  });

  it('send the body as JSON', async () => {
    let received: unknown;
    let contentType: string | null = null;
    server.use(
      http.post('/api/v1/trips/:tripId/cancel', async ({ request }) => {
        received = await request.json();
        contentType = request.headers.get('Content-Type');
        return HttpResponse.json(trip('t-1'));
      }),
    );
    await api.post('/api/v1/trips/{tripId}/cancel', {
      path: { tripId: 't-1' },
      body: { reason: 'Plans changed' },
    });
    expect(received).toEqual({ reason: 'Plans changed' });
    expect(contentType).toBe('application/json');
  });
});

/** Compiled, never run: the client refuses calls the backend would refuse. */
export function typeChecks() {
  expectTypeOf(api.get(ACTIVE)).resolves.toEqualTypeOf<Schemas['TripView']>();
  // @ts-expect-error - no such path
  void api.get('/api/v1/nowhere');
  // @ts-expect-error - the trip id is required
  void api.post('/api/v1/trips/{tripId}/cancel');
  // @ts-expect-error - a quote request needs a body
  void api.post('/api/v1/fares/quotes', {});
  // @ts-expect-error - nearby drivers need lat and lng
  void api.get('/api/v1/drivers/nearby', { query: { type: 'CAR' } });
}
