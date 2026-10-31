import { useEffect, useState } from 'react';
import type { LatLng } from '@/map/MapCanvas';
import { failureOf, type LocationFailure } from '@/places/location';
import { onReconnect, publish } from '@/realtime/live';

export const LOCATION_DESTINATION = '/app/driver/location';

/**
 * As often as the driver moves, but no more than this: the backend ignores anything faster. A move
 * inside it is sent as soon as it allows - the last one before stopping, at a pickup say, included.
 */
const AT_MOST_MS = 2000;
/**
 * And at least this often while still - give or take the second the check runs on: the backend
 * counts 30 s of silence as gone (stale).
 */
const AT_LEAST_MS = 10_000;
/** The backend's max-accuracy-meters: a rougher fix is refused, so it is not sent. */
export const ROUGHEST_METRES = 100;

export type Sharing =
  | { state: 'off' }
  | { state: 'finding' }
  | { state: 'sharing'; at: LatLng; accuracyMetres: number }
  | { state: 'rough'; at: LatLng; accuracyMetres: number }
  | { state: 'failed'; reason: LocationFailure };

interface Fix {
  at: LatLng;
  accuracyMetres: number;
  headingDegrees: number | null;
  speedKmph: number | null;
}

/**
 * While `active`, the device's position goes to the backend over the live connection: as the
 * driver moves, at most every 2 s, and at least every 10 s while they stand still. A fix too rough
 * for the backend is held back and said so; a refusal or a lost signal stops sharing, and is said
 * so too - the app never lets a driver think they are visible when they are not.
 */
export function useShareLocation(active: boolean): Sharing {
  const [sharing, setSharing] = useState<Sharing>({ state: 'finding' });
  const supported = typeof navigator !== 'undefined' && 'geolocation' in navigator;

  useEffect(() => {
    if (!active || !supported) return;
    let last: Fix | null = null;
    // Whether `last` is newer than what was sent.
    let held = false;
    let sentAt = 0;
    // Rises within one connection; the backend expects it to start again after a reconnect.
    let seq = 0;

    function send(fix: Fix) {
      const sent = publish(LOCATION_DESTINATION, {
        lat: fix.at.lat,
        lng: fix.at.lng,
        accuracyMeters: fix.accuracyMetres,
        headingDegrees: fix.headingDegrees,
        speedKmph: fix.speedKmph,
        seq: seq++,
        clientTime: new Date().toISOString(),
      });
      if (!sent) return;
      sentAt = Date.now();
      held = false;
    }

    const watch = navigator.geolocation.watchPosition(
      (position) => {
        const { latitude, longitude, accuracy, heading, speed } = position.coords;
        const fix: Fix = {
          at: { lat: latitude, lng: longitude },
          accuracyMetres: accuracy,
          headingDegrees: heading ?? null,
          speedKmph: speed === null ? null : speed * 3.6,
        };
        if (accuracy > ROUGHEST_METRES) {
          setSharing({ state: 'rough', at: fix.at, accuracyMetres: accuracy });
          return;
        }
        last = fix;
        held = true;
        setSharing({ state: 'sharing', at: fix.at, accuracyMetres: accuracy });
        if (Date.now() - sentAt >= AT_MOST_MS) send(fix);
      },
      (error) => {
        last = null;
        setSharing({ state: 'failed', reason: failureOf(error.code) });
      },
      { enableHighAccuracy: true, maximumAge: 5000, timeout: 20_000 },
    );
    const keepAlive = setInterval(() => {
      const since = Date.now() - sentAt;
      if (last && (since >= AT_LEAST_MS || (held && since >= AT_MOST_MS))) send(last);
    }, 1000);
    const stopReconnect = onReconnect(() => {
      seq = 0;
      if (last) send(last);
    });

    return () => {
      navigator.geolocation.clearWatch(watch);
      clearInterval(keepAlive);
      stopReconnect();
      // Ready for the next time the driver goes online.
      setSharing({ state: 'finding' });
    };
  }, [active, supported]);

  if (!active) return { state: 'off' };
  if (!supported) return { state: 'failed', reason: 'unavailable' };
  return sharing;
}
