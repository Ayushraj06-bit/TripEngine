import type { LatLng } from '@/map/MapCanvas';

export type LocationFailure = 'denied' | 'unavailable' | 'timeout';

export type Located =
  { ok: true; at: LatLng; accuracyMetres: number } | { ok: false; reason: LocationFailure };

/** Rougher than this, the rider is asked to check the pickup on the map. */
export const ROUGH_METRES = 200;

/**
 * Where the device is, asked once, when the rider opens "Where to?" - in context, not at sign-in.
 * Never rejects: every way it can fail is an answer the screen has words for.
 */
export function locate(): Promise<Located> {
  if (typeof navigator === 'undefined' || !('geolocation' in navigator)) {
    return Promise.resolve({ ok: false, reason: 'unavailable' });
  }
  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (position) => {
        resolve({
          ok: true,
          at: { lat: position.coords.latitude, lng: position.coords.longitude },
          accuracyMetres: position.coords.accuracy,
        });
      },
      (error) => {
        // GeolocationPositionError: 1 permission denied, 2 position unavailable, 3 timeout.
        resolve({
          ok: false,
          reason: error.code === 1 ? 'denied' : error.code === 3 ? 'timeout' : 'unavailable',
        });
      },
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 60_000 },
    );
  });
}
