import type { LatLng } from '@/map/MapCanvas';
import type { NearbyDriver, VehicleType } from '@/map/NearbyDrivers';
import { metresBetween } from '@/places/serviceArea';
import type { Stop, StopKind } from '@/state/bookingDraft';

/**
 * Closer than this, pickup and drop are the same place. The backend's own minimum - 300 m by
 * road - is checked when the fare is asked for, by the server that knows the roads.
 */
export const SAME_PLACE_METRES = 100;

export function otherKind(kind: StopKind): StopKind {
  return kind === 'pickup' ? 'drop' : 'pickup';
}

/** Why `at` cannot be the `kind`, given the other stop - or null when it can. */
export function tooClose(kind: StopKind, at: LatLng, other: Stop | null): string | null {
  if (!other || metresBetween(at, other.at) >= SAME_PLACE_METRES) return null;
  return `That’s where your ${otherKind(kind)} is. Choose a ${kind} further away.`;
}

/** A pin with no address: its position, as a map app shows it. */
export function position(at: LatLng): string {
  const lat = `${Math.abs(at.lat).toFixed(4)}° ${at.lat >= 0 ? 'N' : 'S'}`;
  const lng = `${Math.abs(at.lng).toFixed(4)}° ${at.lng >= 0 ? 'E' : 'W'}`;
  return `${lat}, ${lng}`;
}

const NOUNS: Record<VehicleType, [string, string]> = {
  BIKE: ['bike', 'bikes'],
  AUTO: ['auto', 'autos'],
  CAR: ['car', 'cars'],
};

/** "3 bikes, 2 autos and 1 car near your pickup." - only what the backend reported. */
export function nearbyWords(drivers: readonly NearbyDriver[]): string {
  if (drivers.length === 0) return 'No drivers near your pickup right now.';
  const counts = (Object.keys(NOUNS) as VehicleType[])
    .map((type) => [type, drivers.filter((d) => d.type === type).length] as const)
    .filter(([, n]) => n > 0)
    .map(([type, n]) => `${String(n)} ${NOUNS[type][n === 1 ? 0 : 1]}`);
  return `${new Intl.ListFormat('en-IN', { type: 'conjunction' }).format(counts)} near your pickup.`;
}
