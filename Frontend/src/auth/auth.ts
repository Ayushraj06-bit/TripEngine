import { api, configureAuth } from '@/api/client';
import { ApiError } from '@/api/problem';
import { sessionUser, useSession, type SessionUser } from '@/state/session';

/**
 * Signing in, staying signed in, and signing out.
 *
 * The refresh token never touches script: the backend sets it as an httpOnly cookie when asked
 * with this header (ADR 0017), and the browser sends it back on its own. The access token lives
 * in memory (state/session.ts).
 */
const WEB = { 'X-RideFlow-Client': 'web' } as const;

export type AccountRole = 'RIDER' | 'DRIVER';

/**
 * After a token is in hand: who it belongs to. Until then the app is not signed in. A sign-in on
 * a public page is a hand-off: that page sends the user on (guards.tsx).
 */
async function establish(accessToken: string, handoff: boolean): Promise<SessionUser> {
  useSession.getState().signedIn(accessToken);
  const user = sessionUser(await api.get('/api/v1/users/me'));
  useSession.getState().identified(user, handoff);
  return user;
}

function tokenFrom(tokens: { accessToken?: string }): string {
  if (!tokens.accessToken) throw new Error('The sign-in answer carried no access token');
  return tokens.accessToken;
}

export async function signIn(phone: string, password: string): Promise<SessionUser> {
  const tokens = await api.post('/api/v1/auth/login', {
    auth: false,
    headers: WEB,
    body: { phone, password },
  });
  return establish(tokenFrom(tokens), true);
}

export async function register(
  role: AccountRole,
  details: { name: string; phone: string; password: string },
): Promise<SessionUser> {
  const path = role === 'DRIVER' ? '/api/v1/auth/register/driver' : '/api/v1/auth/register/rider';
  const tokens = await api.post(path, { auth: false, headers: WEB, body: details });
  return establish(tokenFrom(tokens), true);
}

/**
 * A new access token from the refresh cookie, or null when the session cannot be renewed.
 *
 * Renewals take turns across tabs (the Web Locks API, where the browser has it). The backend
 * rotates the refresh token on every use and treats a spent one as stolen, revoking the session;
 * two tabs renewing at once would present the same cookie. Taking turns, the second sends the
 * cookie the first was just given.
 */
export async function renew(): Promise<string | null> {
  const attempt = async () => {
    try {
      return tokenFrom(await api.post('/api/v1/auth/refresh', { auth: false, headers: WEB }));
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.status === 400)) return null;
      throw error;
    }
  };
  return typeof navigator !== 'undefined' && 'locks' in navigator
    ? navigator.locks.request('rideflow-session', attempt)
    : attempt();
}

let restoring: Promise<void> | null = null;

/**
 * On start-up: renew the session from its cookie, if there is one. Runs once however often it is
 * asked - React's strict mode starts effects twice, and a second renewal at the same moment would
 * present a spent token.
 */
export function restoreSession(): Promise<void> {
  restoring ??= (async () => {
    try {
      const token = await renew();
      if (token) {
        await establish(token, false);
        return;
      }
    } catch {
      // No answer at all: start signed out; signing in again works once the network does.
    }
    useSession.getState().signedOut();
  })();
  return restoring;
}

/** Forget the restore that ran, so a test can start from nothing. */
export function resetSessionForTests(): void {
  restoring = null;
}

export async function signOut(): Promise<void> {
  try {
    await api.post('/api/v1/auth/logout', { auth: false, headers: WEB });
  } catch {
    // Signed out here regardless; the server's session simply expires.
  }
  useSession.getState().signedOut();
}

/**
 * Wires the API client to the session, once at start-up: renewals go through the cookie, and a
 * session that cannot be renewed hands the user to `onExpired` - which sends them to sign in,
 * keeping where they were.
 */
export function installAuth(onExpired: () => void): void {
  let expired = false;
  useSession.subscribe((state) => {
    if (state.status === 'signed-in') expired = false;
  });
  configureAuth({
    refresh: renew,
    onSessionExpired: () => {
      // Several requests can fail together; the user is sent to sign in once.
      if (expired) return;
      expired = true;
      useSession.getState().signedOut();
      onExpired();
    },
  });
}
