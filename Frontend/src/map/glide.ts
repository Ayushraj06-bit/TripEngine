import type { LatLng } from './MapCanvas';

/** Fast at first, settling at the end: the curve a moving car is drawn along. */
export function easeOut(t: number): number {
  return 1 - (1 - t) ** 3;
}

/** The point a fraction `t` of the way from `a` to `b`. Two seconds apart, a straight line is close enough. */
export function between(a: LatLng, b: LatLng, t: number): LatLng {
  return { lat: a.lat + (b.lat - a.lat) * t, lng: a.lng + (b.lng - a.lng) * t };
}
