import type { LatLng } from '@/map/MapCanvas';

/**
 * The city RideFlow serves: the backend's `rideflow.service-area` polygon, copied here so a point
 * outside it is refused before anything is sent. serviceArea.test.ts reads the backend's
 * application.yml and fails when the two differ.
 */
export const SERVICE_AREA: readonly LatLng[] = [
  { lat: 13.14, lng: 77.46 },
  { lat: 13.14, lng: 77.78 },
  { lat: 12.82, lng: 77.78 },
  { lat: 12.82, lng: 77.46 },
];

/** The box around the area, to steer a place search towards it. */
export const SERVICE_BOUNDS = {
  south: Math.min(...SERVICE_AREA.map((p) => p.lat)),
  north: Math.max(...SERVICE_AREA.map((p) => p.lat)),
  west: Math.min(...SERVICE_AREA.map((p) => p.lng)),
  east: Math.max(...SERVICE_AREA.map((p) => p.lng)),
};

function onSegment(p: LatLng, a: LatLng, b: LatLng): boolean {
  const cross = (b.lng - a.lng) * (p.lat - a.lat) - (b.lat - a.lat) * (p.lng - a.lng);
  if (Math.abs(cross) > 1e-12) return false;
  return (
    p.lat >= Math.min(a.lat, b.lat) &&
    p.lat <= Math.max(a.lat, b.lat) &&
    p.lng >= Math.min(a.lng, b.lng) &&
    p.lng <= Math.max(a.lng, b.lng)
  );
}

/** Inside the area, as the backend decides it: a point exactly on an edge counts as inside. */
export function insideServiceArea(p: LatLng, area: readonly LatLng[] = SERVICE_AREA): boolean {
  let inside = false;
  for (let i = 0, j = area.length - 1; i < area.length; j = i++) {
    const a = area[i];
    const b = area[j];
    if (!a || !b) continue;
    if (onSegment(p, a, b)) return true;
    if (a.lat > p.lat !== b.lat > p.lat) {
      const lngAtLat = ((b.lng - a.lng) * (p.lat - a.lat)) / (b.lat - a.lat) + a.lng;
      if (p.lng < lngAtLat) inside = !inside;
    }
  }
  return inside;
}

/** Metres between two points, in a straight line over the Earth's surface. */
export function metresBetween(a: LatLng, b: LatLng): number {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLng = (b.lng - a.lng) * rad;
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
}
