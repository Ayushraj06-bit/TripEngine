import type { LatLng } from '@/map/MapCanvas';

/** `insecure`: the page is not served over https (or localhost), so the browser refuses. */
export type LocationFailure = 'denied' | 'unavailable' | 'timeout' | 'insecure';

export type Located =
  { ok: true; at: LatLng; accuracyMetres: number } | { ok: false; reason: LocationFailure };

/** Rougher than this, the rider is asked to check the pickup on the map. */
export const ROUGH_METRES = 200;

/**
 * Why the browser gave no position, from its GeolocationPositionError code: 1 permission denied,
 * 2 position unavailable, 3 timeout. A page that is not secure is refused with the same 1 as a
 * rider who said no, though asking them to turn location on would not help.
 */
export function failureOf(code: number): LocationFailure {
  // Only an explicit false: where the property is missing (jsdom) it is undefined, not insecure.
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-boolean-literal-compare
  if (code === 1) return window.isSecureContext === false ? 'insecure' : 'denied';
  return code === 3 ? 'timeout' : 'unavailable';
}

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
        resolve({ ok: false, reason: failureOf(error.code) });
      },
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 60_000 },
    );
  });
}
