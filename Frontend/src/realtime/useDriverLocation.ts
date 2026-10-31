import { useEffect, useState } from 'react';
import type { LatLng } from '@/map/MapCanvas';
import { listen } from './live';
import { isNewer } from './useTripLive';

export const DRIVER_LOCATION = '/user/queue/driver-location';

/** After this long without a position, the one shown is old - and the screen says so. */
export const STALE_AFTER_MS = 30_000;

export type Phase = 'TO_PICKUP' | 'TO_DROP';

export interface DriverPosition {
  at: LatLng;
  phase: Phase;
  /** The backend's estimate to the pickup or the drop, in seconds; null when it has none. */
  etaSeconds: number | null;
  /** When it reached this device, on this device's clock. */
  receivedAt: number;
}

interface Payload {
  lat: number;
  lng: number;
  phase: Phase;
  etaSeconds?: number | null;
}

function isPayload(value: unknown): value is Payload {
  if (value === null || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v['lat'] === 'number' &&
    typeof v['lng'] === 'number' &&
    (v['phase'] === 'TO_PICKUP' || v['phase'] === 'TO_DROP')
  );
}

/**
 * The rider's driver, where the backend last said they were - pushed every two seconds or so while
 * they move, with its estimate to the next stop. Kept in order by seq, counted apart from the
 * trip's own updates. Thirty seconds without one and `stale` is set: the last position stays, said
 * to be old, and is never moved on by guesswork.
 */
export function useDriverLocation(tripId: string): {
  position: DriverPosition | null;
  stale: boolean;
} {
  const [position, setPosition] = useState<DriverPosition | null>(null);
  const [stale, setStale] = useState(false);

  useEffect(
    () =>
      listen(DRIVER_LOCATION, (message) => {
        if (message.tripId !== tripId || typeof message.seq !== 'number') return;
        if (!isPayload(message.payload) || !isNewer(`location:${tripId}`, message.seq)) return;
        const p = message.payload;
        setPosition({
          at: { lat: p.lat, lng: p.lng },
          phase: p.phase,
          etaSeconds: p.etaSeconds ?? null,
          receivedAt: Date.now(),
        });
        setStale(false);
      }),
    [tripId],
  );

  useEffect(() => {
    if (!position) return;
    const timer = setTimeout(() => {
      setStale(true);
    }, STALE_AFTER_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [position]);

  return { position, stale };
}
