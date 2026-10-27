import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import { useSession } from '@/state/session';
import { resetApp, signedInAs } from '@/test/app';
import { backendFor, problem, RIDER, seen } from '@/test/backend';
import { server } from '@/test/server';
import { installAuth, renew, restoreSession, signIn, signOut } from './auth';

beforeEach(resetApp);

afterEach(() => {
  Reflect.deleteProperty(navigator, 'locks');
});

describe('signing in', () => {
  it('asks for the cookie, keeps the token in memory only, and learns who the user is', async () => {
    backendFor(RIDER);
    const setItem = vi.spyOn(Storage.prototype, 'setItem');

    await expect(signIn(RIDER.phone, 'correct-horse')).resolves.toEqual(RIDER);

    expect(seen[0]?.headers.get('X-RideFlow-Client')).toBe('web');
    expect(useSession.getState()).toMatchObject({
      status: 'signed-in',
      accessToken: `access-for-${RIDER.id}`,
      user: RIDER,
      handoff: true,
    });
    // Every write to localStorage and sessionStorage goes through Storage.prototype.setItem.
    for (const [, value] of setItem.mock.calls) expect(value).not.toContain('access-for');
    expect(document.cookie).not.toContain('access-for');
  });

  it('stays signed out when the password is wrong', async () => {
    backendFor(RIDER);
    await expect(signIn(RIDER.phone, 'wrong')).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
    });
    expect(useSession.getState()).toMatchObject({ status: 'signed-out', accessToken: null });
  });
});

describe('restoring a session on a reload', () => {
  it('renews from the cookie once, however often it is asked', async () => {
    backendFor(RIDER);
    let renewals = 0;
    server.use(
      http.post('/api/v1/auth/refresh', ({ request }) => {
        renewals += 1;
        expect(request.headers.get('X-RideFlow-Client')).toBe('web');
        return HttpResponse.json({ accessToken: `access-for-${RIDER.id}`, expiresInSeconds: 900 });
      }),
    );

    // React's strict mode starts effects twice; a second renewal would present a spent token.
    await Promise.all([restoreSession(), restoreSession()]);

    expect(renewals).toBe(1);
    expect(useSession.getState()).toMatchObject({
      status: 'signed-in',
      user: RIDER,
      handoff: false,
    });
  });

  it('starts signed out when there is no session to renew', async () => {
    backendFor(RIDER);
    await restoreSession();
    expect(useSession.getState().status).toBe('signed-out');
  });

  it('starts signed out when the network is down', async () => {
    server.use(http.post('/api/v1/auth/refresh', () => HttpResponse.error()));
    await restoreSession();
    expect(useSession.getState().status).toBe('signed-out');
  });
});

describe('renewing', () => {
  it('takes turns with other tabs through the Web Locks API', async () => {
    server.use(
      http.post('/api/v1/auth/refresh', () =>
        HttpResponse.json({ accessToken: 'renewed', expiresInSeconds: 900 }),
      ),
    );
    const request = vi.fn((_name: string, task: () => Promise<unknown>) => task());
    Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true });

    await expect(renew()).resolves.toBe('renewed');
    expect(request).toHaveBeenCalledWith('rideflow-session', expect.any(Function));
  });

  it('answers null when the session is over, rather than failing', async () => {
    server.use(http.post('/api/v1/auth/refresh', () => problem(401, 'REFRESH_TOKEN_INVALID')));
    await expect(renew()).resolves.toBeNull();
  });
});

describe('the end of a session', () => {
  it('signs out on the server and here', async () => {
    backendFor(RIDER);
    signedInAs(RIDER);
    await signOut();
    const logout = seen.find((r) => r.path === '/api/v1/auth/logout');
    expect(logout?.headers.get('X-RideFlow-Client')).toBe('web');
    expect(useSession.getState()).toMatchObject({
      status: 'signed-out',
      user: null,
      accessToken: null,
    });
  });

  it('signs out here even when the server cannot be reached', async () => {
    server.use(http.post('/api/v1/auth/logout', () => HttpResponse.error()));
    signedInAs(RIDER);
    await signOut();
    expect(useSession.getState().status).toBe('signed-out');
  });

  it('hands the user to sign in once, when several requests find it over together', async () => {
    backendFor(RIDER);
    server.use(http.get('/api/v1/trips/active', () => problem(401, 'UNAUTHENTICATED')));
    const onExpired = vi.fn();
    installAuth(onExpired);
    signedInAs(RIDER);

    await Promise.allSettled([
      api.get('/api/v1/trips/active'),
      api.get('/api/v1/trips/active'),
      api.get('/api/v1/trips/active'),
    ]);

    expect(onExpired).toHaveBeenCalledTimes(1);
    expect(useSession.getState().status).toBe('signed-out');
  });
});
