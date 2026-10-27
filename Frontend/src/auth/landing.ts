import { api } from '@/api/client';
import type { Schemas } from '@/api/types';
import type { SessionUser } from '@/state/session';

/**
 * Where a user goes after signing in - decided by what the server says now, never by what the
 * browser remembers. A rider who closed the tab mid-ride opens straight back into it.
 */
export async function landingFor(user: SessionUser, returnTo?: string | null): Promise<string> {
  if (user.role === 'ADMIN') return '/admin';

  const trip = await activeTrip();
  if (trip) return user.role === 'DRIVER' ? `/driver/trip/${trip.id}` : `/trip/${trip.id}`;

  if (user.role === 'DRIVER') {
    const profile = await api.get('/api/v1/drivers/me');
    if (!profile.vehicle) return '/driver/vehicle';
    if (profile.approvalStatus !== 'APPROVED') return '/driver/approval';
    return safeReturnTo(returnTo, '/driver') ?? '/driver';
  }
  return safeReturnTo(returnTo, '/') ?? '/';
}

/** The trip under way for the signed-in user, or null: the backend answers 204 when there is none. */
export async function activeTrip(): Promise<(Schemas['TripView'] & { id: string }) | null> {
  // Wider than the generated type on purpose: the API description lists only the 200, and the
  // client returns undefined for the 204 the backend sends when there is no trip.
  const trip = (await api.get('/api/v1/trips/active')) as Schemas['TripView'] | undefined;
  return trip?.id ? { ...trip, id: trip.id } : null;
}

/**
 * A path to come back to after signing in, if it is safe: one of the app's own, under the
 * user's own part of it. Anything else - another site, "//evil.example" - is ignored.
 */
export function safeReturnTo(raw: string | null | undefined, area: '/' | '/driver'): string | null {
  if (!raw?.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return null;
  if (/^\/(welcome|sign-in|sign-up)\b/.test(raw)) return null;
  if (area === '/driver') return raw.startsWith('/driver') ? raw : null;
  return raw.startsWith('/driver') || raw.startsWith('/admin') ? null : raw;
}
